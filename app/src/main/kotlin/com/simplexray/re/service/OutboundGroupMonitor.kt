package com.simplexray.re.service

import android.os.SystemClock
import android.util.Log
import com.simplexray.re.common.config.GroupProfile
import com.simplexray.re.common.config.GroupTarget
import com.simplexray.re.common.config.OutboundGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

internal class OutboundGroupMonitor {
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run(
        profiles: Flow<GroupProfile>,
        traffic: Flow<GroupTraffic>,
        check: suspend (String) -> Boolean,
        applyTarget: suspend (String, String?) -> Unit,
        publish: (Map<String, String>) -> Unit,
    ): Unit = coroutineScope {
        val events = Channel<Event>(Channel.UNLIMITED)
        val evidence = mutableMapOf<String, HealthEvidence>()
        val leafSchedules = mutableMapOf<String, LeafSchedule>()
        val overrides = mutableMapOf<String, GroupOverride>()
        val failedNodes = mutableMapOf<String, NodeFailure>()
        val usedGroups = mutableSetOf<String>()
        var groups = emptyMap<String, OutboundGroup>()
        var lastPublished: Map<String, String>? = null

        launch {
            profiles.collect { events.send(Event.ProfileChanged(it)) }
        }
        launch {
            traffic.collect { events.send(Event.Traffic(it)) }
        }

        var event: Event? = events.receive()
        while (isActive) {
            when (val next = event) {
                is Event.ProfileChanged -> {
                    groups = next.profile.groups.associateBy { it.tag }
                    usedGroups.retainAll(groups.keys)
                    for (tag in overrides.keys - groups.keys) {
                        overrides.remove(tag)?.job?.cancel()
                    }
                }
                is Event.Traffic -> when (val activity = next.activity) {
                    is GroupTraffic.Routed -> usedGroups.add(activity.group)
                    is GroupTraffic.Failed -> {
                        val nowMs = SystemClock.elapsedRealtime()
                        val owners = usedGroups.filter {
                            groups[it]?.let { group -> primaryNode(group, groups) } == activity.node
                        }.toSet()
                        failedNodes.getOrPut(activity.node) { NodeFailure(nowMs, owners) }
                        evidence[activity.node] = HealthEvidence(false, nowMs)
                        leafSchedules.remove(activity.node)?.job?.cancel()
                    }
                }
                is Event.CheckCompleted -> {
                    if (leafSchedules[next.tag] === next.schedule) {
                        next.schedule.lastStartedAtMs = next.startedAtMs
                        next.schedule.job = null
                        next.evidence?.let {
                            evidence[next.tag] = it
                            if (it.healthy) failedNodes.remove(next.tag)
                        }
                    }
                }
                is Event.OverrideCompleted -> {
                    if (overrides[next.tag] === next.state) {
                        next.state.job = null
                        next.state.confirmed = if (next.applied) next.target else null
                        next.state.retryAtMs = if (next.applied || next.state.desired != next.target) {
                            next.completedAtMs
                        } else {
                            next.completedAtMs + RETRY_INTERVAL_MS
                        }
                    }
                }
                null -> Unit
            }

            val nowMs = SystemClock.elapsedRealtime()
            val wakeups = mutableListOf<Long>()
            for (group in groups.values) {
                val resolved = resolveGroup(
                    group,
                    groups,
                    evidence,
                    failedNodes,
                    nowMs,
                    group.trustSeconds * 1_000L,
                    asCandidate = false,
                )
                resolved?.expiresAtMs?.let { wakeups.add(it) }
                val state = overrides.getOrPut(group.tag) { GroupOverride() }
                val desired = OverrideTarget(resolved?.tag)
                if (state.desired != desired) {
                    state.desired = desired
                    state.retryAtMs = nowMs
                }
                if (state.job == null && state.confirmed != desired) {
                    if (nowMs >= state.retryAtMs) {
                        state.job = launch {
                            var applied = false
                            try {
                                applyTarget(group.tag, desired.tag)
                                applied = true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "Scheduling a target update retry for group ${group.tag} after an RPC error.",
                                    e,
                                )
                            } finally {
                                events.trySend(
                                    Event.OverrideCompleted(
                                        group.tag,
                                        state,
                                        desired,
                                        applied,
                                        SystemClock.elapsedRealtime(),
                                    )
                                )
                            }
                        }
                    } else {
                        wakeups.add(state.retryAtMs)
                    }
                }
            }

            val requestedChecks = standbyIntervals(groups)
                .mapValues { (_, interval) -> CheckSchedule(interval, nowMs, recovering = false) }
                .toMutableMap()
            for ((node, failure) in failedNodes) {
                val interval = failure.primaryGroups.mapNotNull { tag ->
                    groups[tag]?.takeIf { primaryNode(it, groups) == node }
                        ?.recoveryIntervalSeconds?.times(1_000L)
                }.minOrNull()
                if (interval != null) {
                    requestedChecks[node] = CheckSchedule(
                        interval,
                        failure.failedAtMs + interval,
                        recovering = true,
                    )
                }
            }
            // A healthy business primary relies on its real connection outcomes.
            for (tag in usedGroups) {
                groups[tag]?.let { primaryNode(it, groups) }?.let { node ->
                    if (node !in failedNodes) requestedChecks.remove(node)
                }
            }
            for (node in leafSchedules.keys - requestedChecks.keys) {
                leafSchedules.remove(node)?.job?.cancel()
            }
            for ((node, requested) in requestedChecks) {
                var schedule = leafSchedules[node]
                if (schedule == null || schedule.recovering != requested.recovering) {
                    schedule?.job?.cancel()
                    schedule = LeafSchedule(requested.intervalMs, requested.firstAtMs, requested.recovering)
                    leafSchedules[node] = schedule
                }
                schedule.intervalMs = requested.intervalMs
                if (requested.recovering) schedule.firstAtMs = requested.firstAtMs
            }
            for ((tag, schedule) in leafSchedules) {
                if (schedule.job != null) continue
                val dueAtMs = schedule.lastStartedAtMs?.plus(schedule.intervalMs) ?: schedule.firstAtMs
                if (nowMs >= dueAtMs) {
                    schedule.job = launch {
                        val startedAtMs = SystemClock.elapsedRealtime()
                        var result: HealthEvidence? = null
                        try {
                            result = HealthEvidence(check(tag), SystemClock.elapsedRealtime())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            result = HealthEvidence(false, SystemClock.elapsedRealtime())
                            Log.w(TAG, "Recording unhealthy outbound $tag after a check error.", e)
                        } finally {
                            events.trySend(Event.CheckCompleted(tag, schedule, startedAtMs, result))
                        }
                    }
                } else {
                    wakeups.add(dueAtMs)
                }
            }

            // Publication follows the current resolution and completed override RPCs.
            val effective = buildMap {
                for (tag in groups.keys) {
                    val state = overrides.getValue(tag)
                    val confirmed = state.confirmed
                    if (state.job == null && confirmed != null && confirmed == state.desired) {
                        confirmed.tag?.let { put(tag, it) }
                    }
                }
            }
            if (effective != lastPublished) {
                publish(effective)
                lastPublished = effective
            }

            val wakeAtMs = wakeups.minOrNull()
            event = select<Event?> {
                events.onReceive { it }
                if (wakeAtMs != null) {
                    onTimeout(wakeAtMs - SystemClock.elapsedRealtime()) { null }
                }
            }
        }
    }

    private fun standbyIntervals(groups: Map<String, OutboundGroup>): Map<String, Long> {
        val intervals = mutableMapOf<String, Long>()
        fun collect(target: GroupTarget, intervalMs: Long) {
            if (target.isGroup) {
                val group = groups.getValue(target.tag)
                (listOf(group.primary) + group.fallbacks).forEach {
                    collect(it, group.intervalSeconds * 1_000L)
                }
            } else if (target.tag !in BUILTIN_TARGETS) {
                intervals[target.tag] = minOf(intervals[target.tag] ?: intervalMs, intervalMs)
            }
        }
        groups.values.forEach { group ->
            group.fallbacks.forEach { collect(it, group.intervalSeconds * 1_000L) }
        }
        return intervals
    }

    private fun primaryNode(group: OutboundGroup, groups: Map<String, OutboundGroup>): String =
        if (group.primary.isGroup) primaryNode(groups.getValue(group.primary.tag), groups)
        else group.primary.tag

    private fun resolveGroup(
        group: OutboundGroup,
        groups: Map<String, OutboundGroup>,
        evidence: Map<String, HealthEvidence>,
        failedNodes: Map<String, NodeFailure>,
        nowMs: Long,
        parentTrustMs: Long,
        asCandidate: Boolean,
    ): ResolvedLeaf? {
        val trustMs = minOf(parentTrustMs, group.trustSeconds * 1_000L)
        val primary = resolveTarget(
            group.primary, groups, evidence, failedNodes, nowMs, trustMs, asCandidate,
        )
        if (primary != null) return primary
        for (target in group.fallbacks) {
            val resolved = resolveTarget(target, groups, evidence, failedNodes, nowMs, trustMs, asCandidate = true)
            if (resolved != null) return resolved
        }
        return null
    }

    private fun resolveTarget(
        target: GroupTarget,
        groups: Map<String, OutboundGroup>,
        evidence: Map<String, HealthEvidence>,
        failedNodes: Map<String, NodeFailure>,
        nowMs: Long,
        trustMs: Long,
        asCandidate: Boolean,
    ): ResolvedLeaf? {
        if (target.isGroup) {
            return resolveGroup(
                groups.getValue(target.tag),
                groups,
                evidence,
                failedNodes,
                nowMs,
                trustMs,
                asCandidate,
            )
        }
        if (target.tag in BUILTIN_TARGETS) return ResolvedLeaf(target.tag, null)
        if (target.tag in failedNodes) return null
        if (asCandidate == false) return ResolvedLeaf(target.tag, null)
        val sample = evidence[target.tag] ?: return null
        val expiresAtMs = sample.completedAtMs + trustMs
        return if (sample.healthy && nowMs < expiresAtMs) {
            ResolvedLeaf(target.tag, expiresAtMs)
        } else {
            null
        }
    }

    private data class HealthEvidence(val healthy: Boolean, val completedAtMs: Long)

    private data class NodeFailure(val failedAtMs: Long, val primaryGroups: Set<String>)

    private data class CheckSchedule(val intervalMs: Long, val firstAtMs: Long, val recovering: Boolean)

    private data class ResolvedLeaf(val tag: String, val expiresAtMs: Long?)

    private data class OverrideTarget(val tag: String?)

    private class LeafSchedule(var intervalMs: Long, var firstAtMs: Long, val recovering: Boolean) {
        var lastStartedAtMs: Long? = null
        var job: Job? = null
    }

    private class GroupOverride {
        var desired: OverrideTarget? = null
        var confirmed: OverrideTarget? = null
        var retryAtMs = 0L
        var job: Job? = null
    }

    private sealed interface Event {
        data class ProfileChanged(val profile: GroupProfile) : Event
        data class Traffic(val activity: GroupTraffic) : Event

        data class CheckCompleted(
            val tag: String,
            val schedule: LeafSchedule,
            val startedAtMs: Long,
            val evidence: HealthEvidence?,
        ) : Event

        data class OverrideCompleted(
            val tag: String,
            val state: GroupOverride,
            val target: OverrideTarget,
            val applied: Boolean,
            val completedAtMs: Long,
        ) : Event
    }

    companion object {
        private const val TAG = "OutboundGroupMonitor"
        private const val RETRY_INTERVAL_MS = 1_000L
        private val BUILTIN_TARGETS = setOf("direct", "block")
    }
}
