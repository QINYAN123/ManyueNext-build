package eu.kanade.tachiyomi.ui.reader.manyue

/** Converts source coordinates so enhancement does not move the visible screen region. */
object ManyueViewportPolicy {
    data class Viewport(val scale: Float, val centerX: Float, val centerY: Float)

    fun rescale(
        scale: Float,
        centerX: Float,
        centerY: Float,
        oldWidth: Int,
        oldHeight: Int,
        newWidth: Int,
        newHeight: Int,
    ): Viewport? {
        if (oldWidth <= 0 || oldHeight <= 0 || newWidth <= 0 || newHeight <= 0 ||
            !scale.isFinite() || scale <= 0f || !centerX.isFinite() || !centerY.isFinite()
        ) return null
        return Viewport(
            scale * oldWidth.toFloat() / newWidth,
            centerX * newWidth.toFloat() / oldWidth,
            centerY * newHeight.toFloat() / oldHeight,
        )
    }
}
