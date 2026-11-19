package com.simplexray.re.service

import android.os.SystemClock
import android.util.Log
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
import java.util.concurrent.ConcurrentHashMap

data class FallbackRuntimeInfo(
    val inFallback: Boolean = false,
    val nextRecoveryAtElapsedMs: Long = 0L,
)

internal data class ProbeResult(
    val healthy: Boolean,
    val latencyMs: Long,
    val completedAtElapsedMs: Long,
)

/**
 * Manages live URL_TEST periodic probing, FALLBACK primary recovery countdowns,
 * and recursive group resolution across nested groups.
 *
 * While a FALLBACK group runs on its primary target, zero periodic health checks touch that
 * primary. Only after a real connection failure flips the group to fallback does the recovery
 * countdown begin; when the timer expires, one probe checks primary and switches back on recovery.
 */
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
) {
    @Volatile
    private var workspace: GroupWorkspace = initialWorkspace
    private val probeResults = ConcurrentHashMap<String, ProbeResult>()
    private val fallbackStates = ConcurrentHashMap<String, FallbackRuntimeInfo>()
    private val recoveryJobs = ConcurrentHashMap<String, Job>()
    private val urlTestJobs = ConcurrentHashMap<String, Job>()
    private val stateLock = Any()
    private var runtimeScope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        runtimeScope = scope
        restartUrlTestLoops(scope)
        scope.launch(Dispatchers.IO) {
            syncAllBalancersAndPublish()
        }
    }

    fun updateWorkspace(updated: GroupWorkspace) {
        workspace = updated
        val validGroups = updated.groups.map { it.tag }.toSet()
        for (tag in recoveryJobs.keys - validGroups) {
            recoveryJobs.remove(tag)?.cancel()
            fallbackStates.remove(tag)
        }
        runtimeScope?.let { scope ->
            restartUrlTestLoops(scope)
            scope.launch(Dispatchers.IO) {
                syncAllBalancersAndPublish()
            }
        }
    }

    fun stop() {
        urlTestJobs.values.forEach { it.cancel() }
        urlTestJobs.clear()
        recoveryJobs.values.forEach { it.cancel() }
        recoveryJobs.clear()
        runtimeScope = null
    }

    fun onPrimaryFailed(groupTag: String, failedLeaf: String) {
        val group = workspace.groupByTag(groupTag) ?: return
        if (group.strategy != GroupStrategy.FALLBACK) return
        val recoveryMs = group.recoveryIntervalSeconds.coerceAtLeast(5) * 1_000L
        val nextCheckAt = SystemClock.elapsedRealtime() + recoveryMs

        val startedNow = synchronized(stateLock) {
            val existing = fallbackStates[groupTag]
            if (existing != null && existing.inFallback) {
                false
            } else {
                fallbackStates[groupTag] = FallbackRuntimeInfo(
                    inFallback = true,
                    nextRecoveryAtElapsedMs = nextCheckAt,
                )
                true
            }
        }
        if (startedNow == false) return

        Log.i(TAG, "Group '$groupTag' entered fallback after '$failedLeaf' failed; checking primary in ${group.recoveryIntervalSeconds}s.")
        val scope = runtimeScope ?: return
        recoveryJobs.remove(groupTag)?.cancel()
        recoveryJobs[groupTag] = scope.launch(Dispatchers.IO) {
            syncAllBalancersAndPublish()
            while (isActive) {
                val currentGroup = workspace.groupByTag(groupTag) ?: break
                val intervalMs = currentGroup.recoveryIntervalSeconds.coerceAtLeast(5) * 1_000L
                val targetAt = SystemClock.elapsedRealtime() + intervalMs
                fallbackStates[groupTag] = FallbackRuntimeInfo(
                    inFallback = true,
                    nextRecoveryAtElapsedMs = targetAt,
                )
                publishSnapshot()
                delay(intervalMs)

                val latestGroup = workspace.groupByTag(groupTag) ?: break
                val primaryMember = latestGroup.effectiveMembers().firstOrNull() ?: break
                val primaryLeaf = resolvePreferredLeaf(primaryMember, visited = mutableSetOf(groupTag))
                if (primaryLeaf == null || primaryLeaf in BUILTIN_GROUP_TARGET_TAGS) {
                    clearFallback(groupTag)
                    break
                }
                Log.i(TAG, "Running single recovery check on primary '$primaryLeaf' for group '$groupTag'.")
                val rtt = probeNode(primaryLeaf, latestGroup.testUrl)
                val now = SystemClock.elapsedRealtime()
                if (rtt != null) {
                    probeResults[primaryLeaf] = ProbeResult(healthy = true, latencyMs = rtt, completedAtElapsedMs = now)
                    Log.i(TAG, "Primary '$primaryLeaf' recovered (${rtt}ms); switching group '$groupTag' back to primary.")
                    clearFallback(groupTag)
                    break
                } else {
                    probeResults[primaryLeaf] = ProbeResult(healthy = false, latencyMs = -1L, completedAtElapsedMs = now)
                    Log.w(TAG, "Primary '$primaryLeaf' still failing for group '$groupTag'; retrying in ${latestGroup.recoveryIntervalSeconds}s.")
                }
            }
        }
    }

    fun forceSwitchMember(groupTag: String, member: GroupMemberRef) {
        val group = workspace.groupByTag(groupTag) ?: return
        if (group.strategy == GroupStrategy.FALLBACK) {
            val primary = group.effectiveMembers().firstOrNull()
            if (member == primary) {
                clearFallback(groupTag)
            } else {
                val leaf = primary?.let { resolvePreferredLeaf(it, mutableSetOf(groupTag)) } ?: groupTag
                onPrimaryFailed(groupTag, leaf)
            }
        }
        runtimeScope?.launch(Dispatchers.IO) {
            syncAllBalancersAndPublish()
        }
    }

    fun buildFailoverPlan(groupTag: String): FailoverPlan {
        val group = workspace.groupByTag(groupTag)
            ?: return FailoverPlan(primaryLeaf = null, candidates = emptyList(), failoverTimeoutMs = 3_000)
        val members = group.effectiveMembers()
        if (members.isEmpty()) {
            return FailoverPlan(primaryLeaf = null, candidates = emptyList(), failoverTimeoutMs = 3_000)
        }
        val primaryLeaves = expandMemberLeaves(members.first(), visited = mutableSetOf(groupTag))
        val fallbackLeaves = buildList {
            for (fb in members.drop(1)) {
                addAll(expandMemberLeaves(fb, visited = mutableSetOf(groupTag)))
            }
        }
        val inFallback = fallbackStates[groupTag]?.inFallback == true
        val ordered = if (inFallback && fallbackLeaves.isNotEmpty()) {
            (fallbackLeaves + primaryLeaves).distinct()
        } else {
            (primaryLeaves + fallbackLeaves).distinct()
        }
        return FailoverPlan(
            primaryLeaf = primaryLeaves.firstOrNull(),
            candidates = ordered,
            failoverTimeoutMs = group.failoverTimeoutSeconds.coerceAtLeast(1) * 1_000,
        )
    }

    private fun clearFallback(groupTag: String) {
        synchronized(stateLock) {
            fallbackStates.remove(groupTag)
        }
        recoveryJobs.remove(groupTag)?.cancel()
        runtimeScope?.launch(Dispatchers.IO) {
            syncAllBalancersAndPublish()
        }
    }

    private fun restartUrlTestLoops(scope: CoroutineScope) {
        val currentUrlTestGroups = workspace.groups.filter { it.strategy == GroupStrategy.URL_TEST }
        val activeTags = currentUrlTestGroups.map { it.tag }.toSet()
        for (stale in urlTestJobs.keys - activeTags) {
            urlTestJobs.remove(stale)?.cancel()
        }
        for (group in currentUrlTestGroups) {
            if (urlTestJobs.containsKey(group.tag)) continue
            urlTestJobs[group.tag] = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val latest = workspace.groupByTag(group.tag) ?: break
                    if (latest.strategy != GroupStrategy.URL_TEST) break
                    val leaves = collectUniquePhysicalLeaves(latest, mutableSetOf(latest.tag))
                    if (leaves.isNotEmpty()) {
                        coroutineScope {
                            leaves.map { leaf ->
                                async(Dispatchers.IO) {
                                    val rtt = probeNode(leaf, latest.testUrl)
                                    val now = SystemClock.elapsedRealtime()
                                    probeResults[leaf] = if (rtt != null) {
                                        ProbeResult(healthy = true, latencyMs = rtt, completedAtElapsedMs = now)
                                    } else {
                                        ProbeResult(healthy = false, latencyMs = -1L, completedAtElapsedMs = now)
                                    }
                                }
                            }.awaitAll()
                        }
                        syncAllBalancersAndPublish()
                    }
                    delay(latest.intervalSeconds.coerceAtLeast(5) * 1_000L)
                }
            }
        }
    }

    private suspend fun syncAllBalancersAndPublish() {
        for (group in workspace.groups) {
            if (group.tag !in balancerTags) continue
            val targetOutbound = resolveDirectRoutingTarget(group, visited = mutableSetOf()) ?: continue
            runCatching {
                applyBalancerTarget(group.tag, targetOutbound)
            }.onFailure { e ->
                Log.w(TAG, "Updating balancer for '${group.tag}' deferred: ${e.message}")
            }
        }
        publishSnapshot()
    }

    private fun publishSnapshot() {
        val activeNodes = linkedMapOf<String, String>()
        for (group in workspace.groups) {
            val leaf = resolveActiveLeaf(group, visited = mutableSetOf())
            if (leaf != null) {
                activeNodes[group.tag] = leaf
            }
        }
        val latencies = probeResults.entries
            .filter { it.value.healthy && it.value.latencyMs >= 0L }
            .associate { it.key to it.value.latencyMs }
        publishStatus(activeNodes, HashMap(fallbackStates), latencies)
    }

    private fun resolveDirectRoutingTarget(group: ProxyGroup, visited: MutableSet<String>): String? {
        if (visited.add(group.tag) == false) return null
        val members = group.effectiveMembers()
        if (members.isEmpty()) return null
        return when (group.strategy) {
            GroupStrategy.SELECT -> {
                val chosen = group.selectedMember?.takeIf { it in members } ?: members.first()
                if (chosen.isGroup) {
                    fallbackRelayOutbounds[chosen.tag]
                        ?: workspace.groupByTag(chosen.tag)?.let { resolveDirectRoutingTarget(it, visited) }
                } else {
                    chosen.tag
                }
            }

            GroupStrategy.URL_TEST -> {
                rankUrlTestLeaves(group, visited).firstOrNull()
            }

            GroupStrategy.FALLBACK -> {
                fallbackRelayOutbounds[group.tag] ?: resolveActiveLeaf(group, visited)
            }
        }
    }

    private fun resolveActiveLeaf(group: ProxyGroup, visited: MutableSet<String>): String? {
        if (visited.add(group.tag) == false) return null
        val members = group.effectiveMembers()
        if (members.isEmpty()) return null
        return when (group.strategy) {
            GroupStrategy.SELECT -> {
                val chosen = group.selectedMember?.takeIf { it in members } ?: members.first()
                resolvePreferredLeaf(chosen, visited)
            }

            GroupStrategy.URL_TEST -> {
                rankUrlTestLeaves(group, visited).firstOrNull()
            }

            GroupStrategy.FALLBACK -> {
                val inFallback = fallbackStates[group.tag]?.inFallback == true
                if (inFallback && members.size > 1) {
                    members.drop(1).firstNotNullOfOrNull { resolvePreferredLeaf(it, visited.toMutableSet()) }
                        ?: resolvePreferredLeaf(members.first(), visited)
                } else {
                    resolvePreferredLeaf(members.first(), visited)
                }
            }
        }
    }

    private fun resolvePreferredLeaf(member: GroupMemberRef, visited: MutableSet<String>): String? {
        if (member.isGroup == false) return member.tag
        val child = workspace.groupByTag(member.tag) ?: return null
        return resolveActiveLeaf(child, visited)
    }

    private fun expandMemberLeaves(member: GroupMemberRef, visited: MutableSet<String>): List<String> {
        if (member.isGroup == false) return listOf(member.tag)
        val child = workspace.groupByTag(member.tag) ?: return emptyList()
        if (visited.add(child.tag) == false) return emptyList()
        return when (child.strategy) {
            GroupStrategy.URL_TEST -> rankUrlTestLeaves(child, visited)
            GroupStrategy.SELECT -> {
                val members = child.effectiveMembers()
                val chosen = child.selectedMember?.takeIf { it in members } ?: members.firstOrNull()
                if (chosen != null) expandMemberLeaves(chosen, visited) else emptyList()
            }

            GroupStrategy.FALLBACK -> {
                val members = child.effectiveMembers()
                if (members.isEmpty()) return emptyList()
                val primary = expandMemberLeaves(members.first(), visited.toMutableSet())
                val fallbacks = members.drop(1).flatMap { expandMemberLeaves(it, visited.toMutableSet()) }
                val inFallback = fallbackStates[child.tag]?.inFallback == true
                if (inFallback && fallbacks.isNotEmpty()) (fallbacks + primary).distinct()
                else (primary + fallbacks).distinct()
            }
        }
    }

    private fun rankUrlTestLeaves(group: ProxyGroup, visited: MutableSet<String>): List<String> {
        val now = SystemClock.elapsedRealtime()
        val trustMs = group.trustSeconds.coerceAtLeast(5) * 1_000L
        val allLeaves = group.effectiveMembers().flatMap { member ->
            if (member.isGroup == false) listOf(member.tag)
            else expandMemberLeaves(member, visited.toMutableSet())
        }.distinct()

        val trustedHealthy = mutableListOf<Pair<String, Long>>()
        val remainder = mutableListOf<String>()
        for (leaf in allLeaves) {
            if (leaf in BUILTIN_GROUP_TARGET_TAGS) {
                trustedHealthy += leaf to 0L
                continue
            }
            val sample = probeResults[leaf]
            if (sample != null && sample.healthy && (now - sample.completedAtElapsedMs) <= trustMs) {
                trustedHealthy += leaf to sample.latencyMs
            } else {
                remainder += leaf
            }
        }
        return trustedHealthy.sortedBy { it.second }.map { it.first } + remainder
    }

    private fun collectUniquePhysicalLeaves(group: ProxyGroup, visited: MutableSet<String>): List<String> {
        val leaves = linkedSetOf<String>()
        for (member in group.effectiveMembers()) {
            if (member.isGroup == false) {
                if (member.tag !in BUILTIN_GROUP_TARGET_TAGS) leaves.add(member.tag)
            } else if (visited.add(member.tag)) {
                workspace.groupByTag(member.tag)?.let { child ->
                    leaves.addAll(collectUniquePhysicalLeaves(child, visited))
                }
            }
        }
        return leaves.toList()
    }

    companion object {
        private const val TAG = "GroupRuntimeController"
    }
}
