package eu.kanade.tachiyomi.ui.reader.manyue

/** Pure scheduling and memory guards shared by Pager, Webtoon, and the native worker. */
object ManyueAiSafetyPolicy {

    const val MAX_DISPLAY_PIXELS = 12_000_000
    const val MAX_CLASSIC_PIXELS = 6_000_000

    const val PREFETCH_LOOK_BACK_PAGES = 2
    const val ACTIVE_COMPLETION_LOOK_BACK_PAGES = 5
    const val CONTINUOUS_PREFETCH_PRIORITY = 1

    fun priorityFor(pageIndex: Int, currentIndex: Int): Int? = when (pageIndex - currentIndex) {
        0 -> 100
        1 -> 50
        2 -> 40
        3 -> 30
        4 -> 20
        5 -> 10
        else -> null
    }

    /** Priority for the one cursor target, including pages more than five pages ahead. */
    fun continuousPrefetchPriority(pageIndex: Int, currentIndex: Int): Int {
        val distance = pageIndex - currentIndex
        return when {
            distance == 0 -> 100
            distance in 1..5 -> requireNotNull(priorityFor(pageIndex, currentIndex))
            distance > 5 -> CONTINUOUS_PREFETCH_PRIORITY
            distance in -PREFETCH_LOOK_BACK_PAGES until 0 -> 5
            else -> CONTINUOUS_PREFETCH_PRIORITY
        }
    }

    fun isPixelBudgetSafe(width: Int, height: Int, maxPixels: Int): Boolean {
        if (width <= 0 || height <= 0 || maxPixels <= 0) return false
        return width.toLong() * height.toLong() <= maxPixels.toLong()
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
        val aiMode = currentMode == ManyueEnhancementMode.AI_2X.value ||
            currentMode == ManyueEnhancementMode.AI_2X_CLASSIC.value
        return aiMode &&
            currentToken == expectedToken &&
            currentIdentity == expectedIdentity &&
            currentMode == expectedMode &&
            currentGeneration == expectedGeneration
    }
}
