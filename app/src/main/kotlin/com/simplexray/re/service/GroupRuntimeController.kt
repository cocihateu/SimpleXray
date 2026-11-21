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
import kotlinx.coroutines.withTimeoutOrNull
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
    private val probeNode: suspend (nodeTag: String, testUrl: String, timeoutMs: Int) -> Long?,
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

    suspend fun start(scope: CoroutineScope) {
        runtimeScope = scope
        coroutineScope {
            workspace.groups.filter { it.strategy == GroupStrategy.URL_TEST }.map { group ->
                async(Dispatchers.IO) { measureUrlTestGroup(group) }
            }.awaitAll()
        }
        restartUrlTestLoops(scope, waitBeforeFirstCheck = true)
        fallbackStates.filterValues { it.nextRecoveryAtElapsedMs > 0L }.keys.forEach(::startRecoveryLoop)
        expiryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                syncAllBalancersAndPublish()
                delay(1_000)
            }
        }
    }

    fun updateWorkspace(updated: GroupWorkspace, startHealthChecks: Boolean = true) {
        synchronized(stateLock) {
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
        }
        runtimeScope?.let { scope ->
            if (startHealthChecks) restartUrlTestLoops(scope)
            scope.launch(Dispatchers.IO) { syncAllBalancersAndPublish() }
        }
    }

    fun stop() {
        expiryJob?.cancel()
        expiryJob = null
        urlTestJobs.values.forEach { it.cancel() }
        urlTestJobs.clear()
        synchronized(stateLock) {
            recoveryJobs.values.forEach { it.cancel() }
            recoveryJobs.clear()
            runtimeScope = null
        }
    }

    fun onCandidateFailed(groupTag: String, leaf: String) {
        synchronized(stateLock) { onCandidateFailedLocked(groupTag, leaf) }
        publishSnapshot()
    }

    private fun onCandidateFailedLocked(groupTag: String, leaf: String) {
        val now = SystemClock.elapsedRealtime()
        val groups = fallbackGroupsForLeaf(groupTag, leaf)
        probeResults.entries.forEach { (key, sample) ->
            if (key.first == leaf) probeResults[key] = sample.copy(healthy = false, completedAtElapsedMs = now)
        }
        groups.forEach { group ->
            probeResults[leaf to group.testUrl] = ProbeResult(false, -1L, now)
        }
        for (group in groups.asReversed()) {
            val primaryMember = group.effectiveMembers().firstOrNull() ?: continue
            val primaryLeaves = expandMemberLeaves(primaryMember, mutableSetOf(group.tag))
            val primaryFailed = if (primaryMember.isGroup) {
                primaryLeaves.isEmpty() || primaryLeaves.all { it == leaf || probeResults[it to group.testUrl]?.healthy == false }
            } else {
                primaryMember.tag == leaf
            }
            val current = fallbackStates[group.tag]
            if (primaryFailed && current?.inFallback != true) {
                fallbackStates[group.tag] = FallbackRuntimeInfo(inFallback = true)
                logEvent("[Warning] Group '${group.tag}': primary failed ($leaf); selecting a backup.")
            } else if (current?.activeLeaf == leaf) {
                fallbackStates[group.tag] = current.copy(activeLeaf = null)
            }
        }
    }

    /** Confirms active-traffic silence through the selected leaf's real AsIs HTTP entry. */
    suspend fun onStalledUpload(groupTag: String, leaf: String, applyIfWaiting: (() -> Unit) -> Boolean) {
        val check = synchronized(stateLock) {
            val plan = buildFailoverPlanLocked(groupTag)
            val path = groupPathForLeaf(groupTag, leaf, mutableSetOf()).orEmpty()
            val group = path.firstOrNull {
                it.strategy == GroupStrategy.URL_TEST ||
                    (it.strategy == GroupStrategy.FALLBACK && it.effectiveMembers().size > 1)
            } ?: workspace.groupByTag(groupTag) ?: return@synchronized null
            Triple(
                plan,
                path.map { Triple(it, manualMembers[it.tag], fallbackStates[it.tag]) },
                group to probeResults[leaf to group.testUrl],
            )
        } ?: return
        val (plan, selectedAtStart, measurementAtStart) = check
        val (group, sampleAtStart) = measurementAtStart
        val startedAt = SystemClock.elapsedRealtime()
        val latency = withTimeoutOrNull(plan.failoverTimeoutMs.toLong()) {
            probeNode(leaf, group.testUrl, plan.failoverTimeoutMs)
        }
        synchronized(stateLock) {
            val currentPath = groupPathForLeaf(groupTag, leaf, mutableSetOf()).orEmpty()
                .map { Triple(it, manualMembers[it.tag], fallbackStates[it.tag]) }
            val sample = probeResults[leaf to group.testUrl]
            val currentSelection = currentPath == selectedAtStart && buildFailoverPlanLocked(groupTag) == plan &&
                plan.candidates.firstOrNull() == leaf && sample === sampleAtStart
            if (latency != null) {
                if (currentSelection) recordMeasurementLocked(leaf, group.testUrl, latency, startedAt)
            } else {
                if (sample?.healthy == true && sample.completedAtElapsedMs >= startedAt) return@synchronized
                applyIfWaiting {
                    logEvent("[Warning] Group '$groupTag': upload response timed out; AsIs HTTP health check failed for '$leaf'; closing stalled TCP stream.")
                    if (currentSelection) onCandidateFailedLocked(groupTag, leaf)
                }
            }
        }
        publishSnapshot()
    }

    /** A real upstream response starts the recovery countdown. */
    fun onCandidateConnected(groupTag: String, leaf: String) {
        synchronized(stateLock) {
            for (group in fallbackGroupsForLeaf(groupTag, leaf)) {
                val primary = group.effectiveMembers().firstOrNull()
                    ?.let { resolvePreferredLeaf(it, mutableSetOf(group.tag)) }
                val backups = group.effectiveMembers().drop(1)
                if (backups.none { memberContainsLeaf(it, leaf, mutableSetOf(group.tag)) }) continue
                val current = fallbackStates[group.tag]
                if (primary == leaf || (current?.inFallback != true && primary != null)) continue
                val selected = manualMembers[group.tag]?.takeIf { it in backups }
                if (selected != null && memberContainsLeaf(selected, leaf, mutableSetOf(group.tag)) == false) continue
                if (current?.activeLeaf != null && current.activeLeaf != leaf) continue
                val firstBackupResponse = current?.nextRecoveryAtElapsedMs?.let { it > 0L } != true
                val nextRecovery = if (firstBackupResponse) {
                    SystemClock.elapsedRealtime() + group.recoveryIntervalSeconds * 1_000L
                } else current!!.nextRecoveryAtElapsedMs
                fallbackStates[group.tag] = FallbackRuntimeInfo(true, nextRecovery, leaf)
                if (firstBackupResponse) {
                    logEvent("[Info] Group '${group.tag}': backup '$leaf' connected; primary check in ${group.recoveryIntervalSeconds}s.")
                    startRecoveryLoop(group.tag)
                }
            }
        }
        publishSnapshot()
    }

    private fun startRecoveryLoop(groupTag: String) {
        synchronized(stateLock) {
            val scope = runtimeScope ?: return
            val initialState = fallbackStates[groupTag] ?: return
            if (initialState.inFallback == false || initialState.nextRecoveryAtElapsedMs <= 0L) return
            recoveryJobs.remove(groupTag)?.cancel()
            recoveryJobs[groupTag] = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val state = synchronized(stateLock) { fallbackStates[groupTag] } ?: break
                    delay((state.nextRecoveryAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                    val group = workspace.groupByTag(groupTag) ?: break
                    val primary = group.effectiveMembers().firstOrNull() ?: break
                    val leaf = scheduledPrimaryLeaf(primary, mutableSetOf(groupTag))
                        ?: resolvePreferredLeaf(primary, mutableSetOf(groupTag))
                    val checkStartedAtElapsedMs = SystemClock.elapsedRealtime()
                    val sampleAtStart = synchronized(stateLock) {
                        leaf?.let { probeResults[it to group.testUrl] }
                    }
                    val rtt = when {
                        leaf == null -> null
                        leaf in BUILTIN_GROUP_TARGET_TAGS -> 0L
                        else -> {
                            logEvent("[Info] Group '$groupTag': checking primary '$leaf'.")
                            probeNode(leaf, group.testUrl, 5_000)
                        }
                    }
                    val recovered = synchronized(stateLock) {
                        val current = fallbackStates[groupTag] ?: return@synchronized null
                        if (isActive == false) return@synchronized null
                        val latestGroup = workspace.groupByTag(groupTag) ?: return@synchronized null
                        val latestPrimary = latestGroup.effectiveMembers().firstOrNull()
                        val latestLeaf = latestPrimary?.let {
                            scheduledPrimaryLeaf(it, mutableSetOf(groupTag))
                                ?: resolvePreferredLeaf(it, mutableSetOf(groupTag))
                        }
                        if (current.nextRecoveryAtElapsedMs != state.nextRecoveryAtElapsedMs ||
                            latestLeaf != leaf || latestGroup.testUrl != group.testUrl
                        ) return@synchronized null
                        val sampleChanged = leaf?.let { probeResults[it to group.testUrl] } !== sampleAtStart
                        if (leaf != null && leaf !in BUILTIN_GROUP_TARGET_TAGS && sampleChanged == false) {
                            recordMeasurementLocked(leaf, group.testUrl, rtt, checkStartedAtElapsedMs)
                        }
                        val newerFailure = leaf?.let { probeResults[it to group.testUrl] }?.let {
                            it.healthy == false && it.completedAtElapsedMs >= checkStartedAtElapsedMs
                        } == true
                        if (leaf != null && rtt != null && newerFailure == false && sampleChanged == false) {
                            fallbackGroupsForLeaf(groupTag, leaf, primaryOnly = true).filter { candidate ->
                                val candidateFailure = probeResults[leaf to candidate.testUrl]
                                (candidate.tag == groupTag || candidate.effectiveMembers().firstOrNull()?.let {
                                    scheduledPrimaryLeaf(it, mutableSetOf(candidate.tag))
                                } == leaf) && (candidateFailure == null || candidateFailure.healthy ||
                                    candidateFailure.completedAtElapsedMs < checkStartedAtElapsedMs)
                            }.forEach { restored ->
                                fallbackStates.remove(restored.tag)
                                manualMembers.remove(restored.tag)
                                if (restored.tag != groupTag) recoveryJobs.remove(restored.tag)?.cancel()
                            }
                            true
                        } else {
                            fallbackStates[groupTag] = current.copy(
                                nextRecoveryAtElapsedMs = SystemClock.elapsedRealtime() + latestGroup.recoveryIntervalSeconds * 1_000L,
                            )
                            false
                        }
                    } ?: continue
                    if (recovered) {
                        logEvent("[Info] Group '$groupTag': primary '$leaf' recovered; new connections use primary.")
                        syncAllBalancersAndPublish()
                        break
                    }
                    logEvent("[Info] Group '$groupTag': primary check failed; next check in ${group.recoveryIntervalSeconds}s.")
                    publishSnapshot()
                }
            }
        }
    }

    fun forceSwitchMember(groupTag: String, member: GroupMemberRef) {
        synchronized(stateLock) {
            val group = workspace.groupByTag(groupTag) ?: return
            if (member !in group.effectiveMembers()) return
            if (group.strategy == GroupStrategy.FALLBACK && member == group.effectiveMembers().firstOrNull()) {
                clearFallback(groupTag)
            } else {
                manualMembers[groupTag] = member
                if (group.strategy == GroupStrategy.FALLBACK) {
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

    fun clearSelection(groupTag: String) {
        synchronized(stateLock) { manualMembers.remove(groupTag) }
        runtimeScope?.launch(Dispatchers.IO) { syncAllBalancersAndPublish() }
    }

    fun buildFailoverPlan(groupTag: String): FailoverPlan =
        synchronized(stateLock) { buildFailoverPlanLocked(groupTag) }

    private fun buildFailoverPlanLocked(groupTag: String): FailoverPlan {
        val physicalNode = when {
            groupTag.startsWith(OutboundGroupConfig.PHYSICAL_PREFIX) ->
                groupTag.removePrefix(OutboundGroupConfig.PHYSICAL_PREFIX)
            workspace.groupByTag(groupTag) == null &&
                (groupTag in BUILTIN_GROUP_TARGET_TAGS || workspace.allNodes().any { it.tag == groupTag }) ->
                groupTag
            else -> null
        }
        if (physicalNode != null) {
            return FailoverPlan(physicalNode, listOf(physicalNode), 10_000, watchResponse = false)
        }
        val group = workspace.groupByTag(groupTag)
            ?: return FailoverPlan(null, emptyList(), 3_000)
        if (group.strategy == GroupStrategy.SELECT) {
            val leaf = resolveActiveLeaf(group, mutableSetOf())
            val chosen = manualMembers[groupTag] ?: group.selectedMember ?: group.effectiveMembers().firstOrNull()
            if (chosen?.isGroup == true) return buildFailoverPlanLocked(chosen.tag)
            return FailoverPlan(leaf, listOfNotNull(leaf), group.failoverTimeoutSeconds * 1_000, watchResponse = false)
        }
        if (group.strategy == GroupStrategy.URL_TEST) {
            return FailoverPlan(null, rankUrlTestLeaves(group, mutableSetOf(groupTag)), group.failoverTimeoutSeconds * 1_000)
        }
        val members = group.effectiveMembers()
        if (members.size == 1) {
            val only = members.first()
            return if (only.isGroup) buildFailoverPlanLocked(only.tag)
                else FailoverPlan(only.tag, listOf(only.tag), group.failoverTimeoutSeconds * 1_000, watchResponse = false)
        }
        val primary = members.firstOrNull()
            ?.let { expandMemberLeaves(it, mutableSetOf(groupTag)) }.orEmpty()
        val backupLeaves = orderedBackups(group).flatMap { expandMemberLeaves(it, mutableSetOf(groupTag)) }
        val connected = fallbackStates[groupTag]?.activeLeaf?.takeIf { it in backupLeaves }
        val backups = if (connected == null) backupLeaves else listOf(connected) + backupLeaves.filter { it != connected }
        val candidates = if (fallbackStates[groupTag]?.inFallback == true) backups else primary + backups
        return FailoverPlan(
            primaryLeaf = primary.firstOrNull(),
            candidates = candidates.distinct(),
            failoverTimeoutMs = group.failoverTimeoutSeconds * 1_000,
        )
    }

    fun activeLeaf(member: GroupMemberRef): String? = resolvePreferredLeaf(member, mutableSetOf())

    fun recordMeasurement(
        node: String,
        url: String,
        latencyMs: Long?,
        startedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    ) {
        synchronized(stateLock) { recordMeasurementLocked(node, url, latencyMs, startedAtElapsedMs) }
        publishSnapshot()
    }

    private fun recordMeasurementLocked(node: String, url: String, latencyMs: Long?, startedAtElapsedMs: Long) {
        val previous = probeResults[node to url]
        if (latencyMs != null && previous?.healthy == false && previous.completedAtElapsedMs >= startedAtElapsedMs) return
        probeResults[node to url] = ProbeResult(
            healthy = latencyMs != null,
            latencyMs = latencyMs ?: -1L,
            completedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun clearFallback(groupTag: String) {
        synchronized(stateLock) {
            fallbackStates.remove(groupTag)
            manualMembers.remove(groupTag)
            recoveryJobs.remove(groupTag)?.cancel()
        }
        runtimeScope?.launch(Dispatchers.IO) { syncAllBalancersAndPublish() }
    }

    private fun urlTestLeaves(group: ProxyGroup): List<String> {
        val protectedPrimaries = workspace.groups
            .filter { it.strategy == GroupStrategy.FALLBACK && it.effectiveMembers().size > 1 }
            .mapNotNull { parent ->
                parent.effectiveMembers().firstOrNull()?.let { ref -> scheduledPrimaryLeaf(ref, mutableSetOf(parent.tag)) }
            }.toSet()
        return group.effectiveMembers()
            .flatMap { collectMemberLeaves(it, mutableSetOf(group.tag)) }
            .distinct()
            .filter { it !in protectedPrimaries && it !in BUILTIN_GROUP_TARGET_TAGS }
    }

    private suspend fun measureUrlTestGroup(group: ProxyGroup) {
        coroutineScope {
            urlTestLeaves(group).map { leaf ->
                async(Dispatchers.IO) {
                    val startedAtElapsedMs = SystemClock.elapsedRealtime()
                    recordMeasurement(leaf, group.testUrl, probeNode(leaf, group.testUrl, 5_000), startedAtElapsedMs)
                }
            }.awaitAll()
        }
        synchronized(stateLock) { manualMembers.remove(group.tag) }
        syncAllBalancersAndPublish()
    }

    private fun nextUrlTestDelay(group: ProxyGroup): Long {
        val now = SystemClock.elapsedRealtime()
        val earliestExpiry = urlTestLeaves(group).mapNotNull { leaf ->
            probeResults[leaf to group.testUrl]?.takeIf { it.healthy }
                ?.let { it.completedAtElapsedMs + group.trustSeconds * 1_000L }
        }.minOrNull()
        val intervalDue = now + group.intervalSeconds * 1_000L
        val refreshLead = minOf(5_000L, group.trustSeconds * 500L)
        val nextCheck = earliestExpiry?.let { minOf(intervalDue, it - refreshLead) } ?: intervalDue
        return (nextCheck - now).coerceAtLeast(1_000L)
    }

    private fun restartUrlTestLoops(scope: CoroutineScope, waitBeforeFirstCheck: Boolean = false) {
        val groups = workspace.groups.filter { it.strategy == GroupStrategy.URL_TEST }
        val tags = groups.map { it.tag }.toSet()
        for (tag in urlTestJobs.keys - tags) urlTestJobs.remove(tag)?.cancel()
        for (group in groups) {
            if (urlTestJobs.containsKey(group.tag)) continue
            urlTestJobs[group.tag] = scope.launch(Dispatchers.IO) {
                if (waitBeforeFirstCheck) delay(nextUrlTestDelay(group))
                while (isActive) {
                    val latest = workspace.groupByTag(group.tag) ?: break
                    if (latest.strategy != GroupStrategy.URL_TEST) break
                    measureUrlTestGroup(latest)
                    delay(nextUrlTestDelay(latest))
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

    private fun publishSnapshot() = synchronized(stateLock) {
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
                if (state?.inFallback == true) {
                    state.activeLeaf ?: orderedBackups(group).firstNotNullOfOrNull {
                        resolvePreferredLeaf(it, visited.toMutableSet())
                    }
                }
                else members.firstOrNull()?.let { resolvePreferredLeaf(it, visited) }
            }
        }
    }

    private fun resolvePreferredLeaf(member: GroupMemberRef, visited: MutableSet<String>): String? =
        if (member.isGroup) workspace.groupByTag(member.tag)?.let { resolveActiveLeaf(it, visited) }
        else member.tag

    private fun orderedBackups(group: ProxyGroup): List<GroupMemberRef> {
        val backups = group.effectiveMembers().drop(1)
        val connected = fallbackStates[group.tag]?.activeLeaf
        val selected = manualMembers[group.tag]?.takeIf { it in backups }
            ?: backups.firstOrNull { connected != null && connected in collectMemberLeaves(it, mutableSetOf(group.tag)) }
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
        val pending = leaves.filter { it !in BUILTIN_GROUP_TARGET_TAGS && probeResults[it to group.testUrl] == null }
        val candidates = ranked + pending
        val selected = manualMembers[group.tag]?.takeIf { it.isGroup == false }?.tag
        return if (selected in candidates) listOf(selected!!) + candidates.filter { it != selected } else candidates
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

    private fun fallbackGroupsForLeaf(groupTag: String, leaf: String, primaryOnly: Boolean = false): List<ProxyGroup> =
        groupPathForLeaf(groupTag, leaf, mutableSetOf(), primaryOnly).orEmpty()
            .filter { it.strategy == GroupStrategy.FALLBACK }

    private fun memberContainsLeaf(member: GroupMemberRef, leaf: String, visited: MutableSet<String>): Boolean =
        if (member.isGroup) groupPathForLeaf(member.tag, leaf, visited) != null else member.tag == leaf

    private fun groupPathForLeaf(
        groupTag: String,
        leaf: String,
        visited: MutableSet<String>,
        primaryOnly: Boolean = false,
    ): List<ProxyGroup>? {
        if (visited.add(groupTag) == false) return null
        val group = workspace.groupByTag(groupTag) ?: return null
        val members = group.effectiveMembers()
        val candidates = when (group.strategy) {
            GroupStrategy.SELECT -> listOfNotNull(manualMembers[groupTag] ?: group.selectedMember ?: members.firstOrNull())
            GroupStrategy.URL_TEST -> members
            GroupStrategy.FALLBACK -> when {
                primaryOnly -> members.take(1)
                fallbackStates[groupTag]?.inFallback == true -> orderedBackups(group) + members.take(1)
                else -> members
            }
        }
        for (member in candidates) {
            val branch = if (member.isGroup) {
                groupPathForLeaf(member.tag, leaf, visited.toMutableSet(), primaryOnly)
            } else if (member.tag == leaf) emptyList() else null
            if (branch != null) return listOf(group) + branch
        }
        return null
    }
}
