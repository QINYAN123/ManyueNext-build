package eu.kanade.tachiyomi.ui.reader.manyue

import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyuePrefetchCoordinatorLifecycleTest {
    @Test
    fun `late cursor and same-generation manager reset inherit the canonical direction`() {
        val chapter = 111L
        val mode = ManyueEnhancementMode.AI_2X.value
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(mode)
        ManyuePrefetchManager.reset()
        val generation = ManyueRuntimeState.generation
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val never = CompletableDeferred<Unit>()
        fun reconcile() {
            coordinator.reconcile(
                chapter, 8, generation, 20, mode,
                committedDirection = ManyuePrefetchManager.directionForSelection(chapter, 8, generation, mode),
            ) { _, _ -> never.await(); ManyuePrefetchWorkOutcome.COMPLETED }
        }
        try {
            ManyuePrefetchManager.onPageChanged(chapter, 10)
            ManyuePrefetchManager.onPageChanged(chapter, 8)
            assertNull(ManyuePrefetchManager.directionForSelection(chapter, 9, generation, mode))
            assertNull(ManyuePrefetchManager.directionForSelection(chapter, 8, generation - 1, mode))
            reconcile()
            assertEquals(ManyueAiSafetyPolicy.PrefetchDirection.BACKWARD, coordinator.selectedDirection(chapter, generation))
            assertEquals(1, ManyueReadAheadScheduler.policy.distance(chapter, 7, generation))
            ManyuePrefetchManager.reset()
            ManyuePrefetchManager.onPageChanged(chapter, 8)
            reconcile()
            assertEquals(ManyueAiSafetyPolicy.PrefetchDirection.FORWARD, coordinator.selectedDirection(chapter, generation))
            assertEquals(1, ManyueReadAheadScheduler.policy.distance(chapter, 9, generation))
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyuePrefetchManager.reset()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `short backlook keeps source work while confirmed reverse cancels it before enqueue`() = runBlocking {
        val chapterId = 110L
        val mode = ManyueEnhancementMode.AI_2X.value
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(mode)
        ManyuePrefetchManager.reset()
        val generation = ManyueRuntimeState.generation
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val sourceEntered = CompletableDeferred<Unit>()
        val sourceClosed = CompletableDeferred<Unit>()
        val releaseSource = CompletableDeferred<Unit>()
        val enqueued = Collections.synchronizedList(mutableListOf<Int>())
        val work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome = { page, taskGeneration ->
            val guard = ManyuePrefetchPreparationPhaseGuard()
            try {
                coordinator.awaitWhileCurrent(
                    chapterId, taskGeneration, mode,
                    keepStageCurrent = {
                        guard.shouldKeepStageCurrent { coordinator.isPreparationCurrent(chapterId, taskGeneration, mode, page) }
                    },
                    cancelStageIfStale = { guard.cancelPreparationIfPending() },
                ) {
                    if (page == 11) {
                        sourceEntered.complete(Unit)
                        try {
                            releaseSource.await()
                        } finally {
                            sourceClosed.complete(Unit)
                        }
                    }
                    enqueued += page
                }
                ManyuePrefetchWorkOutcome.COMPLETED
            } catch (_: CancellationException) {
                ManyuePrefetchWorkOutcome.CANCELLED
            }
        }
        fun select(page: Int) {
            ManyuePrefetchManager.onPageChanged(chapterId, page, setOf(chapterId to page))
            ManyuePrefetchManager.releaseCurrent(chapterId, page, generation)
            coordinator.reconcile(
                chapterId, page, generation, 20, mode,
                committedDirection = ManyuePrefetchManager.directionForSelection(chapterId, page, generation, mode),
                work = work,
            )
        }
        try {
            select(10)
            withTimeout(5_000) { sourceEntered.await() }
            select(9)
            assertEquals(ManyueAiSafetyPolicy.PrefetchDirection.FORWARD, coordinator.selectedDirection(chapterId, generation))
            assertTrue(coordinator.isPreparationCurrent(chapterId, generation, mode, 11))
            assertEquals(2, ManyueReadAheadScheduler.policy.distance(chapterId, 11, generation))
            assertTrue(!sourceClosed.isCompleted)
            select(8)
            assertEquals(ManyueAiSafetyPolicy.PrefetchDirection.BACKWARD, coordinator.selectedDirection(chapterId, generation))
            assertEquals(1, ManyueReadAheadScheduler.policy.distance(chapterId, 7, generation))
            withTimeout(5_000) { sourceClosed.await() }
            withTimeout(5_000) { coordinator.awaitCursorIdle() }
            assertEquals((7 downTo 0).toList(), enqueued.toList(), "stale source must close before reverse work is enqueued")
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyuePrefetchManager.reset()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }


    @Test
    fun `closed current decision prevents future work until visible request has been offered`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val oldMode = ManyueRuntimeState.modeInt
        val mode = ManyueEnhancementMode.AI_2X.value
        ManyueRuntimeState.updateMode(mode)
        ManyuePrefetchManager.reset()
        val generation = ManyueRuntimeState.generation
        val future = CompletableDeferred<Int>()
        try {
            ManyuePrefetchManager.onPageChanged(92, 0)
            coordinator.reconcile(92, 0, generation, 2, mode) { page, _ ->
                future.complete(page)
                ManyuePrefetchWorkOutcome.ENQUEUED
            }
            assertTrue(!future.isCompleted, "current holder must get the queue first")
            ManyuePrefetchManager.releaseCurrent(92, 0, generation)
            assertEquals(1, withTimeout(2_000) { future.await() })
            withTimeout(2_000) { coordinator.awaitCursorIdle() }
        } finally {
            coordinator.cancelAll()
            ManyuePrefetchManager.reset()
            ManyueRuntimeState.updateMode(oldMode)
            scope.cancel()
        }
    }

    @Test
    fun `old generation current decision cannot open a rebuilt reader cursor`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val oldMode = ManyueRuntimeState.modeInt
        val mode = ManyueEnhancementMode.AI_2X.value
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.OFF.value)
        val staleGeneration = ManyueRuntimeState.generation
        ManyueRuntimeState.updateMode(mode)
        ManyuePrefetchManager.reset()
        val generation = ManyueRuntimeState.generation
        val future = CompletableDeferred<Int>()
        try {
            ManyuePrefetchManager.onPageChanged(93, 0)
            coordinator.reconcile(93, 0, generation, 2, mode) { page, _ ->
                future.complete(page)
                ManyuePrefetchWorkOutcome.ENQUEUED
            }
            ManyuePrefetchManager.releaseCurrent(93, 0, staleGeneration)
            assertTrue(!future.isCompleted, "an old holder must not release the new gate")
            ManyuePrefetchManager.releaseCurrent(93, 0, generation)
            assertEquals(1, withTimeout(2_000) { future.await() })
        } finally {
            coordinator.cancelAll()
            ManyuePrefetchManager.reset()
            ManyueRuntimeState.updateMode(oldMode)
            scope.cancel()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `one source cursor advances after enqueue while earlier workers are still pending`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val startedByPage = (1..11).associateWith { CompletableDeferred<Unit>() }
        val terminalByPage = (1..11).associateWith { CompletableDeferred<Unit>() }
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val enqueued = Collections.synchronizedList(mutableListOf<Int>())
        val priorWorkerPendingAtNextEnqueue = CompletableDeferred<Unit>()
        val allowFirstWork = CompletableDeferred<Unit>()
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
        val generation = ManyueRuntimeState.generation
        lateinit var work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome

        work = { pageIndex, generation ->
            val running = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, running) }
            try {
                startedByPage.getValue(pageIndex).complete(Unit)
                // Keep the cursor alive until the test has observed it. Without a handshake,
                // a fast executor can finish all 11 pages before the main test resumes.
                if (pageIndex == 1) allowFirstWork.await()
                if (pageIndex > 1 && !terminalByPage.getValue(pageIndex - 1).isCompleted) {
                    priorWorkerPendingAtNextEnqueue.complete(Unit)
                }
                // Model the reader advancing as each request enters the queue. The prior native
                // result remains nonterminal until the next source page has started.
                ManyuePrefetchManager.onPageChanged(91, pageIndex)
                coordinator.reconcileAfterDecision(
                    91, pageIndex, generation, 12, ManyueEnhancementMode.AI_2X.value, work,
                )
                enqueued += pageIndex
                if (pageIndex > 1) terminalByPage.getValue(pageIndex - 1).complete(Unit)
                ManyuePrefetchWorkOutcome.ENQUEUED
            } finally {
                active.decrementAndGet()
            }
        }

        try {
            ManyuePrefetchManager.reset()
            coordinator.reconcileAfterDecision(91, 0, generation, 12, ManyueEnhancementMode.AI_2X.value, work)
            withTimeout(5_000) { startedByPage.getValue(1).await() }
            assertEquals(1, coordinator.activeCursorCount)
            allowFirstWork.complete(Unit)
            for (pageIndex in 1..11) {
                withTimeout(5_000) { startedByPage.getValue(pageIndex).await() }
            }
            withTimeout(5_000) {
                while (coordinator.activeCursorCount != 0) delay(10)
            }

            assertEquals((1..11).toList(), enqueued.toList())
            assertTrue(priorWorkerPendingAtNextEnqueue.isCompleted,
                "the next source page must start before the prior worker is terminal")
            assertEquals(1, maxActive.get(), "source loading and enqueue remain serialized")
            assertEquals(0, coordinator.activeCursorCount)
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyuePrefetchManager.reset()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `fixed head continues past five completed candidates and stops at chapter end`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val startedByPage = (1..19).associateWith { CompletableDeferred<Unit>() }
        val enqueued = Collections.synchronizedList(mutableListOf<Int>())
        val sourceActive = AtomicInteger()
        val maxSourceActive = AtomicInteger()
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
        val generation = ManyueRuntimeState.generation

        val work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome = { pageIndex, _ ->
            val active = sourceActive.incrementAndGet()
            maxSourceActive.updateAndGet { maxOf(it, active) }
            try {
                startedByPage.getValue(pageIndex).complete(Unit)
                enqueued += pageIndex
                ManyuePrefetchWorkOutcome.ENQUEUED
            } finally {
                sourceActive.decrementAndGet()
            }
        }

        try {
            ManyuePrefetchManager.reset()
            coordinator.reconcileAfterDecision(92, 0, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            for (pageIndex in 1..19) {
                withTimeout(5_000) { startedByPage.getValue(pageIndex).await() }
            }
            coordinator.awaitCursorIdle()
            assertEquals((1..19).toList(), enqueued.toList())
            assertEquals(1, maxSourceActive.get(), "the logical chapter horizon does not multiply source cursors")
            assertTrue(coordinator.isPreparationCurrent(92, generation, ManyueEnhancementMode.AI_2X.value, 19))
            assertTrue(!coordinator.isPreparationCurrent(92, generation, ManyueEnhancementMode.AI_2X.value, 20),
                "chapter bounds still reject nonexistent pages")

            // Reconcile at the same head never replays completed chapter work.
            coordinator.reconcileAfterDecision(92, 0, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            assertEquals((1..19).toList(), enqueued.toList())
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyuePrefetchManager.reset()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `successful page can be prefetched again after cache eviction on direction revisit`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val attempts = Collections.synchronizedList(mutableListOf<Int>())
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
        val generation = ManyueRuntimeState.generation
        val work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome = { pageIndex, _ ->
            attempts += pageIndex
            ManyuePrefetchWorkOutcome.COMPLETED
        }

        try {
            coordinator.reconcileAfterDecision(93, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            assertEquals(1, attempts.count { it == 11 })

            coordinator.reconcileAfterDecision(93, 8, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            coordinator.reconcileAfterDecision(93, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()

            // The coordinator does not permanently blacklist successful pages. The request layer
            // can reuse a surviving cache entry, or rebuild the enhancement after cache eviction.
            assertEquals(2, attempts.count { it == 11 })
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `failed page is remembered only for the active cursor key`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val attemptsFor11 = AtomicInteger()
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
        val generation = ManyueRuntimeState.generation
        val work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome = { pageIndex, _ ->
            if (pageIndex == 11) {
                attemptsFor11.incrementAndGet()
                ManyuePrefetchWorkOutcome.FAILED
            } else {
                ManyuePrefetchWorkOutcome.COMPLETED
            }
        }

        try {
            coordinator.reconcileAfterDecision(94, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            coordinator.reconcileAfterDecision(94, 8, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            coordinator.reconcileAfterDecision(94, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()

            assertEquals(1, attemptsFor11.get(), "a failed page is skipped when revisited in the same key")
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `same generation reversal traverses remaining backward chapter with one cursor`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val page11Started = CompletableDeferred<Unit>()
        val releasePage11 = CompletableDeferred<Unit>()
        val completed = Collections.synchronizedList(mutableListOf<Int>())
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
        val generation = ManyueRuntimeState.generation

        val work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome = { pageIndex, _ ->
            if (pageIndex == 11) {
                page11Started.complete(Unit)
                releasePage11.await()
            }
            completed += pageIndex
            ManyuePrefetchWorkOutcome.COMPLETED
        }

        try {
            coordinator.reconcileAfterDecision(97, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            withTimeout(5_000) { page11Started.await() }
            coordinator.reconcileAfterDecision(97, 8, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            assertEquals(1, coordinator.activeCursorCount, "direction reversal keeps one cursor alive")

            releasePage11.complete(Unit)
            coordinator.awaitCursorIdle()

            assertEquals(listOf(11) + (7 downTo 0).toList(), completed.toList())
            assertTrue(!coordinator.isPreparationCurrent(97, generation, ManyueEnhancementMode.AI_2X.value, 11),
                "after reversal an invisible page behind the directional head is stale")
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancelled candidate is not remembered as a permanent failed page`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val firstAttemptStarted = CompletableDeferred<Unit>()
        val releaseFirstAttempt = CompletableDeferred<Unit>()
        val attemptsFor11 = AtomicInteger()
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
        val generation = ManyueRuntimeState.generation

        val work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome = { pageIndex, _ ->
            if (pageIndex == 11 && attemptsFor11.incrementAndGet() == 1) {
                firstAttemptStarted.complete(Unit)
                releaseFirstAttempt.await()
                ManyuePrefetchWorkOutcome.CANCELLED
            } else {
                ManyuePrefetchWorkOutcome.COMPLETED
            }
        }

        try {
            coordinator.reconcileAfterDecision(100, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            withTimeout(5_000) { firstAttemptStarted.await() }
            coordinator.reconcileAfterDecision(100, 8, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            releaseFirstAttempt.complete(Unit)
            coordinator.awaitCursorIdle()

            // Backward traversal ends at page 0. Returning to head 10 opens page 11 again because
            // the cancelled request was advanced for this pass but never added to failedPages.
            coordinator.reconcileAfterDecision(100, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            assertEquals(2, attemptsFor11.get())
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `stale budget wait is cancelled when claimed page leaves the window`() = runBlocking {
        val chapterId = 101L
        val mode = ManyueEnhancementMode.AI_2X.value
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(mode)
        val generation = ManyueRuntimeState.generation
        ManyuePrefetchManager.reset()
        ManyuePrefetchManager.onPageChanged(chapterId, 0)
        ManyuePrefetchManager.releaseCurrent(chapterId, 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val budgetWaitStarted = CompletableDeferred<Unit>()
        val budgetWaitCancelled = CompletableDeferred<Unit>()
        val releaseBudget = CompletableDeferred<Unit>()
        val enqueues = AtomicInteger()
        val phaseGuard = ManyuePrefetchPreparationPhaseGuard()

        try {
            coordinator.reconcileAfterDecision(chapterId, 0, generation, 20, mode) { pageIndex, taskGeneration ->
                if (pageIndex != 1) return@reconcileAfterDecision ManyuePrefetchWorkOutcome.COMPLETED
                try {
                    coordinator.awaitWhileCurrent(
                        chapterId,
                        taskGeneration,
                        mode,
                        keepStageCurrent = {
                            phaseGuard.shouldKeepStageCurrent {
                                coordinator.isPreparationCurrent(chapterId, taskGeneration, mode, pageIndex)
                            }
                        },
                        cancelStageIfStale = {
                            phaseGuard.cancelPreparationIfPending()
                        },
                    ) {
                        budgetWaitStarted.complete(Unit)
                        try {
                            releaseBudget.await()
                            enqueues.incrementAndGet()
                        } finally {
                            budgetWaitCancelled.complete(Unit)
                        }
                    }
                    ManyuePrefetchWorkOutcome.COMPLETED
                } catch (_: CancellationException) {
                    ManyuePrefetchWorkOutcome.CANCELLED
                }
            }
            withTimeout(5_000) { budgetWaitStarted.await() }

            ManyuePrefetchManager.onPageChanged(chapterId, 8)
            ManyuePrefetchManager.releaseCurrent(chapterId, 8)
            coordinator.reconcileAfterDecision(chapterId, 8, generation, 20, mode) { _, _ ->
                ManyuePrefetchWorkOutcome.COMPLETED
            }

            withTimeout(2_000) { budgetWaitCancelled.await() }
            coordinator.awaitCursorIdle()
            assertEquals(0, enqueues.get(), "a stale page must not proceed to request enqueue")
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyuePrefetchManager.reset()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `leaving the window during source preparation closes its reservation before enqueue`() = runBlocking {
        val chapterId = 102L
        val mode = ManyueEnhancementMode.AI_2X.value
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(mode)
        val generation = ManyueRuntimeState.generation
        ManyuePrefetchManager.reset()
        ManyuePrefetchManager.onPageChanged(chapterId, 0)
        ManyuePrefetchManager.releaseCurrent(chapterId, 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val loadStarted = CompletableDeferred<Unit>()
        val reservationClosed = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val closeCount = AtomicInteger()
        val enqueues = AtomicInteger()
        val phaseGuard = ManyuePrefetchPreparationPhaseGuard()

        try {
            coordinator.reconcileAfterDecision(chapterId, 0, generation, 20, mode) { pageIndex, taskGeneration ->
                if (pageIndex != 1) return@reconcileAfterDecision ManyuePrefetchWorkOutcome.COMPLETED
                try {
                    coordinator.awaitWhileCurrent(
                        chapterId,
                        taskGeneration,
                        mode,
                        keepStageCurrent = {
                            phaseGuard.shouldKeepStageCurrent {
                                coordinator.isPreparationCurrent(chapterId, taskGeneration, mode, pageIndex)
                            }
                        },
                        cancelStageIfStale = {
                            phaseGuard.cancelPreparationIfPending()
                        },
                    ) {
                        val reservation = AutoCloseable {
                            closeCount.incrementAndGet()
                            reservationClosed.complete(Unit)
                        }
                        try {
                            loadStarted.complete(Unit)
                            releaseLoad.await()
                            enqueues.incrementAndGet()
                        } finally {
                            reservation.close()
                        }
                    }
                    ManyuePrefetchWorkOutcome.COMPLETED
                } catch (_: CancellationException) {
                    ManyuePrefetchWorkOutcome.CANCELLED
                }
            }
            withTimeout(5_000) { loadStarted.await() }

            ManyuePrefetchManager.onPageChanged(chapterId, 8)
            ManyuePrefetchManager.releaseCurrent(chapterId, 8)
            coordinator.reconcileAfterDecision(chapterId, 8, generation, 20, mode) { _, _ ->
                ManyuePrefetchWorkOutcome.COMPLETED
            }

            withTimeout(2_000) { reservationClosed.await() }
            coordinator.awaitCursorIdle()
            assertEquals(1, closeCount.get())
            assertEquals(0, enqueues.get(), "a stale source load must not enqueue AI work")
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyuePrefetchManager.reset()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `terminal watchers retain at most five reservations and reset releases them`() = runBlocking {
        val chapterId = 103L
        val mode = ManyueEnhancementMode.AI_2X.value
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(mode)
        val generation = ManyueRuntimeState.generation
        ManyuePrefetchManager.reset()
        ManyuePrefetchManager.onPageChanged(chapterId, 0)
        ManyuePrefetchManager.releaseCurrent(chapterId, 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val terminalByPage = (1..6).associateWith { CompletableDeferred<Unit>() }
        val page6Enqueued = CompletableDeferred<Unit>()
        val reservationCloseCount = AtomicInteger()
        val enqueued = Collections.synchronizedList(mutableListOf<Int>())
        val work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome = { pageIndex, taskGeneration ->
            val slot = coordinator.awaitWorkerReservationSlot(chapterId, taskGeneration, mode, pageIndex)
            val reservation = AutoCloseable { reservationCloseCount.incrementAndGet() }
            coordinator.retainWorkerReservationUntilTerminal(
                chapterId = chapterId,
                generation = taskGeneration,
                mode = mode,
                pageIndex = pageIndex,
                token = "prefetch-$pageIndex",
                reservation = reservation,
                slot = slot,
                awaitTerminal = { token ->
                    terminalByPage.getValue(token.substringAfter('-').toInt()).await()
                    ManyueAiUpscaler.STATE_READY
                },
            )
            enqueued += pageIndex
            if (pageIndex == 6) page6Enqueued.complete(Unit)
            ManyuePrefetchWorkOutcome.ENQUEUED
        }

        try {
            coordinator.reconcileAfterDecision(chapterId, 0, generation, 20, mode, work)
            withTimeout(5_000) {
                while (enqueued.size < 5) delay(10)
            }
            assertEquals((1..5).toList(), enqueued.toList())
            assertEquals(5, coordinator.activeWorkerReservationCount)
            assertEquals(0, reservationCloseCount.get())

            // Head stays fixed. Page 6 waits only for capacity, then advances without a new
            // selection/reconcile event when a Native terminal watcher releases its slot.
            delay(150)
            assertTrue(!page6Enqueued.isCompleted, "the five reservation slots bound future enqueues")
            assertEquals(5, coordinator.activeWorkerReservationCount)
            assertEquals(1, coordinator.activeCursorCount)

            terminalByPage.getValue(1).complete(Unit)
            withTimeout(5_000) { page6Enqueued.await() }
            assertEquals(1, reservationCloseCount.get())
            assertEquals(5, coordinator.activeWorkerReservationCount)

            coordinator.cancelAll()
            assertEquals(0, coordinator.activeWorkerReservationCount)
            assertEquals(6, reservationCloseCount.get(), "reset releases every reservation exactly once")
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyuePrefetchManager.reset()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `claimed preparation remains eligible when it becomes the selected current page`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val oldMode = ManyueRuntimeState.modeInt
        val mode = ManyueEnhancementMode.AI_2X.value
        ManyueRuntimeState.updateMode(mode)
        val generation = ManyueRuntimeState.generation
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome = { pageIndex, _ ->
            if (pageIndex == 1) {
                started.complete(Unit)
                finish.await()
            }
            ManyuePrefetchWorkOutcome.COMPLETED
        }
        try {
            ManyuePrefetchManager.reset()
            coordinator.reconcileAfterDecision(107, 0, generation, 10, mode, work)
            withTimeout(5_000) { started.await() }
            coordinator.reconcileAfterDecision(107, 1, generation, 10, mode, work)
            assertTrue(coordinator.isPreparationCurrent(107, generation, mode, 1))
            finish.complete(Unit)
            coordinator.awaitCursorIdle()
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyuePrefetchManager.reset()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    fun `enqueue phase CAS wins over an earlier stale window observation`() {
        val phaseGuard = ManyuePrefetchPreparationPhaseGuard()
        val staleObservationMade = CountDownLatch(1)
        val allowPollCas = CountDownLatch(1)
        val pollCancelled = AtomicInteger()
        val poll = Thread {
            val keepCurrent = phaseGuard.shouldKeepStageCurrent { false }
            staleObservationMade.countDown()
            allowPollCas.await()
            if (!keepCurrent && phaseGuard.cancelPreparationIfPending()) pollCancelled.incrementAndGet()
        }
        poll.start()

        try {
            assertTrue(staleObservationMade.await(5, TimeUnit.SECONDS))
            assertEquals(ManyuePrefetchPreparationPhase.PREPARING, phaseGuard.currentPhase)
            assertTrue(phaseGuard.beginEnqueue(), "IO claims the phase before the poll attempts cancellation")
            phaseGuard.resolveEnqueue()
        } finally {
            allowPollCas.countDown()
            poll.join(5_000)
        }

        assertTrue(!poll.isAlive, "the deterministic poll race should finish")
        assertEquals(0, pollCancelled.get())
        assertEquals(ManyuePrefetchPreparationPhase.RESULT_RESOLVED, phaseGuard.currentPhase)
        assertTrue(phaseGuard.shouldKeepStageCurrent { false }, "resolved enqueue keeps its worker waiter alive")

        val pollWins = ManyuePrefetchPreparationPhaseGuard()
        assertTrue(pollWins.cancelPreparationIfPending())
        var factoryCalls = 0
        if (pollWins.beginEnqueue()) factoryCalls++
        assertEquals(0, factoryCalls, "a winning cancellation CAS prevents Factory entry")
        assertEquals(ManyuePrefetchPreparationPhase.CANCELLED, pollWins.currentPhase)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `new generation starts forward from current page instead of prior chapter head`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val oldMode = ManyueRuntimeState.modeInt
        val oldModel = ManyueRuntimeState.aiModel
        val mode = ManyueEnhancementMode.AI_2X.value
        ManyueRuntimeState.updateMode(mode)
        val firstGeneration = ManyueRuntimeState.generation
        val oldPass = Collections.synchronizedList(mutableListOf<Int>())
        val newPass = Collections.synchronizedList(mutableListOf<Int>())

        try {
            coordinator.reconcileAfterDecision(98, 35, firstGeneration, 40, mode) { pageIndex, _ ->
                oldPass += pageIndex
                ManyuePrefetchWorkOutcome.COMPLETED
            }
            coordinator.awaitCursorIdle()
            assertEquals(listOf(36, 37, 38, 39), oldPass.toList())

            val nextModel = if (oldModel == ManyueAiModel.QUALITY_REAL_ESRGAN) {
                ManyueAiModel.FAST_REAL_CUGAN
            } else {
                ManyueAiModel.QUALITY_REAL_ESRGAN
            }
            ManyueRuntimeState.updateAiModel(nextModel)
            val nextGeneration = ManyueRuntimeState.generation
            coordinator.reconcileAfterDecision(98, 0, nextGeneration, 40, mode) { pageIndex, _ ->
                newPass += pageIndex
                ManyuePrefetchWorkOutcome.COMPLETED
            }
            coordinator.awaitCursorIdle()

            assertEquals((1..39).toList(), newPass.toList())
            assertEquals(
                ManyueAiSafetyPolicy.PrefetchDirection.FORWARD,
                coordinator.selectedDirection(98, nextGeneration),
            )
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyueRuntimeState.updateAiModel(oldModel)
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `head update racing with preparation completion cannot lose the next chapter candidate`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val page5Started = CompletableDeferred<Unit>()
        val releasePage5 = CompletableDeferred<Unit>()
        val raceStart = CompletableDeferred<Unit>()
        val page6Started = CompletableDeferred<Unit>()
        val completed = Collections.synchronizedList(mutableListOf<Int>())
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
        val generation = ManyueRuntimeState.generation

        val work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome = { pageIndex, _ ->
            if (pageIndex == 5) {
                page5Started.complete(Unit)
                releasePage5.await()
            }
            if (pageIndex == 6) page6Started.complete(Unit)
            completed += pageIndex
            ManyuePrefetchWorkOutcome.COMPLETED
        }

        try {
            coordinator.reconcileAfterDecision(99, 0, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            withTimeout(5_000) { page5Started.await() }

            val finish = async(Dispatchers.Default) {
                raceStart.await()
                releasePage5.complete(Unit)
            }
            val moveHead = async(Dispatchers.Default) {
                raceStart.await()
                coordinator.reconcileAfterDecision(99, 1, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            }
            raceStart.complete(Unit)
            finish.await()
            moveHead.await()

            withTimeout(5_000) { page6Started.await() }
            coordinator.awaitCursorIdle()
            assertEquals((1..19).toList(), completed.toList())
            assertEquals(1, completed.count { it == 6 })
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `chapter replacement and runtime mode change cancel stale cursor waits`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val oldStarted = CompletableDeferred<Unit>()
        val oldCancelled = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        val modeWaitStarted = CompletableDeferred<Unit>()
        val modeWaitCancelled = CompletableDeferred<Unit>()
        val oldMode = ManyueRuntimeState.modeInt
        var workerToken: String? = null

        try {
            ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
            val generation = ManyueRuntimeState.generation
            coordinator.reconcileAfterDecision(
                93, 0, generation, 8, ManyueEnhancementMode.AI_2X.value,
            ) { _, taskGeneration ->
                oldStarted.complete(Unit)
                try {
                    coordinator.awaitWhileCurrent(93, taskGeneration, ManyueEnhancementMode.AI_2X.value) {
                        CompletableDeferred<Unit>().await()
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    oldCancelled.complete(Unit)
                    throw error
                }
                ManyuePrefetchWorkOutcome.COMPLETED
            }
            withTimeout(5_000) { oldStarted.await() }

            // A different chapter invalidates the old cursor, then receives exactly one new job.
            coordinator.reconcileAfterDecision(
                94, 0, generation, 3, ManyueEnhancementMode.AI_2X.value,
            ) { pageIndex, _ ->
                if (pageIndex == 1) replacementStarted.complete(Unit)
                ManyuePrefetchWorkOutcome.COMPLETED
            }
            withTimeout(5_000) { oldCancelled.await() }
            withTimeout(5_000) { replacementStarted.await() }
            assertTrue(coordinator.isCurrent(94, generation, ManyueEnhancementMode.AI_2X.value))

            coordinator.cancelAll()
            workerToken = ManyueAiUpscaler.register(
                mangaId = 91,
                chapterId = 94,
                pageIndex = 1,
                inputFile = Files.createTempFile("manyue-prefetch-await", ".bin").toFile(),
                sourceWidth = 100,
                sourceHeight = 200,
                targetMode = 2,
                targetWidth = 200,
                priority = 10,
                expectedMode = ManyueEnhancementMode.AI_2X.value,
                generation = generation,
                sourceFingerprint = "await-$generation",
            )
            coordinator.reconcileAfterDecision(
                94, 0, generation, 3, ManyueEnhancementMode.AI_2X.value,
            ) { _, taskGeneration ->
                modeWaitStarted.complete(Unit)
                try {
                    if (coordinator.awaitWorkerCompletion(
                            94,
                            taskGeneration,
                            ManyueEnhancementMode.AI_2X.value,
                            workerToken!!,
                        ) == ManyueAiUpscaler.STATE_CANCELLED
                    ) {
                        ManyuePrefetchWorkOutcome.CANCELLED
                    } else {
                        ManyuePrefetchWorkOutcome.COMPLETED
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    modeWaitCancelled.complete(Unit)
                    throw error
                }
            }
            withTimeout(5_000) { modeWaitStarted.await() }

            // A settings change cancels a suspended wait even if there is no new page callback.
            ManyueRuntimeState.updateMode(ManyueEnhancementMode.AUTO.value)
            withTimeout(5_000) { modeWaitCancelled.await() }
            assertTrue(!coordinator.isCurrent(94, generation, ManyueEnhancementMode.AI_2X.value))
            assertTrue(
                !ManyueAiUpscaler.isFinished(workerToken!!),
                "cancelling a waiter leaves shared worker work alone",
            )
        } finally {
            coordinator.cancelAll()
            workerToken?.let(ManyueAiUpscaler::cancel)
            ManyueRuntimeState.updateMode(oldMode)
            scope.cancel()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `runtime generation change without page reconcile cancels pending budget wait`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val budgetWaitStarted = CompletableDeferred<Unit>()
        val budgetWaitCancelled = CompletableDeferred<Unit>()
        val budgetAvailable = CompletableDeferred<Unit>()
        val oldMode = ManyueRuntimeState.modeInt
        val oldModel = ManyueRuntimeState.aiModel
        val mode = ManyueEnhancementMode.AI_2X.value

        try {
            ManyueRuntimeState.updateMode(mode)
            val generation = ManyueRuntimeState.generation
            coordinator.reconcileAfterDecision(96, 0, generation, 4, mode) { _, taskGeneration ->
                // Model a cache budget reservation that has not become available yet.
                coordinator.awaitWhileCurrent(96, taskGeneration, mode) {
                    budgetWaitStarted.complete(Unit)
                    try {
                        budgetAvailable.await()
                    } finally {
                        budgetWaitCancelled.complete(Unit)
                    }
                }
                ManyuePrefetchWorkOutcome.COMPLETED
            }
            withTimeout(5_000) { budgetWaitStarted.await() }

            val nextModel = if (ManyueRuntimeState.aiModel == ManyueAiModel.FAST_REAL_CUGAN) {
                ManyueAiModel.QUALITY_REAL_ESRGAN
            } else {
                ManyueAiModel.FAST_REAL_CUGAN
            }
            ManyueRuntimeState.updateAiModel(nextModel)

            assertTrue(ManyueRuntimeState.modeInt == mode, "the runtime mode stays unchanged")
            assertTrue(ManyueRuntimeState.generation != generation, "the runtime generation advances")
            withTimeout(5_000) { budgetWaitCancelled.await() }
            assertTrue(!coordinator.isCurrent(96, generation, mode))
        } finally {
            coordinator.cancelAll()
            ManyueRuntimeState.updateAiModel(oldModel)
            ManyueRuntimeState.updateMode(oldMode)
            scope.cancel()
        }
    }

    @Test
    fun `reader work gate pauses prefetch and cancellation prevents a later load`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val gateOwner = Any()
        val waitingOnGate = CompletableDeferred<Unit>()
        val loads = AtomicInteger()
        val oldMode = ManyueRuntimeState.modeInt
        val mode = ManyueEnhancementMode.AI_2X.value
        ManyueRuntimeState.updateMode(mode)
        val generation = ManyueRuntimeState.generation

        try {
            ManyueReaderWorkGate.update(gateOwner, active = false)
            ManyueReaderWorkGate.reportThermalStatus(gateOwner, ManyueReaderWorkGate.THERMAL_STATUS_SEVERE)
            coordinator.reconcileAfterDecision(95, 0, generation, 4, mode) { _, taskGeneration ->
                waitingOnGate.complete(Unit)
                coordinator.awaitReaderWork(95, taskGeneration, mode)
                loads.incrementAndGet()
                ManyuePrefetchWorkOutcome.COMPLETED
            }

            withTimeout(5_000) { waitingOnGate.await() }
            delay(120)
            assertEquals(0, loads.get(), "prefetch must wait while the strip reader is active")

            coordinator.cancelAll()
            ManyueReaderWorkGate.release(gateOwner)
            delay(160)
            assertEquals(0, loads.get(), "a cancelled cursor must not resume after scrolling")
        } finally {
            coordinator.cancelAll()
            ManyueReaderWorkGate.release(gateOwner)
            ManyueRuntimeState.updateMode(oldMode)
            scope.cancel()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `foreground pause preserves queued token and cursor checkpoint for one-time resume`() = runBlocking {
        val owner = Any()
        ManyueReaderForegroundGate.onResume(owner)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope, WeakReference(owner))
        val pageOneStarted = CompletableDeferred<Unit>()
        val releasePageOne = CompletableDeferred<Unit>()
        val tokenTerminal = CompletableDeferred<Unit>()
        val enqueuedPages = Collections.synchronizedList(mutableListOf<Int>())
        val pageTwoAttempts = AtomicInteger()
        val oldMode = ManyueRuntimeState.modeInt
        val mode = ManyueEnhancementMode.AI_2X.value

        try {
            ManyueRuntimeState.updateMode(mode)
            val generation = ManyueRuntimeState.generation
            coordinator.reconcileAfterDecision(108, 0, generation, 4, mode) { pageIndex, taskGeneration ->
                if (pageIndex == 1) {
                    val slot = coordinator.awaitWorkerReservationSlot(108, taskGeneration, mode, pageIndex)
                    coordinator.retainWorkerReservationUntilTerminal(
                        chapterId = 108,
                        generation = taskGeneration,
                        mode = mode,
                        pageIndex = pageIndex,
                        token = "issued-token-1",
                        reservation = AutoCloseable { },
                        slot = slot,
                        awaitTerminal = {
                            tokenTerminal.await()
                            ManyueAiUpscaler.STATE_READY
                        },
                    )
                    enqueuedPages += pageIndex
                    pageOneStarted.complete(Unit)
                    releasePageOne.await()
                    ManyuePrefetchWorkOutcome.ENQUEUED
                } else {
                    if (pageIndex == 2) pageTwoAttempts.incrementAndGet()
                    enqueuedPages += pageIndex
                    ManyuePrefetchWorkOutcome.COMPLETED
                }
            }

            withTimeout(5_000) { pageOneStarted.await() }
            val beforePause = coordinator.cursorCheckpoint()
            assertEquals(1, coordinator.activeWorkerReservationCount)
            assertEquals(setOf("issued-token-1"), beforePause.workerTokens)
            assertEquals(1, beforePause.nextPageIndex)

            ManyueReaderForegroundGate.onPause(owner)
            releasePageOne.complete(Unit)
            withTimeout(5_000) {
                while (coordinator.cursorCheckpoint().nextPageIndex != 2) delay(10)
            }

            val paused = coordinator.cursorCheckpoint()
            assertEquals(beforePause.chapterId, paused.chapterId)
            assertEquals(beforePause.generation, paused.generation)
            assertEquals(beforePause.mode, paused.mode)
            assertEquals(beforePause.selectedPage, paused.selectedPage)
            assertEquals(beforePause.pageCount, paused.pageCount)
            assertEquals(beforePause.direction, paused.direction)
            assertEquals(beforePause.failedPages, paused.failedPages)
            assertEquals(2, paused.nextPageIndex, "the issued page resolves to its exact successor")
            assertEquals(setOf("issued-token-1"), paused.workerTokens)
            assertEquals(1, coordinator.activeWorkerReservationCount)
            assertEquals(listOf(1), enqueuedPages.toList(), "the cursor must park before page 2")
            assertEquals(0, pageTwoAttempts.get())
            assertEquals(1, coordinator.activeCursorCount)

            ManyueReaderForegroundGate.onResume(owner)
            coordinator.awaitCursorIdle()

            assertEquals(listOf(1, 2, 3), enqueuedPages.toList())
            assertEquals(1, pageTwoAttempts.get(), "resume continues once at the preserved successor")
            assertEquals(setOf("issued-token-1"), coordinator.cursorCheckpoint().workerTokens)

            tokenTerminal.complete(Unit)
            withTimeout(5_000) {
                while (coordinator.activeWorkerReservationCount != 0) delay(10)
            }
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyueReaderForegroundGate.onDestroy(owner)
            ManyueReaderForegroundGate.resetForTest()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `cancellation while paused after reservation acquire returns the permit`() = runBlocking {
        val owner = Any()
        ManyueReaderForegroundGate.onResume(owner)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope, WeakReference(owner))
        val workStarted = CompletableDeferred<Unit>()
        val holdCursor = CompletableDeferred<Unit>()
        val oldMode = ManyueRuntimeState.modeInt
        val mode = ManyueEnhancementMode.AI_2X.value
        ManyueRuntimeState.updateMode(mode)
        val generation = ManyueRuntimeState.generation
        val heldSlots = mutableListOf<ManyuePrefetchJobCoordinator.WorkerReservationSlot>()

        try {
            coordinator.reconcileAfterDecision(109, 0, generation, 8, mode) { _, _ ->
                workStarted.complete(Unit)
                holdCursor.await()
                ManyuePrefetchWorkOutcome.COMPLETED
            }
            withTimeout(5_000) { workStarted.await() }
            repeat(5) { offset ->
                heldSlots += coordinator.awaitWorkerReservationSlot(109, generation, mode, offset + 1)
            }

            ManyueReaderForegroundGate.onPause(owner)
            val cancelledValidation = scope.async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.awaitWorkerReservationSlotStillCurrent(
                    109,
                    generation,
                    mode,
                    1,
                    heldSlots.first(),
                )
            }
            assertTrue(!cancelledValidation.isCompleted, "the acquired slot waits at the paused gate")
            cancelledValidation.cancel()
            cancelledValidation.join()

            ManyueReaderForegroundGate.onResume(owner)
            val replacement = withTimeout(5_000) {
                coordinator.awaitWorkerReservationSlot(109, generation, mode, 6)
            }
            replacement.close()
            heldSlots.drop(1).forEach { it.close() }
            heldSlots.first().close()
            heldSlots.clear()
        } finally {
            heldSlots.forEach { it.close() }
            coordinator.cancelAll()
            holdCursor.complete(Unit)
            scope.cancel()
            ManyueReaderForegroundGate.onDestroy(owner)
            ManyueReaderForegroundGate.resetForTest()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    fun `bounded prefetch read accepts limit and closes oversized stream after limit plus one`() {
        val atLimit = TrackingInputStream(ByteArray(8) { it.toByte() })
        assertArrayEquals(ByteArray(8) { it.toByte() }, readBoundedPrefetchBytes(atLimit, maxBytes = 8))
        assertTrue(atLimit.closed)

        val overLimit = TrackingInputStream(ByteArray(9) { it.toByte() })
        assertNull(readBoundedPrefetchBytes(overLimit, maxBytes = 8))
        assertTrue(overLimit.closed)
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }
}

/** Pure cursor fixtures model the visible holder's queue/skip decision before starting a cursor. */
private fun ManyuePrefetchJobCoordinator.reconcileAfterDecision(
    chapterId: Long,
    currentPageIndex: Int,
    generation: Long,
    pageCount: Int,
    mode: Int,
    work: suspend (pageIndex: Int, generation: Long) -> ManyuePrefetchWorkOutcome,
) {
    ManyuePrefetchManager.releaseCurrent(chapterId, currentPageIndex, generation)
    reconcile(chapterId, currentPageIndex, generation, pageCount, mode, work = work)
}
