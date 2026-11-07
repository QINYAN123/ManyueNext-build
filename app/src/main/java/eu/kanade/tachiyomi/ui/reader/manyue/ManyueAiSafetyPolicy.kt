package eu.kanade.tachiyomi.ui.reader.manyue

/** Pure scheduling and memory guards shared by Pager, Webtoon, and the native worker. */
object ManyueAiSafetyPolicy {

    enum class PrefetchDirection(val step: Int) {
        FORWARD(1),
        BACKWARD(-1),
    }

    const val MAX_DISPLAY_PIXELS = 12_000_000
    const val MAX_CLASSIC_PIXELS = 6_000_000

    /**
     * Fixed-2x native intermediate before the worker area-resamples to the target width.
     * Measured on VER-AN10 (Adreno 740): peak worker RSS ~377-384 MB for 12, 17.5 and 24 MP
     * intermediates alike; GPU runtime state dominates, not the packed RGB buffer.
     */
    const val MAX_NATIVE_INTERMEDIATE_PIXELS = 24_000_000
    const val MAX_WEBP_DIMENSION = 16_383

    const val ACTIVE_COMPLETION_LOOK_BACK_PAGES = 5
    // Nearby priority/predictive-display range, not the chapter cursor's admission horizon.
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

    /** Future encoded work can continue throughout the chapter; passed work is not admitted. */
    fun futureRequestPriority(
        pageIndex: Int,
        currentIndex: Int,
        direction: PrefetchDirection,
    ): Int? {
        if (pageIndex < 0 || relativeDistance(pageIndex, currentIndex, direction) <= 0) return null
        return prefetchPriority(pageIndex, currentIndex, direction) ?: CONTINUOUS_PREFETCH_PRIORITY
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
        // The 2x intermediate lives only inside the native worker, which area-resamples it to
        // targetWidth before encoding; the displayed result is bounded separately above.
        return model.continuousScale ||
            isPredictedNativeOutputSafe(sourceWidth, sourceHeight, MAX_NATIVE_INTERMEDIATE_PIXELS)
    }

    fun isPredictedNativeOutputSafe(sourceWidth: Int, sourceHeight: Int, maxPixels: Int): Boolean {
        if (sourceWidth <= 0 || sourceHeight <= 0) return false
        return isPixelBudgetSafe(
            width = sourceWidth.toLong().times(2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            height = sourceHeight.toLong().times(2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            maxPixels = maxPixels,
        )
    }

    /**
     * Output width for a fixed-2x page whose full 2x result exceeds [maxDisplayPixels]. The model
     * still infers on the full-resolution source (no input downscale); the native worker then
     * area-resamples its 2x result to this width. Null when no fit is needed, when the native 2x
     * intermediate exceeds [maxNativePixels], or when the result would not be meaningfully wider
     * than the original page.
     */
    fun outputBudgetWidth(
        sourceWidth: Int,
        sourceHeight: Int,
        maxDisplayPixels: Int = MAX_DISPLAY_PIXELS,
        maxNativePixels: Int = MAX_NATIVE_INTERMEDIATE_PIXELS,
    ): Int? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxDisplayPixels <= 0) return null
        if (isPredictedNativeOutputSafe(sourceWidth, sourceHeight, maxDisplayPixels)) return null
        if (!isPredictedNativeOutputSafe(sourceWidth, sourceHeight, maxNativePixels)) return null
        val scale = kotlin.math.sqrt(maxDisplayPixels.toDouble() / (sourceWidth.toDouble() * sourceHeight.toDouble()))
        var width = kotlin.math.floor(sourceWidth * scale).toInt().coerceAtMost(sourceWidth * 2)
        while (width > sourceWidth) {
            val height = ManyueAiUpscaler.targetHeight(sourceWidth, sourceHeight, width)
            if (height > 0 && isPixelBudgetSafe(width, height, maxDisplayPixels)) {
                return width.takeIf { it.toLong() * 100L > sourceWidth.toLong() * (100L + MIN_FIT_GAIN_PERCENT) }
            }
            width--
        }
        return null
    }

    /**
     * Opt-in display fit for fixed-2x models: inference input whose 2x output just covers
     * [displayWidth]. Null when the full 2x output is not meaningfully wider than the display,
     * or when the fitted output would not be meaningfully wider than the original page.
     */
    fun displayFit(sourceWidth: Int, sourceHeight: Int, displayWidth: Int): Pair<Int, Int>? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || displayWidth <= 0) return null
        val fullWidth = sourceWidth.toLong() * 2L
        if (fullWidth * 100L <= displayWidth.toLong() * (100L + MIN_FIT_GAIN_PERCENT)) return null
        val width = (displayWidth + 1) / 2
        if (width >= sourceWidth) return null
        if (width.toLong() * 2L * 100L <= sourceWidth.toLong() * (100L + MIN_FIT_GAIN_PERCENT)) return null
        val height = ((sourceHeight.toLong() * width + sourceWidth / 2L) / sourceWidth).toInt()
        return if (height > 0) width to height else null
    }

    /** A fitted 2x output must still be at least this much wider than the original page. */
    const val MIN_FIT_GAIN_PERCENT = 5

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
