package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/**
 * Decides whether [WebGpuViewer]'s page cache may hand back a wrapper it already holds for a
 * `(chapterId, pageIndex)` key. Kept free of Android/GPU types so it is host-testable.
 */
internal object WebGpuPageCachePolicy {

    /**
     * @param cachedPage the reader page the cached wrapper was built for
     * @param requestedPage the reader page now being asked for under the same key
     * @param isProtected the cached wrapper is the shown page or is drawn by a running page turn;
     * swapping it out would blank or flicker what is on screen, so it keeps the old behaviour
     * and the stale wrapper is replaced once it leaves the screen.
     */
    fun <T : Any> shouldReuse(cachedPage: T, requestedPage: T, isProtected: Boolean): Boolean =
        cachedPage === requestedPage || isProtected
}
