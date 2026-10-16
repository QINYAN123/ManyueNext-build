package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/**
 * Selects the AI reading head from visible continuous-viewer pages, supplied in screen order.
 *
 * Continuous strips can show several pages at once, and the bottom page may only be partly
 * visible. Keep screen order as the source of truth: page indexes restart at chapter boundaries
 * and do not encode visual order while reading in reverse. The input is the already-clipped
 * visible-image list, which includes any page whose viewport overlap is positive.
 */
internal object WebGpuViewportAiHead {
    // Reuse the viewer's ordered owner set rather than allocate another plan on every frame.
    fun <T : Any> head(visibleTopToBottom: Iterable<T>, fallback: T?): T? =
        visibleTopToBottom.lastOrNull() ?: fallback
}
