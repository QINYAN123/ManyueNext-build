package eu.kanade.tachiyomi.ui.reader.manyue

/**
 * Pure (no Android deps) foldable width policy. Fully unit-testable.
 */
object ManyueFoldableWidthPolicy {

    const val INNER_SCREEN_MIN_DP = 600
    const val AUTO_MIN_WIDTH = 1632
    private const val EXTREME_RATIO_MAX = 2.35
    private const val EXTREME_RATIO_MIN = 1.08
    private const val FALLBACK_AUTO_WIDTH = 1920

    /** fold modes */
    const val FOLD_AUTO = -1
    const val FOLD_FULL = 0

    fun isInnerScreen(smallestScreenWidthDp: Int): Boolean =
        smallestScreenWidthDp >= INNER_SCREEN_MIN_DP

    /**
     * @param screenWidthPx physical screen width in px
     * @param nativeSamples up to 9 (nativeWidth, nativeHeight) pairs of pages seen
     * @param foldMode -1=AUTO, 0=FULL, >0=manual px
     * @param manualWidth manual pixel width when foldMode>0
     */
    fun computeTargetWidth(
        screenWidthPx: Int,
        nativeSamples: List<Pair<Int, Int>>,
        foldMode: Int,
        manualWidth: Int,
    ): Int {
        // Non-inner screen or FULL -> use full width
        if (foldMode == FOLD_FULL) return screenWidthPx
        if (foldMode > 0) return minOf(screenWidthPx, manualWidth.coerceIn(1360, 2880))
        // AUTO
        if (nativeSamples.isEmpty()) return minOf(FALLBACK_AUTO_WIDTH, screenWidthPx)
        val widths = nativeSamples.map { it.first }.sorted()
        val ratios = nativeSamples.map { it.second.toDouble() / it.first.toDouble() }.sorted()
        val wMed = median(widths)
        val rMed = median(ratios)
        if (rMed >= EXTREME_RATIO_MAX || rMed <= EXTREME_RATIO_MIN) return screenWidthPx
        val desired = Math.round(wMed * 1.2 / 16.0) * 16
        return minOf(screenWidthPx, maxOf(AUTO_MIN_WIDTH, desired.toInt()))
    }

    private fun median(sorted: List<Int>): Int {
        if (sorted.isEmpty()) return 0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else sorted[mid - 1]
    }

    private fun median(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 1.5
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else sorted[mid - 1]
    }
}
