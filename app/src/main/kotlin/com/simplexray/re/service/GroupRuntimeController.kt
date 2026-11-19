package com.simplexray.re.service

import android.os.SystemClock
import com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.ProxyGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class FallbackRuntimeInfo(
    val inFallback: Boolean = false,
    val nextRecoveryAtElapsedMs: Long = 0L,
    val activeLeaf: String? = null,
)

internal data class ProbeResult(
    val healthy: Boolean,
    val latencyMs: Long,
    val completedAtElapsedMs: Long,
)

/** Owns group selection and timed primary recovery for every control surface. */
internal class GroupRuntimeController(
    initialWorkspace: GroupWorkspace,
    private val probeNode: suspend (nodeTag: String, testUrl: String) -> Long?,
    private val applyBalancerTarget: suspend (groupTag: String, targetOutbound: String) -> Unit,
    private val fallbackRelayOutbounds: Map<String, String>,
    private val balancerTags: Set<String>,
    private val publishStatus: (
        activeNodes: Map<String, String>,
        fallbackStates: Map<String, FallbackRuntimeInfo>,
        latencies: Map<String, Long>,
    ) -> Unit,
    private val logEvent: (String) -> Unit = VpnStateHub::emitLog,
) {
    @Volatile private var workspace = initialWorkspace
    private val probeResults = ConcurrentHashMap<Pair<String, String>, ProbeResult>()
    private val fallbackStates = ConcurrentHashMap<String, FallbackRuntimeInfo>()
    private val manualMembers = ConcurrentHashMap<String, GroupMemberRef>()
    private val recoveryJobs = ConcurrentHashMap<String, Job>()
    private val urlTestJobs = ConcurrentHashMap<String, Job>()
    private val stateLock = Any()
    private val syncLock = Mutex()
    private var expiryJob: Job? = null
    private var runtimeScope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        runtimeScope = scope
        restartUrlTestLoops(scope)
        fallbackStates.filterValues { it.nextRecoveryAtElapsedMs > 0L }.keys.forEach(::startRecoveryLoop)
        expiryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                syncAllBalancersAndPublish()
                delay(1_000)
            }
        }
    }

    fun updateWorkspace(updated: GroupWorkspace) {
        val previous = workspace
        workspace = updated
        updated.groups.filter { it.selectedMember != previous.groupByTag(it.tag)?.selectedMember }
            .forEach { manualMembers.remove(it.tag) }
        for (tag in fallbackStates.keys) {
            val group = updated.groupByTag(tag)
            val priorGroup = previous.groupByTag(tag)
            if (group?.strategy != GroupStrategy.FALLBACK ||
                group.effectiveMembers().firstOrNull() != priorGroup?.effectiveMembers()?.firstOrNull()
            ) {
                clearFallback(tag)
            } else if (priorGroup != null && group.recoveryIntervalSeconds != priorGroup.recoveryIntervalSeconds) {
                val state = fallbackStates[tag]
                if (state != null && state.nextRecoveryAtElapsedMs > 0L) {
                    fallbackStates[tag] = state.copy(
                        nextRecoveryAtElapsedMs = state.nextRecoveryAtElapsedMs +
                            (group.recoveryIntervalSeconds - priorGroup.recoveryIntervalSeconds) * 1_000L,
                    )
                    startRecoveryLoop(tag)
                }
            }
        }
        manualMembers.entries.removeAll { (tag, member) ->
            updated.groupByTag(tag)?.effectiveMembers()?.contains(member) != true
        }
        runtimeScope?.let { scope ->
            restartUrlTestLoops(scope)
            scope.launch(Dispatchers.IO) { syncAllBalancersAndPublish() }
        }
    }

    fun stop() {
        expiryJob?.cancel()
        expiryJob = null
        urlTestJobs.values.forEach { it.cancel() }
        urlTestJobs.clear()
        recoveryJobs.values.forEach { it.cancel() }
        recoveryJobs.clear()
        runtimeScope = null
    }

    fun onCandidateFailed(groupTag: String, leaf: String) {
        val now = SystemClock.elapsedRealtime()
        val primaries = reachableFallbackGroups(groupTag).associateWith { group ->
            group.effectiveMembers().firstOrNull()?.let { resolvePreferredLeaf(it, mutableSetOf(group.tag)) }
        }
        probeResults.entries.forEach { (key, sample) ->
            if (key.first == leaf) probeResults[key] = sample.copy(healthy = false, completedAtElapsedMs = now)
        }
        for ((group, primaryLeaf) in primaries) {
            synchronized(stateLock) {
                val current = fallbackStates[group.tag]
                if (leaf == primaryLeaf && current?.inFallback != true) {
                    fallbackStates[group.tag] = FallbackRuntimeInfo(inFallback = true)
                    logEvent("[Warning] Group '${group.tag}': primary '$leaf' failed; selecting a backup.")
                } else if (current?.activeLeaf == leaf) {
                    fallbackStates[group.tag] = current.copy(activeLeaf = null)
                }
            }
        }
        publishSnapshot()
    }

    /** A real upstream response starts the recovery countdown. */
    fun onCandidateConnected(groupTag: String, leaf: String) {
        for (group in reachableFallbackGroups(groupTag)) {
            val primary = group.effectiveMembers().firstOrNull()
                ?.let { resolvePreferredLeaf(it, mutableSetOf(group.tag)) }
            val backups = group.effectiveMembers().drop(1)
                .flatMap { collectMemberLeaves(it, mutableSetOf(group.tag)) }
            if (leaf !in backups) continue
            if (primary == leaf || (fallbackStates[group.tag]?.inFallback != true && primary != null)) continue
            val startRecovery = synchronized(stateLock) {
                val current = fallbackStates[group.tag]
                val firstBackupResponse = current?.nextRecoveryAtElapsedMs?.let { it > 0L } != true
                val nextRecovery = if (firstBackupResponse) {
                    SystemClock.elapsedRealtime() + group.recoveryIntervalSeconds * 1_000L
                } else current!!.nextRecoveryAtElapsedMs
                fallbackStates[group.tag] = FallbackRuntimeInfo(true, nextRecovery, leaf)
                firstBackupResponse
            }
            if (startRecovery) {
                logEvent("[Info] Group '${group.tag}': backup '$leaf' connected; primary check in ${group.recoveryIntervalSeconds}s.")
                startRecoveryLoop(group.tag)
            }
        }
        publishSnapshot()
    }

    private fun startRecoveryLoop(groupTag: String) {
        val scope = runtimeScope ?: return
        recoveryJobs.remove(groupTag)?.cancel()
        recoveryJobs[groupTag] = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val state = fallbackStates[groupTag] ?: break
                delay((state.nextRecoveryAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                val group = workspace.groupByTag(groupTag) ?: break
                val primary = group.effectiveMembers().firstOrNull() ?: break
                val leaf = resolvePreferredLeaf(primary, mutableSetOf(groupTag))
                val rtt = when {
                    leaf == null -> null
                    leaf in BUILTIN_GROUP_TARGET_TAGS -> 0L
                    else -> {
                        logEvent("[Info] Group '$groupTag': checking primary '$leaf'.")
                        probeNode(leaf, group.testUrl).also { recordMeasurement(leaf, group.testUrl, it) }
                    }
                }
                if (rtt != null) {
                    logEvent("[Info] Group '$groupTag': primary '$leaf' recovered; new connections use primary.")
                    synchronized(stateLock) { fallbackStates.remove(groupTag) }
                    manualMembers.remove(groupTag)
                    syncAllBalancersAndPublish()
                    break
                }
                synchronized(stateLock) {
                    fallbackStates[groupTag]?.let { current ->
                        fallbackStates[groupTag] = current.copy(
                            nextRecoveryAtElapsedMs = SystemClock.elapsedRealtime() + group.recoveryIntervalSeconds * 1_000L,
                        )
                    }
                }
                logEvent("[Info] Group '$groupTag': primary check failed; next check in ${group.recoveryIntervalSeconds}s.")
                publishSnapshot()
            }
        }
    }

    fun forceSwitchMember(groupTag: String, member: GroupMemberRef) {
        val group = workspace.groupByTag(groupTag) ?: return
        if (member !in group.effectiveMembers()) return
        if (group.strategy == GroupStrategy.FALLBACK && member == group.effectiveMembers().firstOrNull()) {
            clearFallback(groupTag)
        } else {
            manualMembers[groupTag] = member
            if (group.strategy == GroupStrategy.FALLBACK) {
                synchronized(stateLock) {
                    val previous = fallbackStates[groupTag]
                    fallbackStates[groupTag] = FallbackRuntimeInfo(
                        inFallback = true,
                        nextRecoveryAtElapsedMs = previous?.nextRecoveryAtElapsedMs ?: 0L,
                    )
                }
            }
        }
        runtimeScope?.launch(Dispatchers.IO) { syncAllBalancersAndPublish() }
    }

    fun buildFailoverPlan(groupTag: String): FailoverPlan {
        val group = workspace.groupByTag(groupTag)
            ?: return FailoverPlan(null, emptyList(), 3_000)
        if (group.strategy == GroupStrategy.URL_TEST) {
            return FailoverPlan(null, rankUrlTestLeaves(group, mutableSetOf(groupTag)), group.failoverTimeoutSeconds * 1_000)
        }
        val members = group.effectiveMembers()
        val primary = members.firstOrNull()
            ?.let { expandMemberLeaves(it, mutableSetOf(groupTag)) }.orEmpty()
        val backups = orderedBackups(group).flatMap { expandMemberLeaves(it, mutableSetOf(groupTag)) }
        val candidates = if (fallbackStates[groupTag]?.inFallback == true) backups else primary + backups
        return FailoverPlan(
            primaryLeaf = primary.firstOrNull(),
            candidates = candidates.distinct(),
            failoverTimeoutMs = group.failoverTimeoutSeconds * 1_000,
        )
    }

    fun activeLeaf(member: GroupMemberRef): String? = resolvePreferredLeaf(member, mutableSetOf())

    fun recordMeasurement(node: String, url: String, latencyMs: Long?) {
        probeResults[node to url] = ProbeResult(
            healthy = latencyMs != null,
            latencyMs = latencyMs ?: -1L,
            completedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        publishSnapshot()
    }

    private fun clearFallback(groupTag: String) {
        synchronized(stateLock) { fallbackStates.remove(groupTag) }
        manualMembers.remove(groupTag)
        recoveryJobs.remove(groupTag)?.cancel()
        runtimeScope?.launch(Dispatchers.IO) { syncAllBalancersAndPublish() }
    }

    private fun restartUrlTestLoops(scope: CoroutineScope) {
        val groups = workspace.groups.filter { it.strategy == GroupStrategy.URL_TEST }
        val tags = groups.map { it.tag }.toSet()
        for (tag in urlTestJobs.keys - tags) urlTestJobs.remove(tag)?.cancel()
        for (group in groups) {
            if (urlTestJobs.containsKey(group.tag)) continue
            urlTestJobs[group.tag] = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val latest = workspace.groupByTag(group.tag) ?: break
                    if (latest.strategy != GroupStrategy.URL_TEST) break
                    val protectedPrimaries = workspace.groups
                        .filter { it.strategy == GroupStrategy.FALLBACK && it.effectiveMembers().size > 1 }
                        .mapNotNull { it.effectiveMembers().firstOrNull()?.let { ref -> scheduledPrimaryLeaf(ref, mutableSetOf(it.tag)) } }
                        .toSet()
                    val leaves = latest.effectiveMembers()
                        .flatMap { collectMemberLeaves(it, mutableSetOf(latest.tag)) }
                        .distinct()
                        .filter { it !in protectedPrimaries && it !in BUILTIN_GROUP_TARGET_TAGS }
                    coroutineScope {
                        leaves.map { leaf ->
                            async(Dispatchers.IO) {
                                recordMeasurement(leaf, latest.testUrl, probeNode(leaf, latest.testUrl))
                            }
                        }.awaitAll()
                    }
                    manualMembers.remove(group.tag)
                    syncAllBalancersAndPublish()
                    delay(latest.intervalSeconds * 1_000L)
                }
            }
        }
    }

    private val appliedTargets = mutableMapOf<String, String>()

    private suspend fun syncAllBalancersAndPublish() = syncLock.withLock {
        for (group in workspace.groups) {
            if (group.tag !in balancerTags) continue
            val target = resolveDirectRoutingTarget(group, mutableSetOf()) ?: continue
            if (appliedTargets[group.tag] == target) continue
            runCatching { applyBalancerTarget(group.tag, target) }
                .onSuccess { appliedTargets[group.tag] = target }
                .onFailure { logEvent("[Warning] Group '${group.tag}': applying selection deferred (${it.javaClass.simpleName}).") }
        }
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val active = workspace.groups.mapNotNull { group ->
            resolveActiveLeaf(group, mutableSetOf())?.let { group.tag to it }
        }.toMap()
        val latencies = probeResults.entries.groupBy { it.key.first }.mapNotNull { (node, entries) ->
            val latest = entries.maxByOrNull { it.value.completedAtElapsedMs }?.value
            latest?.takeIf { it.healthy }?.let { node to it.latencyMs }
        }.toMap()
        publishStatus(active, HashMap(fallbackStates), latencies)
    }

    private fun resolveDirectRoutingTarget(group: ProxyGroup, visited: MutableSet<String>): String? {
        if (visited.add(group.tag) == false) return null
        fallbackRelayOutbounds[group.tag]?.let { return it }
        val members = group.effectiveMembers()
        val chosen = when (group.strategy) {
            GroupStrategy.SELECT -> manualMembers[group.tag] ?: group.selectedMember ?: members.firstOrNull()
            GroupStrategy.URL_TEST -> return rankUrlTestLeaves(group, visited).firstOrNull()
            GroupStrategy.FALLBACK -> members.firstOrNull()
        } ?: return null
        return if (chosen.isGroup) {
            workspace.groupByTag(chosen.tag)?.let { resolveDirectRoutingTarget(it, visited) }
        } else chosen.tag
    }

    private fun resolveActiveLeaf(group: ProxyGroup, visited: MutableSet<String>): String? {
        if (visited.add(group.tag) == false) return null
        val members = group.effectiveMembers()
        return when (group.strategy) {
            GroupStrategy.SELECT -> (manualMembers[group.tag] ?: group.selectedMember ?: members.firstOrNull())
                ?.let { resolvePreferredLeaf(it, visited) }
            GroupStrategy.URL_TEST -> manualMembers[group.tag]?.let { resolvePreferredLeaf(it, visited) }
                ?: rankUrlTestLeaves(group, visited).firstOrNull()
            GroupStrategy.FALLBACK -> {
                val state = fallbackStates[group.tag]
                if (state?.inFallback == true) state.activeLeaf
                else members.firstOrNull()?.let { resolvePreferredLeaf(it, visited) }
            }
        }
    }

    private fun resolvePreferredLeaf(member: GroupMemberRef, visited: MutableSet<String>): String? =
        if (member.isGroup) workspace.groupByTag(member.tag)?.let { resolveActiveLeaf(it, visited) }
        else member.tag

    private fun orderedBackups(group: ProxyGroup): List<GroupMemberRef> {
        val backups = group.effectiveMembers().drop(1)
        val selected = manualMembers[group.tag]?.takeIf { it in backups }
        return if (selected == null) backups else listOf(selected) + backups.filter { it != selected }
    }

    private fun expandMemberLeaves(member: GroupMemberRef, visited: MutableSet<String>): List<String> {
        if (member.isGroup == false) return listOf(member.tag)
        val child = workspace.groupByTag(member.tag) ?: return emptyList()
        if (visited.add(child.tag) == false) return emptyList()
        return when (child.strategy) {
            GroupStrategy.URL_TEST -> rankUrlTestLeaves(child, visited)
            GroupStrategy.SELECT -> (manualMembers[child.tag] ?: child.selectedMember ?: child.effectiveMembers().firstOrNull())
                ?.let { expandMemberLeaves(it, visited) }.orEmpty()
            GroupStrategy.FALLBACK -> {
                val primary = child.effectiveMembers().firstOrNull()
                    ?.let { expandMemberLeaves(it, visited.toMutableSet()) }.orEmpty()
                val backups = orderedBackups(child).flatMap { expandMemberLeaves(it, visited.toMutableSet()) }
                if (fallbackStates[child.tag]?.inFallback == true) backups.distinct()
                else (primary + backups).distinct()
            }
        }
    }

    private fun rankUrlTestLeaves(group: ProxyGroup, visited: MutableSet<String>): List<String> {
        val now = SystemClock.elapsedRealtime()
        val leaves = group.effectiveMembers().flatMap { expandMemberLeaves(it, visited.toMutableSet()) }.distinct()
        val ranked = leaves.mapNotNull { leaf ->
            if (leaf in BUILTIN_GROUP_TARGET_TAGS) leaf to 0L
            else probeResults[leaf to group.testUrl]?.takeIf {
                it.healthy && now - it.completedAtElapsedMs <= group.trustSeconds * 1_000L
            }?.let { leaf to it.latencyMs }
        }.sortedBy { it.second }.map { it.first }
        val selected = manualMembers[group.tag]?.takeIf { it.isGroup == false }?.tag
        return if (selected in ranked) listOf(selected!!) + ranked.filter { it != selected } else ranked
    }

    private fun collectMemberLeaves(member: GroupMemberRef, visited: MutableSet<String>): List<String> {
        if (member.isGroup == false) return listOf(member.tag)
        if (visited.add(member.tag) == false) return emptyList()
        return workspace.groupByTag(member.tag)?.effectiveMembers()
            ?.flatMap { collectMemberLeaves(it, visited.toMutableSet()) }.orEmpty()
    }

    private fun scheduledPrimaryLeaf(member: GroupMemberRef, visited: MutableSet<String>): String? {
        if (member.isGroup == false) return member.tag
        if (visited.add(member.tag) == false) return null
        val group = workspace.groupByTag(member.tag) ?: return null
        if (group.strategy == GroupStrategy.URL_TEST) return null
        val chosen = if (group.strategy == GroupStrategy.SELECT) {
            manualMembers[group.tag] ?: group.selectedMember ?: group.effectiveMembers().firstOrNull()
        } else group.effectiveMembers().firstOrNull()
        return chosen?.let { scheduledPrimaryLeaf(it, visited) }
    }

    private fun reachableFallbackGroups(groupTag: String): List<ProxyGroup> {
        val result = mutableListOf<ProxyGroup>()
        val visited = mutableSetOf<String>()
        fun visit(tag: String) {
            if (visited.add(tag) == false) return
            val group = workspace.groupByTag(tag) ?: return
            if (group.strategy == GroupStrategy.FALLBACK) result += group
            group.effectiveMembers().filter { it.isGroup }.forEach { visit(it.tag) }
        }
        visit(groupTag)
        return result
    }
}
