package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/** Chapter pair identifying one transition page in a continuous viewer. */
internal data class WebGpuTransitionKey(
    val previousChapterId: Long,
    val nextChapterId: Long?,
)

/** Pure selection policy for progress events emitted by the WebGPU continuous viewer. */
internal class WebGpuProgressSelectionPolicy {
    private enum class Direction { FORWARD, BACKWARD }

    private var selectionEpoch = 0L
    private var lastDeliveredReaderPage: Any? = null
    private val transitionCrossings = mutableMapOf<WebGpuTransitionKey, Direction>()

    @Synchronized
    fun epoch(): Long = selectionEpoch

    /** Ordinary image-page progress keeps the existing read-through semantics. */
    @Synchronized
    fun canSelectImagePage(eventEpoch: Long): Boolean = eventEpoch == selectionEpoch

    /** Starts a fresh navigation/table epoch and invalidates crossings from the old position. */
    @Synchronized
    fun reset(): Long {
        selectionEpoch++
        lastDeliveredReaderPage = null
        transitionCrossings.clear()
        return selectionEpoch
    }

    /** Same-chapter table refreshes preserve scroll evidence; a missing or changed anchor resets it. */
    fun shouldResetForChapterTable(retainedAnchorChapterId: Long?, incomingChapterId: Long?): Boolean =
        retainedAnchorChapterId == null || retainedAnchorChapterId != incomingChapterId

    /**
     * Records one actual page step. The caller invokes this for each step in a page walk, so a
     * multi-page fling cannot manufacture a transition crossing from only its final delta sign.
     */
    @Synchronized
    fun recordPageStep(
        key: WebGpuTransitionKey?,
        direction: Int,
        eventEpoch: Long,
    ) {
        if (key == null || direction == 0 || eventEpoch != selectionEpoch) return
        transitionCrossings[key] = if (direction > 0) Direction.FORWARD else Direction.BACKWARD
    }

    /**
     * A forward crossing grants one transition claim. Otherwise the actual page-zero anchor can
     * independently prove read-through when it is a real image in the previous chapter. The
     * viewer deduplicates repeated read-through callbacks by ImagePage identity.
     */
    @Synchronized
    fun claimTransitionProgress(
        key: WebGpuTransitionKey,
        eventEpoch: Long,
        anchorChapterId: Long?,
    ): Boolean {
        if (eventEpoch != selectionEpoch) return false
        return when (transitionCrossings.remove(key)) {
            Direction.FORWARD -> true
            Direction.BACKWARD, null -> anchorChapterId == key.previousChapterId
        }
    }

    /** Claim progress after validating the queued texture. Decode and AI can each replace it
     * while the same logical reader page remains at the read-through position. */
    @Synchronized
    fun claimDelivery(eventEpoch: Long, eventImage: Any, liveImage: Any?, readerPage: Any): Boolean {
        if (eventEpoch != selectionEpoch || eventImage !== liveImage || readerPage === lastDeliveredReaderPage) {
            return false
        }
        lastDeliveredReaderPage = readerPage
        return true
    }
}
