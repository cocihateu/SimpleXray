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
    private val onDemoted: (String, String) -> Unit = { _, _ -> },
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
    )

    private data class Candidate(val member: GroupMemberRef, val route: Route)
    private data class Sample(val route: Route, val startedAt: Long, val completedAt: Long, val delayMs: Long?)
    private data class RecoveryResult(val candidate: Candidate, val sample: Sample)
    private data class Plan(val routes: List<Route>, val generation: Long) {
        val route: Route? get() = routes.firstOrNull()
    }

    private class Group(val config: ProxyGroup) {
        val members = config.effectiveMembers().distinct()
        val samples = mutableMapOf<GroupMemberRef, Sample>()
        var selected = if (config.strategy == GroupStrategy.SELECT) config.selectedMember else null
        var manual = false
        var selectionVersion = 0L
        var eventVersion = 0L
        val failures = mutableMapOf<GroupMemberRef, ProxyFailure>()
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
        var pendingCheck = false
        var checkTimer: Job? = null
        var checkGeneration = 0L
    }

    private data class ManualMeasurement(
        val route: Route,
        val groups: Map<String, Pair<Group, Long>>,
        val startedAt: Long,
    )

    private val lock = Any()
    private val measurementSlots = Semaphore(6)
    private var groups = initialWorkspace.groups.associate { it.tag to Group(it) }
    private var nodeFronts = nodeFronts(initialWorkspace)
    private val plans = mutableMapOf<String, Plan>()
    private var nextGeneration = 0L
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
        group.urlGeneration++
        group.urlJob?.cancel()
        group.urlJob = null
        group.recoveryJob?.cancel()
        group.recoveryJob = null
    }

    fun buildFailoverPlan(groupTag: String): FailoverPlan = synchronized(lock) {
        val plan = plan(groupTag)
        val routes = plan.routes.take(FallbackPolicy.MAX_DIAL_ATTEMPTS)
        val fallback = plan.route?.steps?.asReversed()?.firstOrNull {
            it.owner.config.strategy == GroupStrategy.FALLBACK
        }?.owner
        FailoverPlan(
            primaryLeaf = plan.route?.leaf,
            candidates = routes.map { it.leaf },
            failoverTimeoutMs = fallback?.let(::fallbackTimeout) ?: 3_000,
            watchResponse = fallback != null,
            generation = plan.generation,
            owner = plan,
            stallTimeoutMs = FallbackPolicy.stallTimeoutMs(plan.route?.let(::routeLatency)),
            stallTimeoutsMs = routes.associate { it.leaf to FallbackPolicy.stallTimeoutMs(routeLatency(it)) },
            staggerDelaysMs = routes.associate { it.leaf to FallbackPolicy.staggerDelayMs(routeLatency(it)) },
            groupPaths = routes.associate { it.leaf to it.steps.map { step -> step.owner.config.tag }.toSet() },
        )
    }

    fun onCandidateConnected(groupTag: String, leaf: String, owner: Any?) {
        synchronized(lock) {
            val route = eventRoute(owner, leaf) ?: return
            val now = nowElapsedRealtimeMs()
            for ((group, candidate) in routeMembers(route)) {
                val state = group.failures[candidate.member]?.takeIf { ownsFailure(group, it) } ?: continue
                if (now < state.cooldownUntil) continue
                state.suspectHosts.clear()
                if (state.backoffLevel > 0 && state.cooldownUntil > 0 &&
                    now - state.cooldownUntil >= FallbackPolicy.RECOVERY_STABLE_MS
                ) {
                    state.backoffLevel--
                    state.cooldownUntil = now
                }
            }
        }
    }

    suspend fun onCandidateFailed(
        groupTag: String,
        leaf: String,
        owner: Any?,
        host: String,
        strong: Boolean,
    ) {
        synchronized(lock) {
            val route = eventRoute(owner, leaf) ?: return
            for ((group, candidate) in routeMembers(route)) {
                reportSuspect(group, candidate, host, strong)
            }
        }
    }

    fun onDialResult(groupTag: String, winner: String?, failed: List<String>, owner: Any?) {
        synchronized(lock) {
            if (winner == null) {
                logEvent("[Info] $groupTag: all connection candidates failed; retaining cooldown levels.")
                return
            }
            val winningRoute = eventRoute(owner, winner) ?: return
            for (leaf in failed) {
                val route = eventRoute(owner, leaf) ?: continue
                for ((group, candidate) in routeMembers(route)) {
                    if (winningRoute.steps.any { it.owner === group && it.member == candidate.member }) continue
                    failureState(group, candidate)?.let {
                        demote(group, it, "connection failed while $winner connected")
                    }
                }
            }
        }
    }

    suspend fun onStalledUpload(
        groupTag: String,
        leaf: String,
        owner: Any?,
        host: String,
        applyIfWaiting: (() -> Unit) -> Boolean,
    ) {
        synchronized(lock) {
            val route = eventRoute(owner, leaf) ?: return
            if (applyIfWaiting {}) {
                for ((group, candidate) in routeMembers(route)) {
                    reportSuspect(group, candidate, host, strong = true)
                }
            }
        }
    }

    private fun routeMembers(route: Route): List<Pair<Group, Candidate>> =
        route.steps.mapIndexedNotNull { index, step ->
            if (step.owner.config.strategy != GroupStrategy.FALLBACK || route.leaf in BUILTIN_GROUP_TARGET_TAGS) {
                null
            } else {
                step.owner to Candidate(step.member, route.copy(steps = route.steps.drop(index + 1)))
            }
        }.asReversed()

    private fun failureState(group: Group, candidate: Candidate): ProxyFailure? {
        if (groups[group.config.tag] !== group || candidate.member !in group.members || currentRoute(candidate.route) == null ||
            candidate.route.leaf in BUILTIN_GROUP_TARGET_TAGS
        ) return null
        val previous = group.failures[candidate.member]
        if (previous != null && ownsFailure(group, previous)) {
            if (previous.candidate != candidate) {
                stopFailure(previous)
                previous.candidate = candidate
            }
            return previous
        }
        previous?.let(::stopFailure)
        return ProxyFailure(candidate).also { group.failures[candidate.member] = it }
    }

    private fun ownsFailure(group: Group, state: ProxyFailure): Boolean =
        groups[group.config.tag] === group && group.failures[state.candidate.member] === state &&
            state.candidate.member in group.members && currentRoute(state.candidate.route) != null

    private fun stopCheckTimer(state: ProxyFailure) {
        state.checkGeneration++
        state.checkTimer?.cancel()
        state.checkTimer = null
        state.pendingCheck = false
    }

    private fun stopFailure(state: ProxyFailure) {
        stopCheckTimer(state)
        state.checkJob?.cancel()
        state.checkJob = null
    }

    private fun reportSuspect(group: Group, candidate: Candidate, host: String, strong: Boolean) {
        val state = failureState(group, candidate) ?: return
        val now = nowElapsedRealtimeMs()
        if (now < state.cooldownUntil) return
        if (strong) {
            state.suspectHosts.entries.removeAll { now - it.value > FallbackPolicy.HOST_WINDOW_MS }
            state.suspectHosts[host.lowercase().ifBlank { "_unknown_" }] = now
            if (state.suspectHosts.size >= FallbackPolicy.HOST_THRESHOLD) {
                demote(group, state, "three distinct destinations stalled or failed within 60 seconds")
                return
            }
        }
        val gap = if (strong) FallbackPolicy.STRONG_CHECK_GAP_MS else FallbackPolicy.WEAK_CHECK_GAP_MS
        when {
            state.checkJob != null -> if (strong) state.pendingCheck = true
            state.lastCheckAt == null || now - requireNotNull(state.lastCheckAt) >= gap -> startComparativeCheck(group, state)
            strong && state.checkTimer == null -> scheduleCheck(group, state, gap - (now - requireNotNull(state.lastCheckAt)))
        }
    }

    private fun scheduleCheck(group: Group, state: ProxyFailure, waitMs: Long) {
        val runtimeScope = scope ?: return
        val generation = ++state.checkGeneration
        state.checkTimer = runtimeScope.launch(start = CoroutineStart.LAZY) {
            delay(waitMs)
            synchronized(lock) {
                if (ownsFailure(group, state) == false || state.checkGeneration != generation) return@launch
                state.checkTimer = null
                if (nowElapsedRealtimeMs() < state.cooldownUntil) {
                    state.pendingCheck = false
                } else if (state.checkJob != null) {
                    state.pendingCheck = true
                } else {
                    startComparativeCheck(group, state)
                }
            }
        }
        state.checkTimer?.start()
    }

    private fun startComparativeCheck(group: Group, state: ProxyFailure) {
        val runtimeScope = scope ?: return
        stopCheckTimer(state)
        val generation = state.checkGeneration
        val current = state.candidate
        val peers = orderedCandidates(group, emptySet()).filter {
            it.member != current.member && it.route.leaf != current.route.leaf && it.route.leaf !in BUILTIN_GROUP_TARGET_TAGS
        }
        val peer = peers.firstOrNull { isDemoted(group, it) == false && isAlive(group, it) }
            ?: peers.firstOrNull { isDemoted(group, it) == false } ?: peers.firstOrNull()
        state.lastCheckAt = nowElapsedRealtimeMs()
        val timeoutMs = FallbackPolicy.stallTimeoutMs(group.samples[current.member]?.delayMs)
        lateinit var job: Job
        job = runtimeScope.launch(start = CoroutineStart.LAZY) {
            try {
                val startedAt = nowElapsedRealtimeMs()
                val measured = coroutineScope {
                    suspend fun bounded(candidate: Candidate): Sample =
                        withTimeoutOrNull(timeoutMs.toLong()) { measure(group, candidate.route, timeoutMs) }
                            ?: Sample(candidate.route, startedAt, nowElapsedRealtimeMs(), null)
                    val selfResult = async { bounded(current) }
                    val peerResult = peer?.let { async { bounded(it) } }
                    selfResult.await() to peerResult?.await()
                }
                synchronized(lock) {
                    if (ownsFailure(group, state) == false || state.checkGeneration != generation) return@launch
                    val selfSample = measured.first
                    saveSample(group, current, selfSample)
                    val peerSample = measured.second
                    if (peer != null && candidate(peer.member) == peer && peerSample != null) {
                        saveSample(group, peer, peerSample)
                        if (selfSample.delayMs == null && peerSample.delayMs != null &&
                            group.samples[current.member] === selfSample && group.samples[peer.member] === peerSample
                        ) {
                            demote(group, state, "health URL failed while ${peer.displayName()} succeeded")
                        }
                    }
                    publish()
                }
            } finally {
                synchronized(lock) {
                    if (state.checkJob === job) {
                        state.checkJob = null
                        if (ownsFailure(group, state) && state.checkGeneration == generation &&
                            state.pendingCheck && state.checkTimer == null && nowElapsedRealtimeMs() >= state.cooldownUntil
                        ) {
                            state.pendingCheck = false
                            scheduleCheck(group, state, FallbackPolicy.STRONG_CHECK_GAP_MS)
                        }
                    }
                }
            }
        }
        state.checkJob = job
        job.start()
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
        onDemoted(group.config.tag, state.candidate.route.leaf)
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
                    if (groups[group.config.tag] === group && group.selectionVersion == generation &&
                        candidate(alternative.member) == alternative
                    ) {
                        saveSample(group, alternative, sample)
                        sample.delayMs != null && group.samples[alternative.member] === sample &&
                            isDemoted(group, alternative) == false
                    } else false
                }
                if (accepted) return@coroutineScope RecoveryResult(alternative, sample)
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
                    val restored = result?.candidate
                    if (result != null && restored != null && candidate(restored.member) == restored && isDemoted(group, restored) == false &&
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
                aliases.filter { candidate(it.member) == it }.forEach { saveSample(group, it, sample) }
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
                ?.takeIf { it.route == candidate.route && now - it.completedAt <= validForMs }
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
        val startedAt = nowElapsedRealtimeMs()
        val latency = probeNode(route.leaf, group.config.testUrl, timeoutMs)
        Sample(route, startedAt, nowElapsedRealtimeMs(), latency)
    }

    private fun saveSample(group: Group, candidate: Candidate, sample: Sample) {
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
            )
        }
        val result = probeNode(attempt.route.leaf, url, timeoutMs)
        synchronized(lock) {
            if (route(member) == attempt.route) {
                val currentGroups = groups.values.filter { group ->
                    val (owner, generation) = attempt.groups[group.config.tag] ?: return@filter false
                    group.config.testUrl == url && owner === group && generation == group.urlGeneration
                }
                currentGroups.forEach { group ->
                    val measured = group.members.mapNotNull(::candidate)
                        .filter { it.route.leaf == attempt.route.leaf && it.route.front == attempt.route.front }
                    val completedAt = nowElapsedRealtimeMs()
                    measured.forEach { saveSample(group, it, Sample(it.route, attempt.startedAt, completedAt, result)) }
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
        group.samples[candidate.member]?.takeIf { it.route == candidate.route }?.let { it.delayMs != null } ?: true

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
        group.samples[candidate.member]?.takeIf { it.route == candidate.route }?.delayMs
    }

    private fun plan(tag: String): Plan {
        val member = if (tag.startsWith(OutboundGroupConfig.PHYSICAL_PREFIX)) {
            GroupMemberRef(tag.removePrefix(OutboundGroupConfig.PHYSICAL_PREFIX))
        } else GroupMemberRef(tag, isGroup = true)
        val routes = routes(member, emptySet()).distinctBy { it.leaf }
        return plans[tag]?.takeIf { it.routes == routes }
            ?: Plan(routes, ++nextGeneration).also { plans[tag] = it }
    }

    private fun eventRoute(owner: Any?, leaf: String): Route? {
        val route = (owner as? Plan)?.routes?.firstOrNull { it.leaf == leaf } ?: return null
        return currentRoute(route)
    }

    private fun currentRoute(route: Route): Route? {
        if (route.steps.any {
                groups[it.owner.config.tag] !== it.owner || it.owner.eventVersion != it.eventGeneration
            }
        ) return null
        val front = route.front?.let { currentRoute(it) ?: return null }
        return route.copy(steps = route.steps.map { it.copy(generation = it.owner.selectionVersion) }, front = front)
    }

    private fun publish() {
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
