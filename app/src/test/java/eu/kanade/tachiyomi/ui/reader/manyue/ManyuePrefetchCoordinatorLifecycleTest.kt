package eu.kanade.tachiyomi.ui.reader.manyue

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `one cursor completes more than five pages while selection moves without restarting`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val firstStarted = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val startedByPage = (1..11).associateWith { CompletableDeferred<Unit>() }
        val terminalByPage = (1..11).associateWith { CompletableDeferred<Unit>() }
        val completedByPage = (1..11).associateWith { CompletableDeferred<Unit>() }
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val completed = Collections.synchronizedList(mutableListOf<Int>())
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
        val generation = ManyueRuntimeState.generation
        lateinit var work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome

        work = { pageIndex, generation ->
            val running = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, running) }
            try {
                startedByPage.getValue(pageIndex).complete(Unit)
                if (pageIndex == 1) firstStarted.complete(Unit)
                terminalByPage.getValue(pageIndex).await()
                // Simulate continuous reading. reconcile must update only the reading head while
                // this same cursor job continues through the rest of the chapter.
                coordinator.reconcile(91, pageIndex, generation, 12, ManyueEnhancementMode.AI_2X.value, work)
                completed += pageIndex
                completedByPage.getValue(pageIndex).complete(Unit)
                if (pageIndex == 11) finished.complete(Unit)
                ManyuePrefetchWorkOutcome.COMPLETED
            } finally {
                active.decrementAndGet()
            }
        }

        try {
            coordinator.reconcile(91, 0, generation, 12, ManyueEnhancementMode.AI_2X.value, work)
            withTimeout(5_000) { firstStarted.await() }
            assertEquals(1, coordinator.activeCursorCount)
            coordinator.reconcile(91, 1, generation, 12, ManyueEnhancementMode.AI_2X.value, work)
            for (pageIndex in 1..11) {
                withTimeout(5_000) { startedByPage.getValue(pageIndex).await() }
                assertEquals(1, coordinator.activeCursorCount)
                if (pageIndex < 11) {
                    assertTrue(!startedByPage.getValue(pageIndex + 1).isCompleted,
                        "page ${pageIndex + 1} must wait for page $pageIndex worker terminal")
                }
                terminalByPage.getValue(pageIndex).complete(Unit)
                withTimeout(5_000) { completedByPage.getValue(pageIndex).await() }
                if (pageIndex < 11) withTimeout(5_000) { startedByPage.getValue(pageIndex + 1).await() }
            }
            withTimeout(5_000) { finished.await() }
            withTimeout(5_000) {
                while (coordinator.activeCursorCount != 0) delay(10)
            }

            assertEquals((1..11).toList(), completed.toList())
            assertEquals(1, maxActive.get(), "only one prefetch page may be unfinished")
            assertEquals(0, coordinator.activeCursorCount)
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `fixed head stops after five candidates and repeated reconcile does not replay cache hits`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val startedByPage = (1..6).associateWith { CompletableDeferred<Unit>() }
        val terminalByPage = (1..5).associateWith { CompletableDeferred<Unit>() }
        val completed = Collections.synchronizedList(mutableListOf<Int>())
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
        val generation = ManyueRuntimeState.generation

        val work: suspend (Int, Long) -> ManyuePrefetchWorkOutcome = { pageIndex, _ ->
            startedByPage.getValue(pageIndex).complete(Unit)
            terminalByPage.getValue(pageIndex).await()
            completed += pageIndex
            ManyuePrefetchWorkOutcome.COMPLETED
        }

        try {
            coordinator.reconcile(92, 0, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            for (pageIndex in 1..5) {
                withTimeout(5_000) { startedByPage.getValue(pageIndex).await() }
                terminalByPage.getValue(pageIndex).complete(Unit)
                if (pageIndex < 5) withTimeout(5_000) { startedByPage.getValue(pageIndex + 1).await() }
            }
            coordinator.awaitCursorIdle()
            assertEquals((1..5).toList(), completed.toList())
            assertTrue(!startedByPage.getValue(6).isCompleted, "page 6 is outside the fixed-head window")

            // Reconcile with the same head leaves the cursor at the edge instead of replaying
            // completed/cache-hit pages 1..5 forever.
            coordinator.reconcile(92, 0, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            assertEquals((1..5).toList(), completed.toList())
        } finally {
            coordinator.cancelAll()
            scope.cancel()
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
            coordinator.reconcile(93, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            assertEquals(1, attempts.count { it == 11 })

            coordinator.reconcile(93, 9, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            coordinator.reconcile(93, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
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
            coordinator.reconcile(94, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            coordinator.reconcile(94, 9, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            coordinator.reconcile(94, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
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
    fun `same generation reversal opens backward window and lets nearby active work finish`() = runBlocking {
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
            coordinator.reconcile(97, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            withTimeout(5_000) { page11Started.await() }
            coordinator.reconcile(97, 9, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            assertEquals(1, coordinator.activeCursorCount, "direction reversal keeps one cursor alive")

            releasePage11.complete(Unit)
            coordinator.awaitCursorIdle()

            assertEquals(listOf(11, 8, 7, 6, 5, 4), completed.toList())
            assertTrue(3 !in completed, "page 3 is outside the five-page backward window")
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
            coordinator.reconcile(100, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            withTimeout(5_000) { firstAttemptStarted.await() }
            coordinator.reconcile(100, 9, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            releaseFirstAttempt.complete(Unit)
            coordinator.awaitCursorIdle()

            // Backward traversal ends at page 4. Returning to head 10 opens page 11 again because
            // the cancelled request was advanced for this pass but never added to failedPages.
            coordinator.reconcile(100, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
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
            coordinator.reconcile(chapterId, 0, generation, 20, mode) { pageIndex, taskGeneration ->
                if (pageIndex != 1) return@reconcile ManyuePrefetchWorkOutcome.COMPLETED
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
            coordinator.reconcile(chapterId, 8, generation, 20, mode) { _, _ ->
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
            coordinator.reconcile(chapterId, 0, generation, 20, mode) { pageIndex, taskGeneration ->
                if (pageIndex != 1) return@reconcile ManyuePrefetchWorkOutcome.COMPLETED
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
            coordinator.reconcile(chapterId, 8, generation, 20, mode) { _, _ ->
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
    fun `worker waiter survives direction movement after its token is queued`() = runBlocking {
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
        val tokenQueued = CompletableDeferred<Unit>()
        val completeWorker = CompletableDeferred<Unit>()
        val workerWaitCancelled = CompletableDeferred<Unit>()
        val nextPageStarted = CompletableDeferred<Unit>()
        val enqueueCount = AtomicInteger()

        try {
            coordinator.reconcile(chapterId, 0, generation, 20, mode) { pageIndex, taskGeneration ->
                if (pageIndex == 1) {
                    val phaseGuard = ManyuePrefetchPreparationPhaseGuard()
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
                        assertTrue(coordinator.isPreparationCurrent(chapterId, taskGeneration, mode, pageIndex))
                        assertTrue(phaseGuard.beginEnqueue())
                        enqueueCount.incrementAndGet()
                        phaseGuard.resolveEnqueue()
                        tokenQueued.complete(Unit)
                        try {
                            completeWorker.await()
                        } catch (error: CancellationException) {
                            workerWaitCancelled.complete(Unit)
                            throw error
                        }
                    }
                } else if (pageIndex == 4) {
                    nextPageStarted.complete(Unit)
                }
                ManyuePrefetchWorkOutcome.COMPLETED
            }
            withTimeout(5_000) { tokenQueued.await() }

            ManyuePrefetchManager.onPageChanged(chapterId, 3)
            ManyuePrefetchManager.releaseCurrent(chapterId, 3)
            coordinator.reconcile(chapterId, 3, generation, 20, mode) { pageIndex, _ ->
                if (pageIndex == 4) nextPageStarted.complete(Unit)
                ManyuePrefetchWorkOutcome.COMPLETED
            }
            delay(250)
            assertTrue(!workerWaitCancelled.isCompleted, "queued worker wait survives a window change")
            assertTrue(!nextPageStarted.isCompleted, "a second page waits for the queued worker to finish")
            assertEquals(1, enqueueCount.get())

            completeWorker.complete(Unit)
            withTimeout(5_000) { nextPageStarted.await() }
            coordinator.awaitCursorIdle()
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
            coordinator.reconcile(107, 0, generation, 10, mode, work)
            withTimeout(5_000) { started.await() }
            coordinator.reconcile(107, 1, generation, 10, mode, work)
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
            coordinator.reconcile(98, 35, firstGeneration, 40, mode) { pageIndex, _ ->
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
            coordinator.reconcile(98, 0, nextGeneration, 40, mode) { pageIndex, _ ->
                newPass += pageIndex
                ManyuePrefetchWorkOutcome.COMPLETED
            }
            coordinator.awaitCursorIdle()

            assertEquals((1..5).toList(), newPass.toList())
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
    fun `head update racing with cursor shutdown cannot lose the newly opened window`() = runBlocking {
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
            coordinator.reconcile(99, 0, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            withTimeout(5_000) { page5Started.await() }

            val finish = async(Dispatchers.Default) {
                raceStart.await()
                releasePage5.complete(Unit)
            }
            val moveHead = async(Dispatchers.Default) {
                raceStart.await()
                coordinator.reconcile(99, 1, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            }
            raceStart.complete(Unit)
            finish.await()
            moveHead.await()

            withTimeout(5_000) { page6Started.await() }
            coordinator.awaitCursorIdle()
            assertEquals(listOf(1, 2, 3, 4, 5, 6), completed.toList())
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
            coordinator.reconcile(93, 0, generation, 8, ManyueEnhancementMode.AI_2X.value) { _, taskGeneration ->
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
            coordinator.reconcile(94, 0, generation, 3, ManyueEnhancementMode.AI_2X.value) { pageIndex, _ ->
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
            coordinator.reconcile(94, 0, generation, 3, ManyueEnhancementMode.AI_2X.value) { _, taskGeneration ->
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
            ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X_CLASSIC.value)
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
            coordinator.reconcile(96, 0, generation, 4, mode) { _, taskGeneration ->
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
            coordinator.reconcile(95, 0, generation, 4, mode) { _, taskGeneration ->
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
