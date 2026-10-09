package eu.kanade.tachiyomi.ui.reader.manyue

/** Pure scheduling and memory guards shared by Pager, Webtoon, and the native worker. */
object ManyueAiSafetyPolicy {

    enum class PrefetchDirection(val step: Int) {
        FORWARD(1),
        BACKWARD(-1),
    }

    const val MAX_DISPLAY_PIXELS = 12_000_000
    const val MAX_CLASSIC_PIXELS = 6_000_000
    const val MAX_WEBP_DIMENSION = 16_383

    const val ACTIVE_COMPLETION_LOOK_BACK_PAGES = 5
    const val PREFETCH_WINDOW_PAGES = 5
    const val CONTINUOUS_PREFETCH_PRIORITY = 1

    fun directionAfterSelection(
        previousPage: Int,
        currentPage: Int,
        currentDirection: PrefetchDirection,
    ): PrefetchDirection = when {
        currentPage > previousPage -> PrefetchDirection.FORWARD
        currentPage < previousPage -> PrefetchDirection.BACKWARD
        else -> currentDirection
    }

    fun relativeDistance(pageIndex: Int, currentIndex: Int, direction: PrefetchDirection): Int =
        (pageIndex - currentIndex) * direction.step

    /** Priority for a new request inside the bounded directional window, or null outside it. */
    fun prefetchPriority(
        pageIndex: Int,
        currentIndex: Int,
        direction: PrefetchDirection,
    ): Int? {
        val distance = relativeDistance(pageIndex, currentIndex, direction)
        if (pageIndex < 0 || distance !in 1..PREFETCH_WINDOW_PAGES) return null
        return 60 - distance * 10
    }

    fun shouldFinishActiveRequest(pageIndex: Int, currentIndex: Int, isProcessing: Boolean): Boolean =
        isProcessing && kotlin.math.abs(pageIndex.toLong() - currentIndex.toLong()) <= ACTIVE_COMPLETION_LOOK_BACK_PAGES

    fun managedRequestPriority(
        isCurrent: Boolean,
        isVisible: Boolean,
        directionalPriority: Int?,
        keepActive: Boolean,
    ): Int? = when {
        isCurrent -> 120
        isVisible -> 100
        directionalPriority != null -> directionalPriority
        keepActive -> CONTINUOUS_PREFETCH_PRIORITY
        else -> null
    }

    fun priorityFor(pageIndex: Int, currentIndex: Int): Int? = when (pageIndex - currentIndex) {
        0 -> 100
        1 -> 50
        2 -> 40
        3 -> 30
        4 -> 20
        5 -> 10
        else -> null
    }

    /** Priority helper for cursor requests; invalid or out-of-window pages receive lowest priority. */
    fun continuousPrefetchPriority(
        pageIndex: Int,
        currentIndex: Int,
        direction: PrefetchDirection = PrefetchDirection.FORWARD,
    ): Int = when {
        pageIndex == currentIndex -> 100
        else -> prefetchPriority(pageIndex, currentIndex, direction) ?: CONTINUOUS_PREFETCH_PRIORITY
    }

    fun isPixelBudgetSafe(width: Int, height: Int, maxPixels: Int): Boolean {
        if (width <= 0 || height <= 0 || maxPixels <= 0) return false
        return width.toLong() * height.toLong() <= maxPixels.toLong()
    }

    /** WebP encoders have an independent per-axis limit even when the image fits the pixel budget. */
    fun nativeOutputFormat(width: Int, height: Int, anime4kOverlay: Boolean): String =
        if (anime4kOverlay || width > MAX_WEBP_DIMENSION || height > MAX_WEBP_DIMENSION) "png" else "webp"

    fun isNativeWorkSafe(model: ManyueAiModel, sourceWidth: Int, sourceHeight: Int, targetWidth: Int): Boolean {
        if (!isPixelBudgetSafe(sourceWidth, sourceHeight, MAX_DISPLAY_PIXELS)) return false
        val targetHeight = ManyueAiUpscaler.targetHeight(sourceWidth, sourceHeight, targetWidth)
        if (!isPixelBudgetSafe(targetWidth, targetHeight, MAX_DISPLAY_PIXELS)) return false
        return model.continuousScale || isPredictedNativeOutputSafe(sourceWidth, sourceHeight, MAX_DISPLAY_PIXELS)
    }

    fun isPredictedNativeOutputSafe(sourceWidth: Int, sourceHeight: Int, maxPixels: Int): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) return false
        return isPixelBudgetSafe(
            width = sourceWidth.toLong().times(2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            height = sourceHeight.toLong().times(2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            maxPixels = maxPixels,
        )
    }

    fun isCompletionCurrent(
        expectedToken: String,
        currentToken: String?,
        expectedIdentity: Any,
        currentIdentity: Any?,
        expectedMode: Int,
        currentMode: Int,
        expectedGeneration: Long,
        currentGeneration: Long,
    ): Boolean {
        val aiMode = ManyueEnhancementMode.fromInt(currentMode).usesAi()
        return aiMode &&
            currentToken == expectedToken &&
            currentIdentity == expectedIdentity &&
            currentMode == expectedMode &&
            currentGeneration == expectedGeneration
    }
}
