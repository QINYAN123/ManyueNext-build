package eu.kanade.tachiyomi.ui.reader.manyue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManyueWebGpuCancellationRetryTest {

    @Test fun cancellationDeliveredAfterReentryRetriesOnceWithoutAnotherVisibilityUpdate() = runBlocking {
        var visible = true
        var starts = 0
        val started = CompletableDeferred<Unit>()
        val retry = ManyueWebGpuCancellationRetry(this)
        val canStart = { visible }
        val start: () -> Unit = {
            starts++
            started.complete(Unit)
        }

        retry.updateVisibility(true)
        visible = false
        retry.updateVisibility(false) // The original request leaves the visible set.
        visible = true
        retry.updateVisibility(true) // The page re-enters before its cancellation callback arrives.
        retry.afterCancellation(null, { null }, canStart, start)

        withTimeout(1_000L) { started.await() }
        assertEquals(1, starts)

        retry.afterCancellation(null, { null }, canStart, start)
        yield()
        assertEquals("A repeated cancellation in this visible episode must not spin retries", 1, starts)
    }

    @Test fun cancellationDuringStartWaitsForThatStartThenRetriesWithoutMoreMotion() = runBlocking {
        var visible = true
        var starts = 0
        val started = CompletableDeferred<Unit>()
        val startFinished = CompletableDeferred<Unit>()
        val retry = ManyueWebGpuCancellationRetry(this)
        retry.updateVisibility(true)
        val currentStart = launch { startFinished.await() }
        retry.updateVisibility(false)
        retry.updateVisibility(true) // Re-enter while the old start is still unwinding.
        val canStart = { visible }
        val start: () -> Unit = {
            starts++
            started.complete(Unit)
        }

        retry.afterCancellation(currentStart, { currentStart }, canStart, start)
        yield()
        assertEquals(0, starts)

        startFinished.complete(Unit)
        currentStart.join()
        withTimeout(1_000L) { started.await() }
        assertEquals(1, starts)
    }

    @Test fun cancellationCoalescesWithVisibleUpdateWaitingForTheSameStart() = runBlocking {
        var visible = true
        var starts = 0
        val started = CompletableDeferred<Unit>()
        val startFinished = CompletableDeferred<Unit>()
        val retry = ManyueWebGpuCancellationRetry(this)
        retry.updateVisibility(true)
        val currentStart = launch { startFinished.await() }
        val canStart = { visible }
        val start: () -> Unit = {
            starts++
            started.complete(Unit)
        }

        retry.afterVisibleUpdate(currentStart, { currentStart }, canStart, start)
        yield()
        retry.afterCancellation(currentStart, { currentStart }, canStart, start)
        yield()
        assertEquals(0, starts)

        startFinished.complete(Unit)
        currentStart.join()
        withTimeout(1_000L) { started.await() }
        assertEquals("Visibility and cancellation callbacks must share one deferred start", 1, starts)

        retry.afterCancellation(null, { null }, canStart, start)
        yield()
        assertEquals("A second cancel waits for a new visible episode", 1, starts)
    }

    @Test fun hidingBeforeDeferredCancellationRetryFinishesSuppressesStart() = runBlocking {
        var visible = true
        var starts = 0
        val startFinished = CompletableDeferred<Unit>()
        val retry = ManyueWebGpuCancellationRetry(this)
        retry.updateVisibility(true)
        val currentStart = launch { startFinished.await() }

        retry.afterCancellation(currentStart, { currentStart }, { visible }, { starts++ })
        yield()
        visible = false
        retry.updateVisibility(false)
        startFinished.complete(Unit)
        currentStart.join()
        yield()

        assertEquals("Leaving the visible set cancels its deferred retry", 0, starts)
    }

    @Test fun hiddenCancellationDoesNotRetryAndVisibleEntryCanStartLater() = runBlocking {
        var visible = false
        var starts = 0
        val started = CompletableDeferred<Unit>()
        val retry = ManyueWebGpuCancellationRetry(this)
        val canStart = { visible }
        val start: () -> Unit = {
            starts++
            started.complete(Unit)
        }

        retry.updateVisibility(false)
        retry.afterCancellation(null, { null }, canStart, start)
        yield()
        assertEquals(0, starts)

        visible = true
        retry.updateVisibility(true)
        retry.afterVisibleUpdate(null, { null }, canStart, start)
        withTimeout(1_000L) { started.await() }
        assertEquals(1, starts)
    }

    @Test fun resetCancelsDeferredRetryAndStaleCallbacksFailIdentityChecks() = runBlocking {
        var visible = true
        var current = true
        var starts = 0
        val startFinished = CompletableDeferred<Unit>()
        val retry = ManyueWebGpuCancellationRetry(this)
        retry.updateVisibility(true)
        val currentStart = launch { startFinished.await() }

        retry.afterCancellation(currentStart, { currentStart }, { visible && current }, { starts++ })
        retry.reset()
        current = false
        startFinished.complete(Unit)
        currentStart.join()
        yield()
        assertEquals(0, starts)

        assertFalse(
            ManyueWebGpuCallbackPolicy.isCurrent(
                receivedToken = "old-token",
                currentToken = "new-token",
                requestGeneration = 5L,
                bridgeGeneration = 5L,
                requestMode = 2,
                bridgeMode = 2,
                sourceCurrent = true,
                listenerCurrent = true,
                runtimeCurrent = true,
            ),
        )
        assertFalse(
            ManyueWebGpuCallbackPolicy.isCurrent(
                receivedToken = "same-token",
                currentToken = "same-token",
                requestGeneration = 5L,
                bridgeGeneration = 5L,
                requestMode = 2,
                bridgeMode = 2,
                sourceCurrent = true,
                listenerCurrent = false,
                runtimeCurrent = true,
            ),
        )
        assertFalse(
            ManyueWebGpuCallbackPolicy.isCurrent(
                receivedToken = "token",
                currentToken = "token",
                requestGeneration = 4L,
                bridgeGeneration = 5L,
                requestMode = 2,
                bridgeMode = 2,
                sourceCurrent = true,
                listenerCurrent = true,
                runtimeCurrent = true,
            ),
        )
        assertFalse(
            ManyueWebGpuCallbackPolicy.isCurrent(
                receivedToken = "token",
                currentToken = "token",
                requestGeneration = 5L,
                bridgeGeneration = 5L,
                requestMode = 1,
                bridgeMode = 2,
                sourceCurrent = true,
                listenerCurrent = true,
                runtimeCurrent = true,
            ),
        )
        assertFalse(
            ManyueWebGpuCallbackPolicy.isCurrent(
                receivedToken = "token",
                currentToken = "token",
                requestGeneration = 5L,
                bridgeGeneration = 5L,
                requestMode = 2,
                bridgeMode = 2,
                sourceCurrent = false,
                listenerCurrent = true,
                runtimeCurrent = true,
            ),
        )
    }

    @Test fun retryRequiresCurrentVisibleReadyNonTerminalPage() {
        fun canRetry(
            ownerAlive: Boolean = true,
            generationCurrent: Boolean = true,
            modeCurrent: Boolean = true,
            sourceCurrent: Boolean = true,
            sourceReady: Boolean = true,
            wantedForDisplay: Boolean = true,
            actuallyVisible: Boolean = true,
            terminalFailure: Boolean = false,
            hasCurrentToken: Boolean = false,
            hasReadyResult: Boolean = false,
        ) = ManyueWebGpuCancellationRetryPolicy.canRetry(
            ownerAlive,
            generationCurrent,
            modeCurrent,
            sourceCurrent,
            sourceReady,
            wantedForDisplay,
            actuallyVisible,
            terminalFailure,
            hasCurrentToken,
            hasReadyResult,
        )

        assertTrue(canRetry())
        assertFalse(canRetry(ownerAlive = false))
        assertFalse(canRetry(generationCurrent = false))
        assertFalse(canRetry(modeCurrent = false))
        assertFalse(canRetry(sourceCurrent = false))
        assertFalse(canRetry(sourceReady = false))
        assertFalse(canRetry(wantedForDisplay = false))
        assertFalse(canRetry(actuallyVisible = false))
        assertFalse(canRetry(terminalFailure = true))
        assertFalse(canRetry(hasCurrentToken = true))
        assertFalse(canRetry(hasReadyResult = true))
    }
}
