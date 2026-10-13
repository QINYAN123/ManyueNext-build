package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/** Initial width is applied before per-book restoration. Subsequent generic image updates
 * must not overwrite the restored/manual viewport unless the width preference itself changed. */
internal class WebGpuContinuousWidth(initialWidth: Int) {
    private var appliedWidth = initialWidth
    val initialScale = initialWidth / 100f

    fun changedScale(width: Int): Float? {
        if (width == appliedWidth) return null
        appliedWidth = width
        return width / 100f
    }
}
