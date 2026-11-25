package com.simplexray.re.service

import com.simplexray.re.common.config.GroupMemberRef
import com.simplexray.re.common.config.GroupStrategy
import com.simplexray.re.common.config.GroupWorkspace
import com.simplexray.re.common.config.ProxyGroup
import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupRuntimeControllerTest {
    private class Clock {
        private data class Sleeper(val due: Long, val ready: CompletableDeferred<Unit>)
        var now = 10_000L
        private val sleepers = mutableListOf<Sleeper>()

        suspend fun sleep(duration: Long) {
            if (duration <= 0L) return
            val sleeper = Sleeper(now + duration, CompletableDeferred())
            sleepers += sleeper
            try { sleeper.ready.await() } finally { sleepers.remove(sleeper) }
        }

        suspend fun advanceBy(duration: Long) {
            val target = now + duration
            while (true) {
                val next = sleepers.filter { it.due <= target }.minByOrNull { it.due } ?: break
                now = next.due
                sleepers.remove(next)
                next.ready.complete(Unit)
                yield()
            }
            now = target
            yield()
        }
    }

    private class Fixture(var workspace: GroupWorkspace) : Closeable {
        val clock = Clock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var probe: suspend (String, String, Int) -> Long? = { _, _, _ -> null }
        val probes = mutableListOf<Pair<String, String>>()
        var fallback: Map<String, FallbackRuntimeInfo> = emptyMap()
        var active: Map<String, String> = emptyMap()
        val controller = GroupRuntimeController(
            initialWorkspace = workspace,
            probeNode = { leaf, url, timeout ->
                assertEquals(5_000, timeout)
                probes += leaf to url
                probe(leaf, url, timeout)
            },
            publishStatus = { nodes, states, _ -> active = nodes; fallback = states },
            logEvent = {},
            elapsedRealtime = { clock.now },
            dispatcher = Dispatchers.Unconfined,
            sleep = clock::sleep,
        )

        suspend fun start() = controller.start(scope)
        fun plan(tag: String = "group") = controller.buildFailoverPlan(tag)
        suspend fun fail(tag: String = "group", leaf: String = plan(tag).candidates.first()) =
            controller.onCandidateFailed(tag, leaf, plan(tag).generation)
        fun respond(tag: String = "group", leaf: String = plan(tag).candidates.first()) =
            controller.onCandidateConnected(tag, leaf, plan(tag).generation)
        suspend fun enterFallback(tag: String = "group") { fail(tag); respond(tag) }
        fun update(group: ProxyGroup) {
            workspace = workspace.copy(groups = workspace.groups.map { if (it.tag == group.tag) group else it })
            controller.updateWorkspace(workspace)
        }
        override fun close() { controller.stop(); scope.cancel() }
    }

    private fun fallback(tag: String = "group", vararg nodes: String) = ProxyGroup(
        tag = tag,
        members = (nodes.toList().ifEmpty { listOf("primary", "backup", "backup2") }).map { GroupMemberRef(it) },
        recoveryIntervalSeconds = 10,
        testUrl = CONFIGURED,
    )

    private fun fixture(vararg groups: ProxyGroup) = Fixture(GroupWorkspace(groups.toList().ifEmpty { listOf(fallback()) }))

    @Test fun primaryIsNotPeriodicallyProbed() = runBlocking {
        fixture().use { f ->
            f.start()
            f.clock.advanceBy(120_000)
            assertTrue(f.probes.isEmpty())
            assertEquals(listOf("primary"), f.plan().candidates)
            assertTrue(f.fallback.isEmpty())
        }
    }

    @Test fun slowDestinationWithHealthyNodeDoesNotSwitch() = runBlocking {
        fixture().use { f ->
            f.start()
            f.probe = { _, url, _ -> if (url == CONFIGURED) null else 20L }
            var rejected = false
            f.controller.onStalledUpload("group", "primary", f.plan().generation) { action ->
                rejected = true; action(); true
            }
            assertFalse(rejected)
            assertEquals(listOf("primary"), f.plan().candidates)
            assertTrue(f.fallback.isEmpty())
            assertEquals(setOf(CONFIGURED, ALTERNATE), f.probes.map { it.second }.toSet())
        }
    }

    @Test fun independentSuccessCancelsOtherProbeWithoutWaitingForItsTimeout() = runBlocking {
        fixture().use { f ->
            val pending = CompletableDeferred<Long?>()
            var cancelled = false
            f.probe = { _, url, _ ->
                if (url == CONFIGURED) try { pending.await() } finally { cancelled = true } else 8L
            }
            f.fail()
            assertTrue(cancelled)
            assertTrue(f.fallback.isEmpty())
        }
    }

    @Test fun alternateUsesDifferentHostEvenWithConfiguredAlternatePath() = runBlocking {
        val custom = "https://cp.cloudflare.com/other-check"
        fixture(fallback().copy(testUrl = custom)).use { f ->
            f.fail()
            assertEquals(setOf(custom, DEFAULT), f.probes.map { it.second }.toSet())
        }
    }

    @Test fun backupResponseStartsDeadlineAndRepeatedResponsesDoNotPostponeIt() = runBlocking {
        fixture().use { f ->
            f.start()
            f.fail()
            assertEquals(0L, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
            val checks = f.probes.size
            f.clock.advanceBy(30_000)
            assertEquals(checks, f.probes.size)
            f.respond()
            val deadline = f.clock.now + 10_000
            assertEquals(deadline, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
            f.clock.advanceBy(4_000)
            f.respond()
            f.respond()
            assertEquals(deadline, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
        }
    }

    @Test fun scheduledRecoveryAcceptsAlternateAndDoesNotNeedMoreBusinessTraffic() = runBlocking {
        fixture().use { f ->
            f.start()
            f.enterFallback()
            val oldBackup = f.plan()
            f.probe = { _, url, _ -> if (url == ALTERNATE) 9L else null }
            f.clock.advanceBy(10_000)
            assertTrue(f.fallback.isEmpty())
            assertEquals(listOf("primary"), f.plan().candidates)
            assertNotEquals(oldBackup.generation, f.plan().generation)
            f.controller.onCandidateConnected("group", "backup", oldBackup.generation)
            assertTrue(f.fallback.isEmpty())
        }
    }

    @Test fun failedRecoverySchedulesNextIntervalFromCompletionAndExceptionsDoNotKillLoop() = runBlocking {
        fixture().use { f ->
            f.start()
            f.enterFallback()
            val pending = CompletableDeferred<Long?>()
            f.probe = { _, _, _ -> pending.await() }
            f.clock.advanceBy(10_000)
            f.clock.advanceBy(2_000)
            pending.complete(null)
            yield()
            assertEquals(f.clock.now + 10_000, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
            f.probe = { _, _, _ -> throw IOException("measurement failed") }
            f.clock.advanceBy(10_000)
            assertEquals(f.clock.now + 10_000, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
            f.probe = { _, _, _ -> 6L }
            f.clock.advanceBy(10_000)
            assertTrue(f.fallback.isEmpty())
        }
    }

    @Test fun failedBackupProgressesWithoutResettingOriginalDeadline() = runBlocking {
        fixture().use { f ->
            f.start()
            f.enterFallback()
            val deadline = f.fallback.getValue("group").nextRecoveryAtElapsedMs
            f.clock.advanceBy(3_000)
            f.fail()
            assertEquals("backup2", f.plan().candidates.first())
            f.respond()
            assertEquals(deadline, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
            assertEquals("backup2", f.fallback.getValue("group").activeLeaf)
        }
    }

    @Test fun concurrentStallsShareOneCheckAndReleaseBothWaitingStreams() = runBlocking {
        fixture().use { f ->
            val pending = CompletableDeferred<Long?>()
            f.probe = { _, _, _ -> pending.await() }
            val generation = f.plan().generation
            var released = 0
            suspend fun report() = f.controller.onStalledUpload("group", "primary", generation) { action ->
                action(); released++; true
            }
            val first = f.scope.async { report() }
            val second = f.scope.async { report() }
            assertEquals(2, f.probes.size)
            pending.complete(null)
            first.await(); second.await()
            assertEquals(2, released)
            assertEquals(generation + 1L, f.plan().generation)
            assertEquals("backup", f.plan().candidates.first())
            assertEquals(0L, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
        }
    }

    @Test fun responseArrivingDuringFailedCheckPreventsFalseSwitch() = runBlocking {
        fixture().use { f ->
            val pending = CompletableDeferred<Long?>()
            f.probe = { _, _, _ -> pending.await() }
            val check = f.scope.async { f.fail() }
            f.respond()
            pending.complete(null)
            check.await()
            assertTrue(f.fallback.isEmpty())
            assertEquals("primary", f.plan().candidates.first())
        }
    }

    @Test fun stalledCheckMustStillOwnPendingResponseWhenItCompletes() = runBlocking {
        fixture().use { f ->
            var applied = false
            f.controller.onStalledUpload("group", "primary", f.plan().generation) { _ ->
                applied = true
                false
            }
            assertTrue(applied)
            assertTrue(f.fallback.isEmpty())
        }
    }

    @Test fun staleFailureAfterManualSwitchCannotClobberSelection() = runBlocking {
        fixture().use { f ->
            val pending = CompletableDeferred<Long?>()
            f.probe = { _, _, _ -> pending.await() }
            val before = f.plan()
            var applyCalled = false
            val check = f.scope.async {
                f.controller.onStalledUpload("group", "primary", before.generation) { action ->
                    applyCalled = true; action(); true
                }
            }
            f.controller.forceSwitchMember("group", GroupMemberRef("backup2"))
            pending.complete(null)
            check.await()
            assertTrue(applyCalled)
            assertEquals("backup2", f.plan().candidates.first())
            assertEquals(0L, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
        }
    }

    @Test fun oldGenerationPrimaryThatLaterDiesCanEndWithoutChangingNewSelection() = runBlocking {
        fixture().use { f ->
            f.start()
            val old = f.plan()
            f.respond(leaf = "primary")
            f.controller.forceSwitchMember("group", GroupMemberRef("backup2"))
            f.respond()
            val current = f.plan()
            val state = f.fallback.getValue("group")
            var closed = false
            f.controller.onStalledUpload("group", "primary", old.generation) { action ->
                action(); closed = true; true
            }
            assertTrue(closed)
            assertEquals(current, f.plan())
            assertEquals(state, f.fallback.getValue("group"))
        }
    }

    @Test fun manualSwitchWithHealthyOldNodeNeverClosesItsStream() = runBlocking {
        fixture().use { f ->
            val old = f.plan()
            f.controller.forceSwitchMember("group", GroupMemberRef("backup2"))
            val current = f.plan()
            f.probe = { _, url, _ -> if (url == ALTERNATE) 7L else null }
            var closed = false
            f.controller.onStalledUpload("group", "primary", old.generation) { action ->
                action(); closed = true; true
            }
            assertFalse(closed)
            assertEquals(current, f.plan())
            assertEquals(0L, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
        }
    }

    @Test fun staleSuccessfulRecoveryCannotOverrideManualPrimarySelection() = runBlocking {
        fixture().use { f ->
            f.start()
            f.enterFallback()
            val pending = CompletableDeferred<Long?>()
            f.probe = { _, _, _ -> pending.await() }
            f.clock.advanceBy(10_000)
            f.controller.forceSwitchMember("group", GroupMemberRef("primary"))
            pending.complete(2L)
            yield()
            assertTrue(f.fallback.isEmpty())
            assertEquals("primary", f.plan().candidates.first())
        }
    }

    @Test fun staleSuccessDoesNotRemoveCurrentUrlTestOverride() = runBlocking {
        val urlTest = fallback().copy(strategy = GroupStrategy.URL_TEST)
        fixture(urlTest).use { f ->
            val pending = CompletableDeferred<Long?>()
            f.probe = { _, _, _ -> pending.await() }
            val check = f.scope.async { f.fail() }
            f.controller.forceSwitchMember("group", GroupMemberRef("backup2"))
            pending.complete(1L)
            check.await()
            assertEquals("backup2", f.plan().candidates.first())
        }
    }

    @Test fun unrelatedGroupSwitchDoesNotInvalidateFirstBackupResponse() = runBlocking {
        val other = fallback("other", "x", "y").copy(strategy = GroupStrategy.SELECT)
        fixture(fallback(), other).use { f ->
            f.start()
            f.fail()
            val backupPlan = f.plan()
            f.controller.forceSwitchMember("other", GroupMemberRef("y"))
            assertEquals(backupPlan.generation, f.plan().generation)
            f.controller.onCandidateConnected("group", "backup", backupPlan.generation)
            assertEquals(f.clock.now + 10_000, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
        }
    }

    @Test fun delegationKeepsOriginallyRequestedEpoch() = runBlocking {
        val inner = fallback("inner")
        val outer = ProxyGroup("outer", strategy = GroupStrategy.SELECT,
            members = listOf(GroupMemberRef("inner", true), GroupMemberRef("direct")))
        fixture(inner, outer).use { f ->
            f.controller.forceSwitchMember("outer", GroupMemberRef("direct"))
            f.controller.forceSwitchMember("outer", GroupMemberRef("inner", true))
            assertNotEquals(f.plan("inner").generation, f.plan("outer").generation)
            val before = f.plan("outer")
            f.controller.onCandidateFailed("outer", "primary", before.generation)
            assertEquals("backup", f.plan("outer").candidates.first())
            assertNotEquals(before.generation, f.plan("outer").generation)
            f.respond("outer")
            assertTrue(f.fallback.getValue("inner").nextRecoveryAtElapsedMs > 0L)
        }
    }

    @Test fun nestedFallbackExhaustsInnerAlternativesThenRecoversLeavesOutward() = runBlocking {
        val inner = fallback("inner", "a", "b")
        val outer = fallback("outer", "unused").copy(members = listOf(GroupMemberRef("inner", true), GroupMemberRef("c")))
        fixture(inner, outer).use { f ->
            f.start()
            assertEquals(listOf("a"), f.plan("outer").candidates)
            f.fail("outer")
            assertEquals(listOf("b"), f.plan("outer").candidates)
            assertNull(f.fallback["outer"])
            f.fail("outer")
            assertEquals(listOf("c"), f.plan("outer").candidates)
            f.respond("outer")
            f.probes.clear()
            f.probe = { leaf, _, _ -> if (leaf == "a") 3L else null }
            f.clock.advanceBy(10_000)
            assertEquals(setOf("a"), f.probes.map { it.first }.toSet())
            assertTrue(f.fallback.isEmpty())
            assertEquals(listOf("a"), f.plan("outer").candidates)
        }
    }

    @Test fun nestedRecoveryDoesNotLeaveAncestorWithStaleFailedLeafMask() = runBlocking {
        val inner = fallback("inner", "a", "b")
        val outer = fallback("outer").copy(members = listOf(GroupMemberRef("inner", true), GroupMemberRef("c")))
        fixture(inner, outer).use { f ->
            f.start()
            f.fail("outer", "a")
            f.respond("outer", "b")
            assertNull(f.fallback["outer"])
            f.probe = { leaf, _, _ -> if (leaf == "a") 3L else null }
            f.clock.advanceBy(10_000)
            assertTrue(f.fallback.isEmpty())
            assertEquals(listOf("a"), f.plan("outer").candidates)
            assertEquals(listOf("a"), f.plan("inner").candidates)
        }
    }

    @Test fun nestedBackupSelectionChangeKeepsAncestorDeadlineAndAcceptsNewLeaf() = runBlocking {
        val inner = fallback("inner", "a", "b")
        val outer = fallback("outer").copy(members = listOf(GroupMemberRef("p"), GroupMemberRef("inner", true)))
        fixture(inner, outer).use { f ->
            f.start()
            f.fail("outer", "p")
            f.respond("outer", "a")
            val deadline = f.fallback.getValue("outer").nextRecoveryAtElapsedMs
            f.clock.advanceBy(2_000)
            // Traffic addressed directly to the inner group changes its leaf too.
            f.fail("inner", "a")
            assertEquals("b", f.plan("outer").candidates.first())
            f.respond("outer", "b")
            assertEquals("b", f.fallback.getValue("outer").activeLeaf)
            assertEquals(deadline, f.fallback.getValue("outer").nextRecoveryAtElapsedMs)
        }
    }

    @Test fun nestedUrlTestAlternativesAreTriedBeforeOuterFallback() = runBlocking {
        val inner = fallback("inner", "a", "b").copy(strategy = GroupStrategy.URL_TEST)
        val outer = fallback("outer").copy(members = listOf(GroupMemberRef("inner", true), GroupMemberRef("c")))
        fixture(inner, outer).use { f ->
            f.fail("outer", "a")
            assertEquals(listOf("b"), f.plan("outer").candidates)
            assertNull(f.fallback["outer"])
            f.fail("outer", "b")
            assertEquals(listOf("c"), f.plan("outer").candidates)
            assertTrue(f.fallback.getValue("outer").inFallback)
            assertTrue(f.plan("inner").candidates.isNotEmpty())
        }
    }

    @Test fun nestedSelectUsesOnlyItsSelectedPrimaryBranch() = runBlocking {
        val inner = fallback("inner", "a", "b").copy(strategy = GroupStrategy.SELECT, selectedMember = GroupMemberRef("b"))
        val outer = fallback("outer").copy(members = listOf(GroupMemberRef("inner", true), GroupMemberRef("c")))
        fixture(inner, outer).use { f ->
            f.start()
            f.enterFallback("outer")
            f.probes.clear()
            f.probe = { _, _, _ -> 2L }
            f.clock.advanceBy(10_000)
            assertEquals(setOf("b"), f.probes.map { it.first }.toSet())
            assertEquals(listOf("b"), f.plan("outer").candidates)
        }
    }

    @Test fun oldBackupLateDataCannotStartANewFallbackCycle() = runBlocking {
        fixture().use { f ->
            f.start()
            f.enterFallback()
            val old = f.plan()
            f.controller.forceSwitchMember("group", GroupMemberRef("primary"))
            f.fail()
            f.controller.onCandidateConnected("group", "backup", old.generation)
            assertEquals(0L, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
            f.respond()
            assertEquals(f.clock.now + 10_000, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
        }
    }

    @Test fun recoveryIntervalChangeUsesOriginalCountdownOrigin() = runBlocking {
        fixture().use { f ->
            f.start()
            f.enterFallback()
            val origin = f.clock.now
            f.clock.advanceBy(3_000)
            f.update(f.workspace.groups.first().copy(recoveryIntervalSeconds = 20))
            assertEquals(origin + 20_000, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
            f.probe = { _, _, _ -> 1L }
            f.clock.advanceBy(16_000)
            assertTrue(f.fallback.getValue("group").inFallback)
            f.clock.advanceBy(1_000)
            assertTrue(f.fallback.isEmpty())
        }
    }

    @Test fun topologyReplacementRejectsOldCallbacksAndChecks() = runBlocking {
        fixture().use { f ->
            val pending = CompletableDeferred<Long?>()
            f.probe = { _, _, _ -> pending.await() }
            val old = f.plan()
            val check = f.scope.async { f.fail() }
            f.update(fallback("group", "replacement", "backup"))
            pending.complete(null)
            check.await()
            f.controller.onCandidateConnected("group", "backup", old.generation)
            assertEquals(listOf("replacement"), f.plan().candidates)
            assertTrue(f.fallback.isEmpty())
        }
    }

    @Test fun staleManualMeasurementCannotRestoreAfterSelectionChange() = runBlocking {
        fixture().use { f ->
            f.enterFallback()
            val epochs = f.controller.measurementGenerations()
            val started = f.clock.now
            f.controller.forceSwitchMember("group", GroupMemberRef("backup2"))
            f.controller.recordMeasurement("primary", CONFIGURED, 1L, started,
                restorePrimary = true, selectionGenerations = epochs)
            assertEquals("backup2", f.plan().candidates.first())
            assertTrue(f.fallback.getValue("group").inFallback)
        }
    }

    @Test fun expiredAndFailedUrlTestSamplesDoNotProduceEmptyPlan() = runBlocking {
        fixture(fallback().copy(strategy = GroupStrategy.URL_TEST, trustSeconds = 5)).use { f ->
            f.controller.recordMeasurement("primary", CONFIGURED, 10L)
            f.controller.recordMeasurement("backup", CONFIGURED, null)
            f.controller.recordMeasurement("backup2", CONFIGURED, null)
            f.clock.advanceBy(6_000)
            assertEquals("primary", f.plan().candidates.first())
            f.controller.recordMeasurement("primary", CONFIGURED, null)
            assertTrue(f.plan().candidates.isNotEmpty())
            assertTrue(f.plan().candidates.size <= 3)
        }
    }

    @Test fun urlTestOverrideLastsUntilScheduledMeasurement() = runBlocking {
        fixture(fallback().copy(strategy = GroupStrategy.URL_TEST, intervalSeconds = 5)).use { f ->
            f.probe = { leaf, _, _ -> if (leaf == "primary") 1L else 50L }
            f.start()
            f.controller.forceSwitchMember("group", GroupMemberRef("backup2"))
            assertEquals("backup2", f.plan().candidates.first())
            f.clock.advanceBy(4_000)
            assertEquals("backup2", f.plan().candidates.first())
            f.clock.advanceBy(1_000)
            assertEquals("primary", f.plan().candidates.first())
        }
    }

    @Test fun rejectTargetIsNeverAHealthyRecoveryProbe() = runBlocking {
        fixture(fallback("group", "block", "backup")).use { f ->
            f.start()
            f.probe = { _, _, _ -> 0L }
            f.enterFallback()
            f.clock.advanceBy(10_000)
            assertTrue(f.fallback.getValue("group").inFallback)
            assertEquals("backup", f.plan().candidates.first())
            assertTrue(f.probes.isEmpty())
        }
    }

    @Test fun probeCancellationPropagatesInsteadOfConfirmingFailure() = runBlocking {
        fixture().use { f ->
            f.probe = { _, _, _ -> throw CancellationException("cancel measurement") }
            val check = f.scope.async { f.fail() }
            try { check.await(); throw AssertionError("Expected cancellation") } catch (_: CancellationException) { }
            assertTrue(f.fallback.isEmpty())
        }
    }

    @Test fun stopCancelsInFlightChecksAndAllTimers() = runBlocking {
        fixture().use { f ->
            f.start()
            f.enterFallback()
            val pending = CompletableDeferred<Long?>()
            var cancelled = 0
            f.probe = { _, _, _ -> try { pending.await() } finally { cancelled++ } }
            f.clock.advanceBy(10_000)
            assertEquals(0, cancelled)
            val before = f.probes.size
            f.controller.stop()
            assertEquals(2, cancelled)
            val snapshot = f.fallback
            f.clock.advanceBy(60_000)
            f.controller.onCandidateConnected("group", "backup", f.plan().generation)
            assertEquals(before, f.probes.size)
            assertEquals(snapshot, f.fallback)
        }
    }

    @Test fun cancellingOneWaiterDoesNotCancelOtherWaitersSharedCheck() = runBlocking {
        fixture().use { f ->
            val pending = CompletableDeferred<Long?>()
            f.probe = { _, _, _ -> pending.await() }
            val generation = f.plan().generation
            val first = f.scope.async(start = CoroutineStart.UNDISPATCHED) {
                f.controller.onCandidateFailed("group", "primary", generation)
            }
            val second = f.scope.async { f.controller.onCandidateFailed("group", "primary", generation) }
            first.cancel()
            assertEquals(2, f.probes.size)
            pending.complete(null)
            second.await()
            assertEquals("backup", f.plan().candidates.first())
        }
    }

    @Test fun newEpochDoesNotReuseInFlightOldEpochFailure() = runBlocking {
        fixture().use { f ->
            val oldResult = CompletableDeferred<Long?>()
            f.probe = { _, _, _ -> oldResult.await() }
            val oldCheck = f.scope.async { f.fail() }
            f.controller.forceSwitchMember("group", GroupMemberRef("primary"))
            f.probe = { _, _, _ -> 3L }
            f.fail()
            oldResult.complete(null)
            oldCheck.await()
            assertEquals(listOf("primary"), f.plan().candidates)
            assertTrue(f.fallback.isEmpty())
            assertTrue(f.probes.size > 2)
        }
    }

    @Test fun exhaustedBackupsRetryOnlyBackupsAndFirstDataStillArmsRecovery() = runBlocking {
        fixture(fallback("group", "primary", "backup")).use { f ->
            f.start()
            f.fail()
            f.fail()
            assertEquals(listOf("backup"), f.plan().candidates)
            f.respond()
            assertEquals(f.clock.now + 10_000, f.fallback.getValue("group").nextRecoveryAtElapsedMs)
            assertEquals(listOf("backup"), f.plan().candidates)
        }
    }

    @Test fun innerRecoveryCanRestoreOuterBeforeOuterBackupEverResponds() = runBlocking {
        val inner = fallback("inner", "a", "b")
        val outer = fallback("outer").copy(members = listOf(GroupMemberRef("inner", true), GroupMemberRef("c")))
        fixture(inner, outer).use { f ->
            f.start()
            f.fail("outer", "a")
            f.respond("outer", "b")
            f.fail("outer", "b")
            assertEquals(0L, f.fallback.getValue("outer").nextRecoveryAtElapsedMs)
            f.probe = { leaf, _, _ -> if (leaf == "a") 1L else null }
            f.clock.advanceBy(10_000)
            assertTrue(f.fallback.isEmpty())
            assertEquals(listOf("a"), f.plan("outer").candidates)
        }
    }

    private companion object {
        const val CONFIGURED = "https://configured.invalid/check"
        const val DEFAULT = "https://www.gstatic.com/generate_204"
        const val ALTERNATE = "https://cp.cloudflare.com/generate_204"
    }
}
