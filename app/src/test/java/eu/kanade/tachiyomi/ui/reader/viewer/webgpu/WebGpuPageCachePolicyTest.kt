package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same-key page-table refresh semantics for [WebGpuViewer.getPage] (DUPLICATE_PAGE_AUDIT_20261103
 * candidate 1): a wrapper built for an older ReaderPage must not stand in for a new one.
 */
class WebGpuPageCachePolicyTest {

    @Test fun sameReaderPageInstanceIsReused() {
        val page = ReaderPage(0, url = "u0", imageUrl = "i0")
        assertTrue(WebGpuPageCachePolicy.shouldReuse(page, page, isProtected = false))
    }

    @Test fun reloadedChapterPageWithSameIndexIsNotReused() {
        val old = ReaderPage(0, url = "u0", imageUrl = "i0")
        val reloaded = ReaderPage(0, url = "u0", imageUrl = "i0")
        assertFalse(WebGpuPageCachePolicy.shouldReuse(old, reloaded, isProtected = false))
    }

    @Test fun replacedPageTableWithDifferentUrlIsNotReused() {
        val old = ReaderPage(0, url = "u0", imageUrl = "old")
        val replaced = ReaderPage(0, url = "u0b", imageUrl = "new")
        assertFalse(WebGpuPageCachePolicy.shouldReuse(old, replaced, isProtected = false))
    }

    @Test fun shownOrPinnedStaleWrapperIsKeptUntilItLeavesTheScreen() {
        val old = ReaderPage(3, url = "u3")
        val reloaded = ReaderPage(3, url = "u3")
        assertTrue(WebGpuPageCachePolicy.shouldReuse(old, reloaded, isProtected = true))
    }
}
