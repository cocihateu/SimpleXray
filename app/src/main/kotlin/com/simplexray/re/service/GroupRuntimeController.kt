package com.simplexray.re.service

import android.os.SystemClock
import com.simplexray.re.common.config.BUILTIN_GROUP_TARGET_TAGS
import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.ProxyGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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
) {
    private data class Step(val groupTag: String, val member: GroupMemberRef)

    private data class Route(
        val leaf: String,
        val steps: List<Step> = emptyList(),
        val front: Route? = null,
    )

    private data class Candidate(val member: GroupMemberRef, val route: Route)
    private data class Sample(val route: Route, val startedAt: Long, val completedAt: Long, val delayMs: Long?)
    private data class Branch(
        val group: Group,
        val current: Candidate,
        val backups: List<Candidate>,
        val preferred: List<Candidate>,
    )
    private data class Plan(val route: Route?, val branches: List<Branch>, val generation: Long)

    private class Group(val config: ProxyGroup) {
        val members = config.effectiveMembers().distinct()
        val samples = mutableMapOf<GroupMemberRef, Sample>()
        var selected = if (config.strategy == GroupStrategy.SELECT) config.selectedMember else null
        var manual = false
        var selectionVersion = 0L
        var replyVersion = 0L
        var checking: Candidate? = null
        var urlJob: Job? = null
        var recoveryJob: Job? = null
        var recoveryAt = 0L
    }

    private data class FailureCheck(
        val branch: Branch,
        val replyVersion: Long,
    )

    private data class ManualMeasurement(val route: Route, val groups: Map<String, Group>, val startedAt: Long)

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
            plans.keys.retainAll(groups.keys)
            if (startHealthChecks) groups.values.forEach(::startUrlTest)
            publish()
        }
    }

    fun stop() {
        synchronized(lock) {
            scope = null
            groups.values.forEach(::stopJobs)
        }
    }

    private fun stopJobs(group: Group) {
        group.urlJob?.cancel()
        group.urlJob = null
        group.recoveryJob?.cancel()
        group.recoveryJob = null
    }

    fun buildFailoverPlan(groupTag: String): FailoverPlan = synchronized(lock) {
        val plan = plan(groupTag)
        FailoverPlan(
            primaryLeaf = plan.route?.leaf,
            candidates = (listOfNotNull(plan.route?.leaf) + plan.branches.flatMap { branch ->
                (branch.backups + branch.preferred).map { it.route.leaf }
            }).distinct(),
            failoverTimeoutMs = plan.branches.firstOrNull()?.group?.let(::fallbackTimeout) ?: 3_000,
            watchResponse = plan.branches.isNotEmpty(),
            generation = plan.generation,
        )
    }

    fun onCandidateConnected(groupTag: String, leaf: String, generation: Long) {
        synchronized(lock) {
            val route = eventPlan(groupTag, generation)?.route?.takeIf { it.leaf == leaf } ?: return
            route.steps.forEach { step -> groups.getValue(step.groupTag).replyVersion++ }
        }
    }

    suspend fun onCandidateFailed(groupTag: String, leaf: String, generation: Long) {
        synchronized(lock) { scope }?.launch { corroborateFailure(groupTag, leaf, generation) }
    }

    suspend fun onStalledUpload(
        groupTag: String,
        leaf: String,
        generation: Long,
        applyIfWaiting: (() -> Unit) -> Boolean,
    ) {
        val canRetry = synchronized(lock) {
            val current = plan(groupTag)
            current.route?.let { it.leaf != leaf || current.branches.isNotEmpty() } == true
        }
        // The relay retries this exchange while the group verifies the active path.
        if (canRetry && applyIfWaiting {}) onCandidateFailed(groupTag, leaf, generation)
    }

    private suspend fun corroborateFailure(
        groupTag: String,
        leaf: String,
        generation: Long,
    ) {
        val (plan, checks) = synchronized(lock) {
            val plan = eventPlan(groupTag, generation)?.takeIf { it.route?.leaf == leaf } ?: return
            plan to plan.branches.filter { it.backups.isNotEmpty() }.map { FailureCheck(it, it.group.replyVersion) }
        }
        for (check in checks) {
            val (group, current, backups) = check.branch
            val claimed = synchronized(lock) {
                if (plan(groupTag) == plan && group.checking != current) {
                    group.checking = current
                    true
                } else false
            }
            if (claimed == false) return
            try {
                val sample = measure(group, current.route, fallbackTimeout(group))
                fun currentStillFailed(): Boolean {
                    val latest = group.samples[current.member]
                    return plan(groupTag) == plan && group.replyVersion == check.replyVersion &&
                        (latest?.delayMs == null || latest.startedAt <= sample.startedAt)
                }
                val confirmed = synchronized(lock) {
                    if (plan(groupTag) != plan) return
                    saveSample(group, current, sample)
                    sample.delayMs == null && currentStillFailed()
                }
                if (confirmed) {
                    val backup = firstReachable(group, backups) ?: continue
                    synchronized(lock) {
                        if (currentStillFailed()) switch(group, current, backup)
                    }
                    return
                }
            } finally {
                synchronized(lock) {
                    if (group.checking == current) group.checking = null
                }
            }
        }
    }

    private suspend fun firstReachable(group: Group, candidates: List<Candidate>): Candidate? = coroutineScope {
        val timeoutMs = fallbackTimeout(group)
        val requests = candidates.map { alternative ->
            async { alternative to measure(group, alternative.route, timeoutMs) }
        }
        try {
            for (request in requests) {
                val (alternative, sample) = request.await()
                if (sample.delayMs != null) return@coroutineScope alternative
            }
            null
        } finally {
            requests.forEach { it.cancel() }
        }
    }

    private fun switch(group: Group, previous: Candidate, backup: Candidate) {
        selectFallback(group, backup.member)
        logEvent("[Warning] ${group.config.tag}: switched from ${previous.displayName()} to ${backup.displayName()}")
        publish()
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
        val intervalMs = group.config.recoveryIntervalSeconds * 1_000L
        group.recoveryAt = nowElapsedRealtimeMs() + intervalMs
        group.recoveryJob = runtimeScope.launch {
            while (true) {
                delay(intervalMs)
                val candidates = synchronized(lock) {
                    group.members.takeWhile { it != member }.mapNotNull(::candidate)
                }
                val restored = firstReachable(group, candidates)
                synchronized(lock) {
                    if (groups[group.config.tag] !== group || group.selected != member) return@launch
                    if (restored != null && candidate(restored.member) == restored) {
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
        group.urlJob = scope?.launch {
            while (true) {
                measureGroup(group)
                delay(group.config.intervalSeconds * 1_000L)
            }
        }
    }

    private suspend fun measureGroup(group: Group) = coroutineScope {
        val (candidates, version) = synchronized(lock) {
            group.members.mapNotNull(::candidate) to group.selectionVersion
        }
        val results = candidates.groupBy { it.route }.map { (route, aliases) ->
            async {
                aliases to measure(group, route, 5_000)
            }
        }.awaitAll()
        synchronized(lock) {
            if (groups[group.config.tag] !== group) return@synchronized
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
                val previous = group.selected?.let(::candidate) ?: primary(group)
                if (group.config.strategy == GroupStrategy.FALLBACK) {
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
            ManualMeasurement(route, groups, nowElapsedRealtimeMs())
        }
        val result = probeNode(attempt.route.leaf, url, timeoutMs)
        synchronized(lock) {
            if (route(member) == attempt.route) {
                groups.values.filter { it.config.testUrl == url && attempt.groups[it.config.tag] === it }
                    .forEach { group ->
                        val measured = group.members.mapNotNull(::candidate)
                            .filter { it.route.leaf == attempt.route.leaf && it.route.front == attempt.route.front }
                        val completedAt = nowElapsedRealtimeMs()
                        measured.forEach { saveSample(group, it, Sample(it.route, attempt.startedAt, completedAt, result)) }
                        if (measured.isNotEmpty() && group.config.strategy == GroupStrategy.URL_TEST) {
                            updateUrlSelection(group, clearManual = false)
                        }
                    }
                publish()
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
                            if (restorePrimary && result != null && primary in measured && group.selected != null) {
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

    private fun selected(group: Group, path: Set<GroupMemberRef>): Candidate? =
        group.selected?.takeIf { it in group.members }?.let { candidate(it, path) }
            ?: group.members.firstNotNullOfOrNull { candidate(it, path) }

    private fun candidate(member: GroupMemberRef): Candidate? = route(member)?.let { Candidate(member, it) }

    private fun candidate(member: GroupMemberRef, path: Set<GroupMemberRef>): Candidate? =
        route(member, path)?.let { Candidate(member, it) }

    private fun route(member: GroupMemberRef, path: Set<GroupMemberRef> = emptySet()): Route? {
        if (member in path) return null
        if (member.isGroup) {
            val group = groups[member.tag] ?: return null
            val choice = selected(group, path + member) ?: return null
            return choice.route.copy(steps = listOf(Step(member.tag, choice.member)) + choice.route.steps)
        }
        if (member.tag in BUILTIN_GROUP_TARGET_TAGS || member.tag in nodeFronts) {
            val front = nodeFronts[member.tag]?.let { route(it, path + member) ?: return null }
            return Route(member.tag, front = front)
        }
        return null
    }

    private fun targetRoute(tag: String): Route? =
        if (tag.startsWith(OutboundGroupConfig.PHYSICAL_PREFIX)) {
            route(GroupMemberRef(tag.removePrefix(OutboundGroupConfig.PHYSICAL_PREFIX)))
        } else route(GroupMemberRef(tag, isGroup = true))

    private fun plan(tag: String): Plan {
        val route = targetRoute(tag)
        val branches = if (route == null) emptyList() else route.steps.asReversed().mapNotNull { step ->
            val group = groups.getValue(step.groupTag)
            if (group.config.strategy != GroupStrategy.FALLBACK) return@mapNotNull null
            val current = candidate(step.member) ?: return@mapNotNull null
            val priority = group.members.indexOf(step.member)
            fun alternatives(members: List<GroupMemberRef>): List<Candidate> =
                members.mapNotNull(::candidate).filter { it.route.leaf != route.leaf }.distinctBy { it.route.leaf }
            val backups = alternatives(group.members.drop(priority + 1))
            val preferred = alternatives(group.members.take(priority))
            if (backups.isEmpty() && preferred.isEmpty()) null else Branch(group, current, backups, preferred)
        }
        return plans[tag]?.takeIf { it.route == route && it.branches == branches }
            ?: Plan(route, branches, ++nextGeneration).also { plans[tag] = it }
    }

    private fun eventPlan(tag: String, generation: Long): Plan? =
        plan(tag).takeIf { it.generation == generation }

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
