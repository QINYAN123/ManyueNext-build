package eu.kanade.tachiyomi.ui.reader.manyue

/** Pure (no Android deps) foldable width policy. Fully unit-testable. */
object ManyueFoldableWidthPolicy {

    const val INNER_SCREEN_MIN_DP = 600

    /** fold modes */
    const val FOLD_AUTO = -1
    const val FOLD_FULL = 0

    fun isInnerScreen(smallestScreenWidthDp: Int): Boolean =
        smallestScreenWidthDp >= INNER_SCREEN_MIN_DP

    /**
     * @param screenWidthPx physical viewport width in px
     * @param nativeSamples retained for call-site compatibility; source dimensions must not
     *   resize the reading viewport because mixed page sizes can cause unstable width changes.
     * @param foldMode -1=AUTO, 0=FULL, >0=manual px
     * @param manualWidth manual pixel width when foldMode>0
     */
    @Suppress("UNUSED_PARAMETER")
    fun computeTargetWidth(
        screenWidthPx: Int,
        nativeSamples: List<Pair<Int, Int>>,
        foldMode: Int,
        manualWidth: Int,
    ): Int {
        // AUTO/FULL use the complete viewport. Explicit width remains a user choice.
        if (foldMode == FOLD_FULL) return screenWidthPx
        if (foldMode > 0) return minOf(screenWidthPx, manualWidth.coerceIn(1360, 2880))
        return screenWidthPx
    }
}
