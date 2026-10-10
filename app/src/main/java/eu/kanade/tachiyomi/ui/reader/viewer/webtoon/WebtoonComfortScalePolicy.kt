package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import kotlin.math.ceil

/** Pure math for keeping a shrunk webtoon centered inside the current viewport. */
internal object WebtoonComfortScalePolicy {
    const val MIN_SCALE = 0.5f
    const val DEFAULT_SCALE = 1f

    fun rememberedScale(scale: Float): Float? =
        scale.takeIf { it.isFinite() && it >= MIN_SCALE && it < DEFAULT_SCALE }

    fun contentHeight(viewportHeight: Int, scale: Float): Int {
        if (viewportHeight <= 0) return 0
        val safeScale = scale.coerceIn(MIN_SCALE, DEFAULT_SCALE)
        return ceil(viewportHeight / safeScale.toDouble()).toInt()
    }

    fun verticalOffset(viewportHeight: Int, contentHeight: Int): Float =
        (viewportHeight - contentHeight) / 2f
}
