package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Device-free fixture of the scroll/page-walk and read-through equations in
 * reader-webgpu/.../ImageViewerContinuousState.kt (`scrollBy` and `captureLocked`).
 */
class WebGpuContinuousReadThroughFixtureTest {
    private data class FixturePage(
        val name: String,
        val height: Float,
        val chapterId: Long? = null,
        val transition: WebGpuTransitionKey? = null,
    )

    private data class ScrollResult(
        val pageZeroIndex: Int,
        val scrollY: Float,
        val crossed: List<Pair<WebGpuTransitionKey?, Int>>,
    )

    private val chapters31To32 = WebGpuTransitionKey(31L, 32L)

    private fun scrollBy(
        pages: List<FixturePage>,
        startPageZeroIndex: Int,
        delta: Float,
    ): ScrollResult {
        var pageZeroIndex = startPageZeroIndex
        var scrollY = delta
        val crossed = mutableListOf<Pair<WebGpuTransitionKey?, Int>>()

        // Mirrors ImageViewerContinuousState.scrollBy: each negative page step calls onPageChange
        // before it installs the new page zero.
        while (scrollY < 0f && pageZeroIndex > 0) {
            val from = pages[pageZeroIndex]
            val to = pages[pageZeroIndex - 1]
            crossed += (to.transition ?: from.transition) to -1
            pageZeroIndex--
            scrollY += to.height
        }

        // Mirrors the forward walk and its strict `scrollY > pageHeight` boundary.
        while (pageZeroIndex < pages.lastIndex) {
            val current = pages[pageZeroIndex]
            if (scrollY <= current.height || current.height <= 0f) break
            val next = pages[pageZeroIndex + 1]
            crossed += (next.transition ?: current.transition) to 1
            pageZeroIndex++
            scrollY -= current.height
        }

        return ScrollResult(pageZeroIndex, scrollY, crossed)
    }

    private fun readThrough(
        pages: List<FixturePage>,
        scroll: ScrollResult,
        viewportHeight: Float,
    ): String? {
        var y = -scroll.scrollY
        var result: String? = null
        for (index in scroll.pageZeroIndex..pages.lastIndex) {
            val page = pages[index]
            if (page.height > 0f && y + page.height <= viewportHeight + 0.5f) {
                result = page.name
            }
            y += page.height
        }
        return result
    }

    @Test
    fun toolbarAnchorReversePanReportsPreviousTransitionButPolicyRejectsIt() {
        val pages = listOf(
            FixturePage("chapter31-last", height = 1_100f, chapterId = 31L),
            FixturePage("31-to-32-transition", height = 1_080f, transition = chapters31To32),
            FixturePage("chapter32-first", height = 3_000f, chapterId = 32L),
        )
        val scroll = scrollBy(pages, startPageZeroIndex = 2, delta = -2f)
        val report = readThrough(pages, scroll, viewportHeight = 2_400f)
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()
        scroll.crossed.forEach { (key, direction) -> policy.recordPageStep(key, direction, epoch) }

        assertEquals(1, scroll.pageZeroIndex)
        assertEquals(1_078f, scroll.scrollY, 0f)
        assertEquals("31-to-32-transition", report)
        val anchorChapterId = pages[scroll.pageZeroIndex].chapterId
        assertFalse(policy.claimTransitionProgress(chapters31To32, epoch, anchorChapterId))
    }

    @Test
    fun naturalForwardCrossingReportsTransitionAndAllowsPreviousChapterCompletion() {
        val pages = listOf(
            FixturePage("chapter31-last", height = 1_100f, chapterId = 31L),
            FixturePage("31-to-32-transition", height = 1_080f, transition = chapters31To32),
            FixturePage("chapter32-first", height = 3_000f, chapterId = 32L),
        )
        val scroll = scrollBy(pages, startPageZeroIndex = 0, delta = 1_102f)
        val report = readThrough(pages, scroll, viewportHeight = 2_400f)
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()
        scroll.crossed.forEach { (key, direction) -> policy.recordPageStep(key, direction, epoch) }

        assertEquals(1, scroll.pageZeroIndex)
        assertEquals(2f, scroll.scrollY, 0f)
        assertEquals("31-to-32-transition", report)
        val anchorChapterId = pages[scroll.pageZeroIndex].chapterId
        assertTrue(policy.claimTransitionProgress(chapters31To32, epoch, anchorChapterId))
    }

    @Test
    fun previousChapterAnchorCanReportTransitionWithoutCrossingPageZero() {
        val pages = listOf(
            FixturePage("chapter31-last", height = 1_100f, chapterId = 31L),
            FixturePage("31-to-32-transition", height = 1_080f, transition = chapters31To32),
            FixturePage("chapter32-first", height = 3_000f, chapterId = 32L),
        )
        val scroll = scrollBy(pages, startPageZeroIndex = 0, delta = 0f)
        val report = readThrough(pages, scroll, viewportHeight = 2_400f)
        val anchorChapterId = pages[scroll.pageZeroIndex].chapterId // actual mapped page-zero owner
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()
        scroll.crossed.forEach { (key, direction) -> policy.recordPageStep(key, direction, epoch) }

        assertEquals(0, scroll.pageZeroIndex)
        assertEquals("31-to-32-transition", report)
        assertTrue(policy.claimTransitionProgress(chapters31To32, epoch, anchorChapterId))
    }

    @Test
    fun reverseReturnToOldLastImageUsesActualOldChapterAnchor() {
        val pages = listOf(
            FixturePage("chapter31-last", height = 1_100f, chapterId = 31L),
            FixturePage("31-to-32-transition", height = 1_080f, transition = chapters31To32),
            FixturePage("chapter32-first", height = 3_000f, chapterId = 32L),
        )
        val scroll = scrollBy(pages, startPageZeroIndex = 2, delta = -1_082f)
        val report = readThrough(pages, scroll, viewportHeight = 2_400f)
        val anchorChapterId = pages[scroll.pageZeroIndex].chapterId // actual mapped page-zero owner
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()
        scroll.crossed.forEach { (key, direction) -> policy.recordPageStep(key, direction, epoch) }

        assertEquals(0, scroll.pageZeroIndex)
        assertEquals(1_098f, scroll.scrollY, 0f)
        assertEquals("31-to-32-transition", report)
        assertTrue(policy.claimTransitionProgress(chapters31To32, epoch, anchorChapterId))
    }

    @Test
    fun terminalTransitionStillReportsFinalChapterCompletion() {
        val terminalTransition = WebGpuTransitionKey(previousChapterId = 42L, nextChapterId = null)
        val pages = listOf(
            FixturePage("chapter42-last", height = 1_100f, chapterId = 42L),
            FixturePage("chapter42-terminal-transition", height = 1_080f, transition = terminalTransition),
        )
        val scroll = scrollBy(pages, startPageZeroIndex = 0, delta = 1_102f)
        val report = readThrough(pages, scroll, viewportHeight = 2_400f)
        val policy = WebGpuProgressSelectionPolicy()
        val epoch = policy.reset()
        scroll.crossed.forEach { (key, direction) -> policy.recordPageStep(key, direction, epoch) }
        val anchorChapterId = pages[scroll.pageZeroIndex].chapterId

        assertEquals(1, scroll.pageZeroIndex)
        assertEquals("chapter42-terminal-transition", report)
        assertTrue(policy.claimTransitionProgress(terminalTransition, epoch, anchorChapterId))
    }
}
