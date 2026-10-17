package eu.kanade.tachiyomi.ui.reader.manyue

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Coalesces a visible-page retry after cancellation and waits for an in-flight source start to
 * settle before retrying. A single automatic retry is allowed per visible episode.
 */
internal class ManyueWebGpuCancellationRetry(
    private val scope: CoroutineScope,
) {
    private var visible = false
    private var visibilityEpoch = 0L
    private var cancellationRetryUsed = false
    private var deferredStart: Job? = null

    fun updateVisibility(isVisible: Boolean) {
        if (visible == isVisible) return
        visible = isVisible
        visibilityEpoch++
        deferredStart?.cancel()
        deferredStart = null
        if (isVisible) cancellationRetryUsed = false
    }

    /** A cancelled request may be retried only once before the page leaves and re-enters. */
    fun allowsVisibilityStart(): Boolean = !visible || !cancellationRetryUsed

    fun afterVisibleUpdate(
        startJob: Job?,
        currentStartJob: () -> Job?,
        canStart: () -> Boolean,
        start: () -> Unit,
    ) {
        if (!visible || cancellationRetryUsed || !canStart()) return
        schedule(startJob, currentStartJob, canStart, start)
    }

    fun afterCancellation(
        startJob: Job?,
        currentStartJob: () -> Job?,
        canStart: () -> Boolean,
        start: () -> Unit,
    ) {
        if (!visible || cancellationRetryUsed || !canStart()) return
        cancellationRetryUsed = true
        schedule(startJob, currentStartJob, canStart, start)
    }

    fun reset() {
        visibilityEpoch++
        visible = false
        cancellationRetryUsed = false
        deferredStart?.cancel()
        deferredStart = null
    }

    private fun schedule(
        initialStartJob: Job?,
        currentStartJob: () -> Job?,
        canStart: () -> Boolean,
        start: () -> Unit,
    ) {
        if (deferredStart?.isActive == true) return
        val epoch = visibilityEpoch
        var previous: Job? = initialStartJob
        val scheduled = scope.launch {
            while (true) {
                val active = currentStartJob()?.takeIf { it.isActive }
                    ?: previous?.takeIf { it.isActive }
                    ?: break
                active.join()
                if (previous === active) previous = null
            }
            // Do not invoke start inline from a terminal listener or from the finishing start job.
            yield()
            while (currentStartJob()?.isActive == true) {
                currentStartJob()?.join()
                yield()
            }
            if (epoch == visibilityEpoch && visible && canStart()) start()
        }
        deferredStart = scheduled
        scheduled.invokeOnCompletion {
            if (deferredStart === scheduled) deferredStart = null
        }
    }
}

internal object ManyueWebGpuCallbackPolicy {
    fun isCurrent(
        receivedToken: String,
        currentToken: String?,
        requestGeneration: Long,
        bridgeGeneration: Long,
        requestMode: Int,
        bridgeMode: Int,
        sourceCurrent: Boolean,
        listenerCurrent: Boolean,
        runtimeCurrent: Boolean,
    ): Boolean = receivedToken == currentToken &&
        requestGeneration == bridgeGeneration && requestMode == bridgeMode &&
        sourceCurrent && listenerCurrent && runtimeCurrent
}

internal object ManyueWebGpuCancellationRetryPolicy {
    fun canRetry(
        ownerAlive: Boolean,
        generationCurrent: Boolean,
        modeCurrent: Boolean,
        sourceCurrent: Boolean,
        sourceReady: Boolean,
        wantedForDisplay: Boolean,
        actuallyVisible: Boolean,
        terminalFailure: Boolean,
        hasCurrentToken: Boolean,
        hasReadyResult: Boolean,
    ): Boolean = ownerAlive && generationCurrent && modeCurrent && sourceCurrent && sourceReady &&
        wantedForDisplay && actuallyVisible && !terminalFailure && !hasCurrentToken && !hasReadyResult
}
