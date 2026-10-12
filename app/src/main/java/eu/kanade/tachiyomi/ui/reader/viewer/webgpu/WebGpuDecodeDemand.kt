package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/** Refresh original-image demand when the viewport changes without crossing a page boundary. */
internal class WebGpuDecodeDemand<T : Any>(
    private val preloadWindow: (T) -> Unit,
    private val prioritizeVisible: (T) -> Unit,
) {
    private data class Window<T>(val anchor: T, val width: Int, val height: Int, val above: Int, val below: Int)
    private var window: Window<T>? = null
    private var visible = emptyList<T>()

    fun update(anchor: T?, width: Int, height: Int, above: Int, below: Int, pages: List<T>) {
        if (anchor == null || width <= 0 || height <= 0) return
        val next = Window(anchor, width, height, above, below)
        val changed = next != window
        window = next
        if (changed) preloadWindow(anchor)
        if (changed || pages != visible) {
            visible = pages.toList()
            // The decoder takes the last prioritized page first: topmost visible wins.
            pages.asReversed().forEach(prioritizeVisible)
        }
    }

    fun reset() {
        window = null
        visible = emptyList()
    }
}
