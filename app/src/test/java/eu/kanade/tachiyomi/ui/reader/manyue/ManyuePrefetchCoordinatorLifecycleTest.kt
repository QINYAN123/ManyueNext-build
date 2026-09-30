package eu.kanade.tachiyomi.ui.reader.manyue

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
        lateinit var work: suspend (Int, Long) -> Unit

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

            // A later reverse/forward selection never rewinds the completed cursor.
            coordinator.reconcile(91, 3, generation, 12, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.reconcile(91, 11, generation, 12, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            assertEquals((1..11).toList(), completed.toList())
        } finally {
            coordinator.cancelAll()
            scope.cancel()
            ManyueRuntimeState.updateMode(oldMode)
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `forward jump keeps an active request then redirects once and reverse scroll does not replay`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        val coordinator = ManyuePrefetchJobCoordinator(scope)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val completed = Collections.synchronizedList(mutableListOf<Int>())
        val oldMode = ManyueRuntimeState.modeInt
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
        val generation = ManyueRuntimeState.generation
        lateinit var work: suspend (Int, Long) -> Unit

        work = { pageIndex, _ ->
            if (pageIndex == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            completed += pageIndex
            if (pageIndex == 19) finished.complete(Unit)
        }

        try {
            coordinator.reconcile(92, 0, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            withTimeout(5_000) { firstStarted.await() }
            coordinator.reconcile(92, 10, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            assertTrue(coordinator.isCurrent(92, generation, ManyueEnhancementMode.AI_2X.value))
            releaseFirst.complete(Unit)
            withTimeout(5_000) { finished.await() }
            coordinator.awaitCursorIdle()

            assertEquals(listOf(1, 8, 9, 11, 12, 13, 14, 15, 16, 17, 18, 19), completed.toList())
            coordinator.reconcile(92, 2, generation, 20, ManyueEnhancementMode.AI_2X.value, work)
            coordinator.awaitCursorIdle()
            assertEquals(listOf(1, 8, 9, 11, 12, 13, 14, 15, 16, 17, 18, 19), completed.toList())
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
            }
            withTimeout(5_000) { oldStarted.await() }

            // A different chapter invalidates the old cursor, then receives exactly one new job.
            coordinator.reconcile(94, 0, generation, 3, ManyueEnhancementMode.AI_2X.value) { pageIndex, _ ->
                if (pageIndex == 1) replacementStarted.complete(Unit)
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
                    coordinator.awaitWorkerCompletion(94, taskGeneration, ManyueEnhancementMode.AI_2X.value, workerToken!!)
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
            assertTrue(!ManyueAiUpscaler.isFinished(workerToken!!), "cancelling a waiter leaves shared worker work alone")
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
