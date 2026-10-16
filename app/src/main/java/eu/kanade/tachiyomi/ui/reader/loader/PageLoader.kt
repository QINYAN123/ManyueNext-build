package eu.kanade.tachiyomi.ui.reader.loader

import androidx.annotation.CallSuper
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * A loader used to load pages into the reader. Any open resources must be cleaned up when the
 * method [recycle] is called.
 */
abstract class PageLoader {

    /**
     * Whether this loader has been already recycled.
     */
    var isRecycled = false
        private set

    abstract var isLocal: Boolean

    /**
     * Returns the list of pages of a chapter.
     */
    abstract suspend fun getPages(): List<ReaderPage>

    /**
     * Loads the page. May also preload other pages.
     * Progress of the page loading should be followed via [page.statusFlow].
     * [loadPage] is not currently guaranteed to complete, so it should be launched asynchronously.
     */
    open suspend fun loadPage(page: ReaderPage) {}

    /** Opens an encoded source on IO. Online loaders may recover an evicted cache entry. */
    open suspend fun openPageStream(page: ReaderPage): InputStream? = page.stream?.invoke()

    /** Only recover a proven missing online cache file, never a bad decoder/permission error. */
    open fun recoverMissingSource(page: ReaderPage, error: FileNotFoundException): Boolean = false

    /**
     * Retries the given [page] in case it failed to load. This method only makes sense when an
     * online source is used.
     */
    open fun retryPage(page: ReaderPage) {}

    /**
     * Recycles this loader. Implementations must override this method to clean up any active
     * resources.
     */
    @CallSuper
    open fun recycle() {
        isRecycled = true
    }
}
