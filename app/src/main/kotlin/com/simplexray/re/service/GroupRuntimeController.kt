package com.simplexray.re.service

import android.os.SystemClock
import com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.ProxyGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class FallbackRuntimeInfo(
    val inFallback: Boolean = false,
    val nextRecoveryAtElapsedMs: Long = 0L,
    val activeLeaf: String? = null,
)

internal class GroupRuntimeController(
    initialWorkspace: GroupWorkspace,
    private val probeNode: suspend (nodeTag: String, testUrl: String, timeoutMs: Int) -> Long?,
    private val publishStatus: (Map<String, String>, Map<String, FallbackRuntimeInfo>, Map<String, Long>) -> Unit,
    private val logEvent: (String) -> Unit,
    private val nowElapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val onDemoted: (String, GroupMemberRef) -> Unit = { _, _ -> },
) {
    private data class Step(
        val owner: Group,
        val member: GroupMemberRef,
        val generation: Long,
        val eventGeneration: Long,
    )

    private data class Route(
        val leaf: String,
        val steps: List<Step> = emptyList(),
        val front: Route? = null,
    ) {
        fun withSelectionVersions(version: (Group) -> Long): Route = if (steps.isEmpty() && front == null) this else
            copy(steps = steps.map { it.copy(generation = version(it.owner)) }, front = front?.withSelectionVersions(version))
    }

    private data class Candidate(val member: GroupMemberRef, val route: Route) {
        // A live connection keeps its path through automatic reselection.
        fun replyKey() = copy(route = route.withSelectionVersions { 0L })
    }
    private data class Destination(val member: GroupMemberRef, val leaf: String, val host: String)
    private data class DestinationFailure(val route: Route, val until: Long)
    private data class EvidenceTime(val at: Long, val version: Long)
    private data class Sample(
        val route: Route, val startedAt: Long, val completedAt: Long, val delayMs: Long?, val replyVersion: Long? = null,
    )
    private data class RecoveryResult(val candidate: Candidate, val sample: Sample)
    private data class Plan(
        val tag: String, val routes: List<Route>, val generation: Long, val credit: (String) -> Unit,
    ) : FailoverPlan.ReplyEvidence {
        val route: Route? get() = routes.firstOrNull()
        override fun creditReply(leaf: String) = credit(leaf)
    }

    private class Group(val config: ProxyGroup) {
        val members = config.effectiveMembers().distinct()
        val samples = mutableMapOf<GroupMemberRef, Sample>()
        val replies = mutableMapOf<Candidate, EvidenceTime>()
        var selected = if (config.strategy == GroupStrategy.SELECT) config.selectedMember else null
        var manual = false
        var selectionVersion = 0L
        var eventVersion = 0L
        val failures = mutableMapOf<GroupMemberRef, ProxyFailure>()
        val destinations = mutableMapOf<Destination, DestinationFailure>()
        var urlJob: Job? = null
        var urlGeneration = 0L
        var recoveryJob: Job? = null
        var recoveryAt = 0L
    }

    private class ProxyFailure(var candidate: Candidate) {
        val suspectHosts = mutableMapOf<String, Long>()
        var cooldownUntil = 0L
        var backoffLevel = 0
        var lastCheckAt: Long? = null
        var checkJob: Job? = null
        var pendingGapMs: Long? = null
        var checkDueAt = 0L
        var checkTimer: Job? = null
        var checkGeneration = 0L
    }

    private data class ManualMeasurement(
        val route: Route,
        val groups: Map<String, Pair<Group, Long>>,
        val startedAt: Long,
        val replyVersion: Long,
    )

    private val lock = Any()
    private val measurementSlots = Semaphore(6)
    private var groups = initialWorkspace.groups.associate { it.tag to Group(it) }
    private var nodeFronts = nodeFronts(initialWorkspace)
    private val plans = mutableMapOf<String, Plan>()
    private var nextGeneration = 0L
    private var replyVersion = 0L
    private var scope: CoroutineScope? = null

    suspend fun start(scope: CoroutineScope) {
        synchronized(lock) {
            this.scope = scope
            groups.values.forEach(::startUrlTest)
            publish()
        }
    }

    fun updateWorkspace(updated: GroupWorkspace, startHealthChecks: Boolean = true) {
        synchronized(lock) {
            nodeFronts = nodeFronts(updated)
            val previous = groups
            groups = updated.groups.associate { config ->
                val existing = previous[config.tag]
                config.tag to (existing?.takeIf { it.config == config } ?: Group(config))
            }
            previous.values.filter { groups[it.config.tag] !== it }.forEach(::stopJobs)
            groups.values.forEach { group ->
                group.replies.keys.removeAll { !hasCurrentOwners(it.route) }
                val replaced = group.failures.filterValues { currentRoute(it.candidate.route) == null }
                replaced.forEach { (member, state) ->
                    stopFailure(state)
                    group.failures.remove(member)
                }
            }
            plans.keys.retainAll(groups.keys)
            if (startHealthChecks) groups.values.forEach(::startUrlTest)
            publish()
        }
    }

    fun stop() {
        synchronized(lock) {
            scope = null
            groups.values.forEach(::stopJobs)
            plans.clear()
        }
    }

    private fun stopJobs(group: Group) {
        group.selectionVersion++
        group.eventVersion++
        group.failures.values.forEach(::stopFailure)
        group.failures.clear()
        group.destinations.clear()
        group.replies.clear()
        group.urlGeneration++
        group.urlJob?.cancel()
        group.urlJob = null
        group.recoveryJob?.cancel()
        group.recoveryJob = null
    }

    fun buildFailoverPlan(groupTag: String, host: String = ""): FailoverPlan = synchronized(lock) {
        synchronizeFallbackSelections()
        val key = host.lowercase()
        val now = nowElapsedRealtimeMs()
        val plan = plan(groupTag).forDestination(key, now)
        val ordinary = plan.routes.take(FallbackPolicy.MAX_DIAL_ATTEMPTS)
        val hostRetries = plan.hostRetryRoutes(key, now).toList()
        val dialRoutes = (ordinary + hostRetries).distinctBy { it.leaf }
        val fallback = plan.route?.steps?.asReversed()?.firstOrNull {
            it.owner.config.strategy == GroupStrategy.FALLBACK
        }?.owner
        FailoverPlan(
            primaryLeaf = plan.route?.leaf,
            candidates = ordinary.map { it.leaf },
            hostRetryCandidates = hostRetries.map { it.leaf },
            failoverTimeoutMs = fallback?.let(::fallbackTimeout) ?: 3_000,
            watchResponse = fallback != null,
            generation = plan.generation,
            owner = plan,
            stallTimeoutMs = FallbackPolicy.stallTimeoutMs(plan.route?.let(::routeLatency)),
            stallTimeoutsMs = dialRoutes.associate { it.leaf to FallbackPolicy.stallTimeoutMs(routeLatency(it)) },
            groupPaths = dialRoutes.associate { it.leaf to it.steps.map { step -> step.owner.config.tag }.toSet() },
        )
    }

    fun onCandidateConnected(groupTag: String, leaf: String, owner: Any?, host: String) {
        synchronized(lock) {
            val route = eventRoute(groupTag, owner, leaf) ?: return
            val now = nowElapsedRealtimeMs()
            val key = host.lowercase()
            val members = routeMembers(route, fallbackOnly = false)
            recordReply(members.map { (group, candidate) -> group to candidate.replyKey() }, now)
            members.firstOrNull { it.first.config.strategy == GroupStrategy.FALLBACK }?.let { (group, candidate) ->
                val destination = candidate.destination(key)
                if (group.destinations[destination]?.let { currentRoute(it.route) } == candidate.route) {
                    group.destinations.remove(destination)
                }
            }
            for ((group, candidate) in members) {
                val state = group.failures[candidate.member]?.takeIf {
                    ownsFailure(group, it) && currentRoute(it.candidate.route) == candidate.route
                } ?: continue
                if (now < state.cooldownUntil) continue
                state.suspectHosts.remove(key)
                if (state.backoffLevel > 0 && state.cooldownUntil > 0 &&
                    now - state.cooldownUntil >= FallbackPolicy.RECOVERY_STABLE_MS && currentCandidate(candidate) != null
                ) {
                    state.backoffLevel--
                    state.cooldownUntil = now
                }
            }
        }
    }

    private fun recordReply(keys: List<Pair<Group, Candidate>>, now: Long) {
        if (keys.isEmpty()) return
        val reply = EvidenceTime(now, ++replyVersion)
        for ((group, key) in keys) {
            if (key !in group.replies) group.replies.keys.removeAll { !hasCurrentOwners(it.route) }
            group.replies[key] = reply
        }
    }

    suspend fun onCandidateFailed(
        groupTag: String,
        leaf: String,
        owner: Any?,
        host: String,
        strong: Boolean,
        destinationFailure: Boolean = strong,
    ) {
        synchronized(lock) {
            val route = eventRoute(groupTag, owner, leaf) ?: return
            reportSuspect(route, host.lowercase(), strong, destinationFailure)
        }
    }

    suspend fun onStalledUpload(
        groupTag: String,
        leaf: String,
        owner: Any?,
        host: String,
        strong: Boolean,
        responsePending: Boolean,
        applyIfWaiting: (() -> Unit) -> Boolean,
    ): Boolean {
        val key = host.lowercase()
        var checks = emptyList<Job>()
        var completed: Job? = null
        while (true) {
            synchronized(lock) {
                var retry = false
                val claimed = applyIfWaiting {
                    val route = eventRoute(groupTag, owner, leaf) ?: run {
                        checks = emptyList()
                        return@applyIfWaiting
                    }
                    val finished = completed
                    if (finished == null) {
                        checks = reportSuspect(route, key, strong && responsePending, destinationFailure = responsePending)
                    } else if (finished.isCancelled) {
                        checks = (checks + routeMembers(route).mapNotNull { (group, candidate) ->
                            val state = group.failures[candidate.member]?.takeIf {
                                ownsFailure(group, it) && currentRoute(it.candidate.route) == candidate.route
                            } ?: return@mapNotNull null
                            state.checkJob ?: state.checkTimer
                        }).distinct()
                    }
                    synchronizeFallbackSelections()
                    val current = plan(groupTag)
                    val now = nowElapsedRealtimeMs()
                    val preferred = current.route
                    // Idle transfers follow their serving route's health; host evidence applies to pending responses.
                    val servingImpaired = preferred != null && preferred.leaf != leaf && impaired(route, now)
                    retry = if (responsePending) {
                        (servingImpaired || affected(route, key, now)) &&
                            current.hostRetryRoutes(key, now).any { it.leaf != leaf }
                    } else servingImpaired
                }
                if (claimed == false || retry) return claimed && retry
                checks = checks.filter { it !== completed }
                if (checks.isEmpty()) return false
            }
            // Each completed branch can supply a replacement while its ancestors continue checking.
            completed = select<Job> { checks.forEach { job -> job.onJoin { job } } }
        }
    }

    private fun routeMembers(route: Route, fallbackOnly: Boolean = true): List<Pair<Group, Candidate>> =
        route.steps.mapIndexedNotNull { index, step ->
            if ((fallbackOnly && step.owner.config.strategy != GroupStrategy.FALLBACK) || route.leaf in BUILTIN_GROUP_TARGET_TAGS) {
                null
            } else {
                step.owner to Candidate(step.member, route.copy(steps = route.steps.drop(index + 1)))
            }
        }.asReversed()

    private fun failureState(group: Group, candidate: Candidate): ProxyFailure? {
        val current = currentCandidate(candidate) ?: return null
        if (groups[group.config.tag] !== group || current.member !in group.members ||
            candidate.route.leaf in BUILTIN_GROUP_TARGET_TAGS
        ) return null
        val previous = group.failures[current.member]
        if (previous != null && ownsFailure(group, previous)) {
            if (currentRoute(previous.candidate.route) != current.route) {
                stopFailure(previous)
                previous.suspectHosts.clear()
            }
            previous.candidate = current
            return previous
        }
        previous?.let(::stopFailure)
        return ProxyFailure(current).also { group.failures[current.member] = it }
    }

    private fun ownsFailure(group: Group, state: ProxyFailure): Boolean =
        groups[group.config.tag] === group && group.failures[state.candidate.member] === state &&
            state.candidate.member in group.members && currentRoute(state.candidate.route) != null

    private fun refreshFailureCandidate(group: Group, state: ProxyFailure): Candidate? {
        if (ownsFailure(group, state) == false) return null
        val current = candidate(state.candidate.member) ?: return null
        if (currentRoute(state.candidate.route) != current.route) state.suspectHosts.clear()
        state.candidate = current
        return current
    }

    private fun stopCheckTimer(state: ProxyFailure) {
        state.checkGeneration++
        state.checkTimer?.cancel()
        state.checkTimer = null
        state.pendingGapMs = null
    }

    private fun stopFailure(state: ProxyFailure) {
        stopCheckTimer(state)
        state.checkJob?.cancel()
        state.checkJob = null
    }

    private fun Candidate.destination(host: String) = Destination(member, route.leaf, host)

    private fun affected(route: Route, host: String, now: Long): Boolean {
        val (group, candidate) = routeMembers(route).firstOrNull() ?: return false
        val failure = group.destinations[candidate.destination(host)] ?: return false
        return now < failure.until && currentRoute(failure.route) == candidate.route
    }

    private fun impaired(route: Route, now: Long): Boolean = routeMembers(route).any { (group, candidate) ->
        isDemoted(group, candidate, now) || isAlive(group, candidate) == false
    }

    private fun destinationRank(route: Route, host: String, now: Long): Int = when {
        affected(route, host, now) -> 2
        route.leaf in BUILTIN_GROUP_TARGET_TAGS || impaired(route, now) -> 1
        else -> 0
    }

    private fun Plan.hostRetryRoutes(host: String, now: Long): Sequence<Route> =
        routes.asSequence().filter { destinationRank(it, host, now) == 0 }

    private fun reportSuspect(route: Route, host: String, strong: Boolean, destinationFailure: Boolean): List<Job> {
        val members = routeMembers(route)
        if (destinationFailure && host.isNotBlank()) {
            // The innermost fallback owns the actual attempt. Every enclosing plan sees the same evidence.
            val now = nowElapsedRealtimeMs()
            members.firstOrNull()?.let { (group, candidate) ->
                group.destinations.values.removeAll { now >= it.until }
                group.destinations[candidate.destination(host)] =
                    DestinationFailure(candidate.route, now + FallbackPolicy.HOST_WINDOW_MS)
            }
        }
        return members.mapNotNull { (group, candidate) -> reportSuspect(group, candidate, host, strong) }
    }

    private fun reportSuspect(
        group: Group,
        candidate: Candidate,
        host: String,
        strong: Boolean,
    ): Job? {
        val state = failureState(group, candidate) ?: return null
        val now = nowElapsedRealtimeMs()
        if (now < state.cooldownUntil) return null
        if (strong && host.isNotBlank()) {
            state.suspectHosts.entries.removeAll { now - it.value > FallbackPolicy.HOST_WINDOW_MS }
            state.suspectHosts[host] = now
            if (state.suspectHosts.size >= FallbackPolicy.HOST_THRESHOLD &&
                state.candidate.route.steps.none { it.owner.config.strategy == GroupStrategy.FALLBACK }
            ) {
                demote(group, state, "three distinct destinations stalled or failed within 60 seconds")
                return null
            }
        }
        queueCheck(group, state, FallbackPolicy.checkGapMs(strong))
        return state.checkJob ?: state.checkTimer
    }

    private fun queueCheck(group: Group, state: ProxyFailure, gapMs: Long) {
        val runtimeScope = scope ?: return
        if (state.checkJob != null) {
            state.pendingGapMs = minOf(state.pendingGapMs ?: gapMs, gapMs)
            return
        }
        val now = nowElapsedRealtimeMs()
        val dueAt = state.lastCheckAt?.plus(gapMs) ?: now
        if (dueAt <= now) {
            startComparativeCheck(group, state)
            return
        }
        if (state.checkTimer != null && state.checkDueAt <= dueAt) return
        stopCheckTimer(state)
        state.checkDueAt = dueAt
        val generation = state.checkGeneration
        state.checkTimer = runtimeScope.launch(start = CoroutineStart.LAZY) {
            delay((dueAt - nowElapsedRealtimeMs()).coerceAtLeast(0L))
            val check = synchronized(lock) {
                if (ownsFailure(group, state) == false || state.checkGeneration != generation) return@launch
                state.checkTimer = null
                if (nowElapsedRealtimeMs() >= state.cooldownUntil) startComparativeCheck(group, state)
                state.checkJob
            }
            check?.join()
        }
        state.checkTimer?.start()
    }

    private fun startComparativeCheck(group: Group, state: ProxyFailure) {
        val runtimeScope = scope ?: return
        stopCheckTimer(state)
        val generation = state.checkGeneration
        val childEvidenceSince = EvidenceTime(nowElapsedRealtimeMs(), replyVersion)
        lateinit var job: Job
        job = runtimeScope.launch(start = CoroutineStart.LAZY) {
            try {
                // Child checks resolve their alternatives before an ancestor evaluates the branch.
                awaitChildChecks(group, state, generation, childEvidenceSince)
                val peerMember = synchronized(lock) {
                    if (state.checkGeneration != generation) return@launch
                    val current = refreshFailureCandidate(group, state) ?: return@launch
                    val peers = orderedCandidates(group, emptySet()).filter {
                        it.member != current.member && it.route.leaf != current.route.leaf && it.route.leaf !in BUILTIN_GROUP_TARGET_TAGS
                    }
                    val peer = peers.firstOrNull { isDemoted(group, it) == false && isAlive(group, it) }
                        ?: peers.firstOrNull { isDemoted(group, it) == false } ?: peers.firstOrNull()
                    peer?.member
                }
                if (peerMember != null) awaitChildChecks(group, state, generation, childEvidenceSince, peerMember)
                val (current, peer, timeoutMs) = synchronized(lock) {
                    if (state.checkGeneration != generation) return@launch
                    val current = refreshFailureCandidate(group, state) ?: return@launch
                    val now = nowElapsedRealtimeMs()
                    state.suspectHosts.entries.removeAll { now - it.value > FallbackPolicy.HOST_WINDOW_MS }
                    if (state.suspectHosts.size >= FallbackPolicy.HOST_THRESHOLD) {
                        demote(group, state, "three distinct destinations stalled or failed within 60 seconds")
                        return@launch
                    }
                    val peer = peerMember?.let(::candidate)?.takeIf { it.route.leaf != current.route.leaf }
                    state.lastCheckAt = nowElapsedRealtimeMs()
                    Triple(current, peer, FallbackPolicy.stallTimeoutMs(group.samples[current.member]?.delayMs))
                }
                val measured = coroutineScope {
                    val selfResult = async { measure(group, current.route, timeoutMs) }
                    val peerResult = peer?.let { async { measure(group, it.route, timeoutMs) } }
                    selfResult.await() to peerResult?.await()
                }
                awaitChildChecks(group, state, generation, childEvidenceSince)
                if (peer != null) awaitChildChecks(group, state, generation, childEvidenceSince, peer.member)
                synchronized(lock) {
                    if (ownsFailure(group, state) == false || state.checkGeneration != generation) return@launch
                    val active = refreshFailureCandidate(group, state) ?: return@launch
                    if (currentRoute(current.route) != active.route) {
                        state.pendingGapMs = FallbackPolicy.STRONG_CHECK_GAP_MS
                        return@launch
                    }
                    val selfSample = measured.first.copy(route = active.route)
                    saveSample(group, active, selfSample)
                    val activePeer = peer?.let(::currentCandidate)
                    val peerSample = measured.second?.let { sample -> activePeer?.let { sample.copy(route = it.route) } }
                    if (activePeer != null && peerSample != null) {
                        saveSample(group, activePeer, peerSample)
                        if (selfSample.delayMs == null && peerSample.delayMs != null &&
                            group.samples[active.member] === selfSample && group.samples[activePeer.member] === peerSample
                        ) {
                            demote(group, state, "health URL failed while ${activePeer.displayName()} succeeded")
                        }
                    } else if (peer != null) {
                        state.pendingGapMs = FallbackPolicy.STRONG_CHECK_GAP_MS
                    }
                    publish()
                    if (selfSample.delayMs == null && group.samples[active.member] === selfSample &&
                        isDemoted(group, active) == false &&
                        group.selected?.let { it != active.member } == true
                    ) {
                        onDemoted(group.config.tag, active.member)
                    }
                }
            } finally {
                synchronized(lock) {
                    if (state.checkJob === job) {
                        state.checkJob = null
                        val gap = state.pendingGapMs
                        if (gap != null && ownsFailure(group, state) && state.checkGeneration == generation &&
                            nowElapsedRealtimeMs() >= state.cooldownUntil
                        ) {
                            state.pendingGapMs = null
                            queueCheck(group, state, gap)
                        }
                    }
                }
            }
        }
        state.checkJob = job
        job.start()
    }

    private suspend fun awaitChildChecks(
        group: Group,
        state: ProxyFailure,
        generation: Long,
        evidenceSince: EvidenceTime,
        member: GroupMemberRef = state.candidate.member,
    ) {
        while (true) {
            val pending = synchronized(lock) {
                if (ownsFailure(group, state) == false || state.checkGeneration != generation) return
                val current = candidate(member) ?: return
                for ((childGroup, childCandidate) in routeMembers(current.route)) {
                    val sample = childGroup.samples[childCandidate.member]?.takeIf {
                        currentRoute(it.route) == childCandidate.route
                    }
                    // This evaluation consumes completed child evidence while queued rechecks continue independently.
                    if ((sample != null && sample.completedAt >= evidenceSince.at) ||
                        (childGroup.replies[childCandidate.replyKey()]?.version ?: 0L) > evidenceSince.version
                    ) continue
                    val child = childGroup.failures[childCandidate.member]?.takeIf { ownsFailure(childGroup, it) }
                    val existing = child?.checkJob ?: child?.checkTimer
                    if (existing != null) return@synchronized existing
                    if (isDemoted(childGroup, childCandidate) == false) {
                        val next = failureState(childGroup, childCandidate) ?: continue
                        startComparativeCheck(childGroup, next)
                        return@synchronized next.checkJob
                    }
                }
                return
            }
            pending?.join()
        }
    }

    private fun demote(group: Group, state: ProxyFailure, reason: String) {
        if (ownsFailure(group, state) == false) return
        val now = nowElapsedRealtimeMs()
        if (now < state.cooldownUntil) return
        val previous = group.selected ?: primary(group)?.member
        val cooldown = (FallbackPolicy.BASE_COOLDOWN_MS shl state.backoffLevel).coerceAtMost(FallbackPolicy.MAX_COOLDOWN_MS)
        state.backoffLevel = (state.backoffLevel + 1).coerceAtMost(4)
        state.cooldownUntil = now + cooldown
        state.suspectHosts.clear()
        stopCheckTimer(state)
        state.checkJob?.cancel()
        state.checkJob = null
        if (group.selected == state.candidate.member) group.selected = null
        val next = orderedCandidates(group, emptySet()).firstOrNull()
        if (next != null && (previous != next.member ||
                (next.member != primary(group)?.member && group.recoveryJob == null))
        ) selectFallback(group, next.member)
        logEvent("[Warning] ${group.config.tag}: demoted ${state.candidate.displayName()} for ${cooldown / 1_000}s ($reason)")
        onDemoted(group.config.tag, state.candidate.member)
        publish()
    }

    private suspend fun firstReachable(group: Group, candidates: List<Candidate>, generation: Long): RecoveryResult? = coroutineScope {
        val timeoutMs = fallbackTimeout(group)
        val requests = candidates.filter { synchronized(lock) { isDemoted(group, it) == false } }.map { alternative ->
            async { alternative to measure(group, alternative.route, timeoutMs) }
        }
        try {
            for (request in requests) {
                val (alternative, sample) = request.await()
                val accepted = synchronized(lock) {
                    val current = currentCandidate(alternative)
                    if (groups[group.config.tag] === group && group.selectionVersion == generation &&
                        current != null
                    ) {
                        val currentSample = sample.copy(route = current.route)
                        saveSample(group, current, currentSample)
                        if (currentSample.delayMs != null && group.samples[current.member] === currentSample &&
                            isDemoted(group, current) == false
                        ) RecoveryResult(current, currentSample) else null
                    } else null
                }
                if (accepted != null) return@coroutineScope accepted
            }
            null
        } finally {
            requests.forEach { it.cancel() }
        }
    }

    private fun Candidate.displayName(): String =
        if (member.isGroup) "${member.tag} (${route.leaf})" else route.leaf

    private fun selectFallback(group: Group, member: GroupMemberRef?) {
        group.selected = member
        group.selectionVersion++
        group.recoveryJob?.cancel()
        group.recoveryJob = null
        group.recoveryAt = 0L
        if (member == null || member == primary(group)?.member) return
        val runtimeScope = scope ?: return
        val generation = group.selectionVersion
        val intervalMs = group.config.recoveryIntervalSeconds * 1_000L
        group.recoveryAt = nowElapsedRealtimeMs() + intervalMs
        group.recoveryJob = runtimeScope.launch {
            while (true) {
                delay(intervalMs)
                val candidates = synchronized(lock) {
                    if (groups[group.config.tag] !== group || group.selectionVersion != generation) return@launch
                    group.members.takeWhile { it != member }.mapNotNull(::candidate)
                }
                val result = firstReachable(group, candidates, generation)
                synchronized(lock) {
                    if (groups[group.config.tag] !== group || group.selectionVersion != generation) return@launch
                    val restored = result?.candidate?.let(::currentCandidate)
                    if (result != null && restored != null && isDemoted(group, restored) == false &&
                        group.samples[restored.member] === result.sample
                    ) {
                        selectFallback(group, restored.member)
                        logEvent("[Info] ${group.config.tag}: recovered to ${restored.displayName()}")
                        publish()
                        return@launch
                    }
                    group.recoveryAt = nowElapsedRealtimeMs() + intervalMs
                    publish()
                }
            }
        }
    }

    private fun startUrlTest(group: Group) {
        if (group.config.strategy != GroupStrategy.URL_TEST || group.urlJob != null) return
        val runtimeScope = scope ?: return
        val generation = ++group.urlGeneration
        group.urlJob = runtimeScope.launch {
            while (true) {
                synchronized(lock) {
                    if (groups[group.config.tag] !== group || group.urlGeneration != generation) return@launch
                }
                measureGroup(group, generation)
                delay(group.config.intervalSeconds * 1_000L)
            }
        }
    }

    private suspend fun measureGroup(group: Group, generation: Long? = null) = coroutineScope {
        val (candidates, version, urlGeneration) = synchronized(lock) {
            if (groups[group.config.tag] !== group ||
                (generation != null && group.urlGeneration != generation)
            ) return@coroutineScope
            Triple(group.members.mapNotNull(::candidate), group.selectionVersion, group.urlGeneration)
        }
        val results = candidates.groupBy { it.route }.map { (route, aliases) ->
            async {
                aliases to measure(group, route, 5_000)
            }
        }.awaitAll()
        synchronized(lock) {
            if (groups[group.config.tag] !== group || group.urlGeneration != urlGeneration) return@synchronized
            results.forEach { (aliases, sample) ->
                aliases.mapNotNull(::currentCandidate).forEach { saveSample(group, it, sample.copy(route = it.route)) }
            }
            updateUrlSelection(group, clearManual = group.selectionVersion == version)
            publish()
        }
    }

    private fun updateUrlSelection(group: Group, clearManual: Boolean) {
        if (group.manual && clearManual == false) return
        val now = nowElapsedRealtimeMs()
        val validForMs = group.config.trustSeconds * 1_000L
        val best = group.members.mapNotNull(::candidate).mapNotNull { candidate ->
            group.samples[candidate.member]
                ?.takeIf { currentRoute(it.route) == candidate.route && now - it.completedAt <= validForMs }
                ?.delayMs?.let { candidate to it }
        }.minByOrNull { it.second }?.first
        if (best != null) {
            val previous = group.selected?.let(::candidate) ?: primary(group)
            group.selected = best.member
            group.manual = false
            if (previous?.member != best.member) {
                group.selectionVersion++
                logEvent("[Info] ${group.config.tag}: URL test selected ${best.displayName()}")
            }
        }
    }

    private suspend fun measure(group: Group, route: Route, timeoutMs: Int): Sample = measurementSlots.withPermit {
        val (startedAt, version) = synchronized(lock) { nowElapsedRealtimeMs() to replyVersion }
        val latency = withTimeoutOrNull(timeoutMs.toLong()) {
            probeNode(route.leaf, group.config.testUrl, timeoutMs)
        }
        Sample(route, startedAt, nowElapsedRealtimeMs(), latency, version)
    }

    private fun saveSample(group: Group, candidate: Candidate, sample: Sample) {
        if (sample.delayMs == null) {
            val reply = group.replies[candidate.replyKey()]
            // Internally started checks have exact ordering; timestamp-only ties favor real traffic.
            if (reply != null && (sample.replyVersion?.let { reply.version > it } ?: (reply.at >= sample.startedAt))) return
        }
        if (sample.startedAt >= (group.samples[candidate.member]?.startedAt ?: Long.MIN_VALUE)) {
            group.samples[candidate.member] = sample
        }
    }

    fun forceSwitchMember(groupTag: String, member: GroupMemberRef) {
        synchronized(lock) {
            val group = groups[groupTag] ?: return
            val choice = candidate(member)
            if (member in group.members && choice != null) {
                group.eventVersion++
                val previous = group.selected?.let(::candidate) ?: primary(group)
                if (group.config.strategy == GroupStrategy.FALLBACK) {
                    group.failures.remove(member)?.let(::stopFailure)
                    group.destinations.keys.removeAll { it.member == member }
                    selectFallback(group, member)
                } else {
                    group.selected = member
                    group.manual = true
                    group.selectionVersion++
                }
                if (previous?.member != member) {
                    logEvent("[Info] $groupTag: manually selected ${choice.displayName()}")
                }
                publish()
            }
        }
    }

    fun clearSelection(groupTag: String) {
        synchronized(lock) {
            val group = groups[groupTag] ?: return
            group.eventVersion++
            if (group.config.strategy == GroupStrategy.FALLBACK) {
                selectFallback(group, null)
            } else {
                group.selected = if (group.config.strategy == GroupStrategy.SELECT) group.config.selectedMember else null
                group.manual = false
                group.selectionVersion++
                if (group.config.strategy == GroupStrategy.URL_TEST) updateUrlSelection(group, clearManual = true)
            }
            publish()
        }
    }

    fun activeLeaf(member: GroupMemberRef): String? = synchronized(lock) { route(member)?.leaf }

    fun measurementGenerations(): Map<String, Long> = synchronized(lock) {
        groups.mapValues { (_, group) -> group.selectionVersion }
    }

    suspend fun measureNestedGroups(members: List<GroupMemberRef>) {
        val visited = mutableSetOf<GroupMemberRef>()
        suspend fun prepare(member: GroupMemberRef) {
            if (visited.add(member) == false) return
            val group = if (member.isGroup) synchronized(lock) { groups[member.tag] } else null
            if (group != null) {
                group.members.forEach { prepare(it) }
                if (group.config.strategy == GroupStrategy.URL_TEST) measureGroup(group)
            } else if (member.isGroup == false) {
                synchronized(lock) { nodeFronts[member.tag] }?.let { prepare(it) }
            }
        }
        members.forEach { prepare(it) }
    }

    suspend fun measureTarget(member: GroupMemberRef, url: String, timeoutMs: Int): Long? {
        val attempt = synchronized(lock) {
            val route = route(member) ?: return null
            ManualMeasurement(
                route,
                groups.mapValues { (_, group) -> group to group.urlGeneration },
                nowElapsedRealtimeMs(),
                replyVersion,
            )
        }
        val result = probeNode(attempt.route.leaf, url, timeoutMs)
        synchronized(lock) {
            val current = currentRoute(attempt.route)
            if (current != null && route(member) == current) {
                val currentGroups = groups.values.filter { group ->
                    val (owner, generation) = attempt.groups[group.config.tag] ?: return@filter false
                    group.config.testUrl == url && owner === group && generation == group.urlGeneration
                }
                currentGroups.forEach { group ->
                    val measured = group.members.mapNotNull(::candidate)
                        .filter { it.route.leaf == current.leaf && it.route.front == current.front }
                    val completedAt = nowElapsedRealtimeMs()
                    measured.forEach { saveSample(group, it, Sample(it.route, attempt.startedAt, completedAt, result, attempt.replyVersion)) }
                    if (measured.isNotEmpty() && group.config.strategy == GroupStrategy.URL_TEST) {
                        updateUrlSelection(group, clearManual = false)
                    }
                }
                if (currentGroups.isNotEmpty()) publish()
            }
        }
        return result
    }

    fun recordMeasurement(
        leaf: String,
        url: String,
        result: Long?,
        startedAt: Long,
        restorePrimary: Boolean = false,
        selectionGenerations: Map<String, Long> = emptyMap(),
    ) {
        synchronized(lock) {
            groups.values.filter { it.config.testUrl == url }.forEach { group ->
                val measured = group.members.mapNotNull(::candidate).filter { it.route.leaf == leaf }
                measured.forEach { saveSample(group, it, Sample(it.route, startedAt, nowElapsedRealtimeMs(), result)) }
                val sameSelection = selectionGenerations.isEmpty() ||
                    selectionGenerations[group.config.tag] == group.selectionVersion
                if (measured.isNotEmpty() && sameSelection) {
                    when (group.config.strategy) {
                        GroupStrategy.URL_TEST -> updateUrlSelection(group, clearManual = false)
                        GroupStrategy.FALLBACK -> {
                            val primary = primary(group)
                            if (restorePrimary && result != null && primary in measured && group.selected != null &&
                                primary != null && isDemoted(group, primary) == false
                            ) {
                                selectFallback(group, null)
                            }
                        }
                        GroupStrategy.SELECT -> Unit
                    }
                }
            }
            publish()
        }
    }

    private fun fallbackTimeout(group: Group): Int =
        (group.config.failoverTimeoutSeconds * 1_000L).coerceAtMost(60_000L).toInt()

    private fun primary(group: Group): Candidate? = group.members.firstNotNullOfOrNull(::candidate)

    private fun isAlive(group: Group, candidate: Candidate): Boolean =
        group.samples[candidate.member]?.takeIf { currentRoute(it.route) == candidate.route }?.let { it.delayMs != null } ?: true

    private fun isDemoted(group: Group, candidate: Candidate, now: Long = nowElapsedRealtimeMs()): Boolean =
        group.failures[candidate.member]?.takeIf { ownsFailure(group, it) }
            ?.let { now < it.cooldownUntil } == true

    private fun orderedCandidates(group: Group, path: Set<GroupMemberRef>): List<Candidate> {
        val candidates = group.members.mapNotNull { candidate(it, path) }
        val now = nowElapsedRealtimeMs()
        val ranked = candidates.map {
            it to (if (isAlive(group, it)) 0 else 2) + (if (isDemoted(group, it, now)) 1 else 0)
        }
        val selected = ranked.firstOrNull { it.first.member == group.selected && it.second == 0 }?.first
        return listOfNotNull(selected) + ranked.filter { it.first != selected }.sortedBy { it.second }.map { it.first }
    }

    private fun selected(group: Group, path: Set<GroupMemberRef>): Candidate? =
        if (group.config.strategy == GroupStrategy.FALLBACK) orderedCandidates(group, path).firstOrNull()
        else group.selected?.takeIf { it in group.members }?.let { candidate(it, path) }
            ?: group.members.firstNotNullOfOrNull { candidate(it, path) }

    private fun candidate(member: GroupMemberRef): Candidate? = route(member)?.let { Candidate(member, it) }

    private fun candidate(member: GroupMemberRef, path: Set<GroupMemberRef>): Candidate? =
        route(member, path)?.let { Candidate(member, it) }

    private fun currentCandidate(previous: Candidate): Candidate? =
        candidate(previous.member)?.takeIf { it.route == currentRoute(previous.route) }

    private fun route(member: GroupMemberRef, path: Set<GroupMemberRef> = emptySet()): Route? {
        if (member in path) return null
        if (member.isGroup) {
            val group = groups[member.tag] ?: return null
            val choice = selected(group, path + member) ?: return null
            return choice.route.copy(
                steps = listOf(Step(group, choice.member, group.selectionVersion, group.eventVersion)) + choice.route.steps,
            )
        }
        if (member.tag in BUILTIN_GROUP_TARGET_TAGS || member.tag in nodeFronts) {
            val front = nodeFronts[member.tag]?.let { route(it, path + member) ?: return null }
            return Route(member.tag, front = front)
        }
        return null
    }

    private fun routes(member: GroupMemberRef, path: Set<GroupMemberRef>): List<Route> {
        if (member in path) return emptyList()
        if (member.isGroup == false) return listOfNotNull(route(member, path))
        val group = groups[member.tag] ?: return emptyList()
        val nextPath = path + member
        val choices = if (group.config.strategy == GroupStrategy.FALLBACK) orderedCandidates(group, nextPath)
            else listOfNotNull(selected(group, nextPath))
        return choices.flatMap { choice ->
            routes(choice.member, nextPath).map {
                it.copy(steps = listOf(Step(group, choice.member, group.selectionVersion, group.eventVersion)) + it.steps)
            }
        }
    }

    private fun routeLatency(route: Route): Long? = routeMembers(route).firstNotNullOfOrNull { (group, candidate) ->
        group.samples[candidate.member]?.takeIf { currentRoute(it.route) == candidate.route }?.delayMs
    }

    private fun plan(tag: String): Plan {
        val member = if (tag.startsWith(OutboundGroupConfig.PHYSICAL_PREFIX)) {
            GroupMemberRef(tag.removePrefix(OutboundGroupConfig.PHYSICAL_PREFIX))
        } else GroupMemberRef(tag, isGroup = true)
        val routes = routes(member, emptySet()).distinctBy { it.leaf }
        plans[tag]?.takeIf { it.routes == routes }?.let { return it }
        val credits = routes.associate { route ->
            val keys = routeMembers(route, fallbackOnly = false).map { (group, candidate) -> group to candidate.replyKey() }
            route.leaf to (route to keys)
        }
        return Plan(tag, routes, ++nextGeneration) credit@{ leaf ->
            val (route, keys) = credits[leaf] ?: return@credit
            if (keys.isEmpty()) return@credit
            synchronized(lock) {
                if (hasCurrentOwners(route)) recordReply(keys, nowElapsedRealtimeMs())
            }
        }.also { plans[tag] = it }
    }

    private fun Plan.forDestination(host: String, now: Long): Plan {
        if (host.isBlank() || routes.none { affected(it, host, now) }) return this
        // Stable-rank real leaves before the dial limit, keeping built-ins in their ordinary slots.
        val proxies = routes.filter { it.leaf !in BUILTIN_GROUP_TARGET_TAGS }
            .map { it to destinationRank(it, host, now) }.sortedBy { it.second }.iterator()
        return copy(routes = routes.map { if (it.leaf in BUILTIN_GROUP_TARGET_TAGS) it else proxies.next().first })
    }

    private fun eventRoute(groupTag: String, owner: Any?, leaf: String): Route? {
        if (owner !is Plan || owner.tag != groupTag) return null
        val route = owner.routes.firstOrNull { it.leaf == leaf } ?: return null
        return currentRoute(route)
    }

    private fun hasCurrentOwners(route: Route): Boolean = route.steps.all {
        groups[it.owner.config.tag] === it.owner && it.owner.eventVersion == it.eventGeneration
    } && (route.front == null || hasCurrentOwners(route.front))

    private fun currentRoute(route: Route): Route? = if (hasCurrentOwners(route))
        route.withSelectionVersions { it.selectionVersion } else null

    private fun synchronizeFallbackSelections() {
        groups.values.filter { it.config.strategy == GroupStrategy.FALLBACK }.forEach { group ->
            val next = orderedCandidates(group, emptySet()).firstOrNull() ?: return@forEach
            val primary = primary(group)?.member
            if (next.member != (group.selected ?: primary) ||
                (next.member != primary && group.recoveryJob == null && scope != null)
            ) selectFallback(group, next.member)
        }
    }

    private fun publish() {
        synchronizeFallbackSelections()
        val active = mutableMapOf<String, String>()
        val fallbacks = mutableMapOf<String, FallbackRuntimeInfo>()
        val latencies = mutableMapOf<String, Sample>()
        groups.values.forEach { group ->
            val tag = group.config.tag
            val route = plan(tag).route
            route?.let { active[tag] = it.leaf }
            if (group.config.strategy == GroupStrategy.FALLBACK) {
                fallbacks[tag] = FallbackRuntimeInfo(
                    inFallback = route?.steps?.firstOrNull()?.member != primary(group)?.member,
                    nextRecoveryAtElapsedMs = group.recoveryAt,
                    activeLeaf = route?.leaf,
                )
            }
            group.samples.values.forEach { sample ->
                val leaf = sample.route.leaf
                if (sample.completedAt >= (latencies[leaf]?.completedAt ?: Long.MIN_VALUE)) latencies[leaf] = sample
            }
        }
        publishStatus(active, fallbacks, latencies.mapNotNull { (leaf, sample) ->
            sample.delayMs?.let { leaf to it }
        }.toMap())
    }

    private fun nodeFronts(workspace: GroupWorkspace): Map<String, GroupMemberRef?> = buildMap {
        workspace.groups.forEach { group ->
            group.nodes.forEach { node -> if (containsKey(node.tag) == false) put(node.tag, group.frontProxy) }
        }
    }
}
