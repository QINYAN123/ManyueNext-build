package eu.kanade.tachiyomi.ui.reader.manyue

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyuePrefetchPolicyTest {

    @Test fun `regular phone is not inner screen`() {
        assertFalse(ManyueFoldableWidthPolicy.isInnerScreen(360))
        assertFalse(ManyueFoldableWidthPolicy.isInnerScreen(599))
    }

    @Test fun `inner screen threshold`() {
        assertTrue(ManyueFoldableWidthPolicy.isInnerScreen(600))
        assertTrue(ManyueFoldableWidthPolicy.isInnerScreen(840))
    }

    @Test fun `non-inner phone returns screen width unchanged`() {
        // isFoldable() false -> suggestedTargetWidth returns screenWidthPx.
        // Policy.computeTargetWidth with samples but foldMode=FULL also returns screen width.
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(
            1200, listOf(1000 to 1400), ManyueFoldableWidthPolicy.FOLD_FULL, 0,
        )
        assertEquals(1200, w)
    }

    @Test fun `priority window arithmetic`() {
        assertEquals(100, ManyueAiSafetyPolicy.priorityFor(5, 5))
        assertEquals(50, ManyueAiSafetyPolicy.prefetchPriority(6, 5, ManyueAiSafetyPolicy.PrefetchDirection.FORWARD))
        assertEquals(40, ManyueAiSafetyPolicy.prefetchPriority(7, 5, ManyueAiSafetyPolicy.PrefetchDirection.FORWARD))
        assertEquals(30, ManyueAiSafetyPolicy.prefetchPriority(8, 5, ManyueAiSafetyPolicy.PrefetchDirection.FORWARD))
        assertEquals(20, ManyueAiSafetyPolicy.prefetchPriority(9, 5, ManyueAiSafetyPolicy.PrefetchDirection.FORWARD))
        assertEquals(10, ManyueAiSafetyPolicy.prefetchPriority(10, 5, ManyueAiSafetyPolicy.PrefetchDirection.FORWARD))
        assertNull(ManyueAiSafetyPolicy.prefetchPriority(11, 5, ManyueAiSafetyPolicy.PrefetchDirection.FORWARD))
        assertNull(ManyueAiSafetyPolicy.prefetchPriority(2, 5, ManyueAiSafetyPolicy.PrefetchDirection.FORWARD))
    }

    @Test fun `prefetch manager starts empty`() {
        ManyuePrefetchManager.reset()
        // After reset, register/onPageChanged should not throw.
        ManyuePrefetchManager.onPageChanged(0)
        ManyuePrefetchManager.reset()
    }

    @Test fun `continuous admission keeps future work but drops passed invisible requests`() {
        val forward = ManyueAiSafetyPolicy.PrefetchDirection.FORWARD
        val backward = ManyueAiSafetyPolicy.PrefetchDirection.BACKWARD
        assertEquals(50, ManyueAiSafetyPolicy.futureRequestPriority(11, 10, forward))
        assertEquals(1, ManyueAiSafetyPolicy.futureRequestPriority(30, 10, forward))
        assertEquals(1, ManyueAiSafetyPolicy.futureRequestPriority(0, 20, backward))
        assertNull(ManyueAiSafetyPolicy.futureRequestPriority(9, 10, forward))
        assertNull(ManyueAiSafetyPolicy.futureRequestPriority(21, 20, backward))
        assertNull(ManyueAiSafetyPolicy.futureRequestPriority(-1, 20, backward))
        assertNull(ManyueAiSafetyPolicy.futureRequestPriority(10, 10, forward))
        assertEquals(100, ManyueAiSafetyPolicy.managedRequestPriority(false, true, null, false),
            "a partially visible previous page is still needed")
        assertNull(ManyueAiSafetyPolicy.managedRequestPriority(false, false, null, false),
            "a completely passed page cannot use the active-work escape hatch")
    }

    @Test fun `directional window and active completion are symmetric`() {
        val forward = ManyueAiSafetyPolicy.PrefetchDirection.FORWARD
        val backward = ManyueAiSafetyPolicy.PrefetchDirection.BACKWARD

        assertEquals(100, ManyueAiSafetyPolicy.continuousPrefetchPriority(4, 4))
        assertEquals(50, ManyueAiSafetyPolicy.continuousPrefetchPriority(5, 4, forward))
        assertEquals(10, ManyueAiSafetyPolicy.continuousPrefetchPriority(9, 4, forward))
        assertEquals(1, ManyueAiSafetyPolicy.continuousPrefetchPriority(10, 4, forward))
        assertEquals(50, ManyueAiSafetyPolicy.prefetchPriority(9, 10, backward))
        assertEquals(10, ManyueAiSafetyPolicy.prefetchPriority(5, 10, backward))
        assertNull(ManyueAiSafetyPolicy.prefetchPriority(4, 10, backward))
        assertEquals(forward, ManyueAiSafetyPolicy.directionAfterSelection(4, 5, backward))
        assertEquals(backward, ManyueAiSafetyPolicy.directionAfterSelection(5, 4, forward))
        assertEquals(backward, ManyueAiSafetyPolicy.directionAfterSelection(4, 4, backward))
        assertTrue(ManyueAiSafetyPolicy.shouldFinishActiveRequest(5, 10, isProcessing = true))
        assertTrue(ManyueAiSafetyPolicy.shouldFinishActiveRequest(15, 10, isProcessing = true))
        assertFalse(ManyueAiSafetyPolicy.shouldFinishActiveRequest(4, 10, isProcessing = true))
        assertFalse(ManyueAiSafetyPolicy.shouldFinishActiveRequest(5, 10, isProcessing = false))
        assertEquals(120, ManyueAiSafetyPolicy.managedRequestPriority(true, true, 50, true))
        assertEquals(100, ManyueAiSafetyPolicy.managedRequestPriority(false, true, 50, true))
        assertEquals(50, ManyueAiSafetyPolicy.managedRequestPriority(false, false, 50, false))
        assertEquals(1, ManyueAiSafetyPolicy.managedRequestPriority(false, false, null, true))
        assertNull(ManyueAiSafetyPolicy.managedRequestPriority(false, false, null, false))
        assertEquals(5, ManyueAiSafetyPolicy.ACTIVE_COMPLETION_LOOK_BACK_PAGES)
        assertEquals(5, ManyueAiSafetyPolicy.PREFETCH_WINDOW_PAGES)
    }

    @Test fun `continuous prefetch rejects invalid reader positions through caller bounds`() {
        assertEquals(1, ManyueAiSafetyPolicy.continuousPrefetchPriority(100, 0))
        assertEquals(1, ManyueAiSafetyPolicy.continuousPrefetchPriority(0, 100))
        assertEquals(null, ManyueAiSafetyPolicy.priorityFor(pageIndex = 11, currentIndex = 5))
        assertEquals(null, ManyueAiSafetyPolicy.priorityFor(pageIndex = 4, currentIndex = 5))
    }

    @Test fun `current decision history stays bounded and recent backscroll skips the wait`() = runBlocking {
        val chapterId = 704L
        ManyuePrefetchManager.reset()
        try {
            for (pageIndex in 0 until 40) {
                ManyuePrefetchManager.onPageChanged(chapterId, pageIndex)
                ManyuePrefetchManager.releaseCurrent(chapterId, pageIndex)
            }

            // The recent 32 entries remain remembered; a visit to the oldest retained page does
            // not recreate the visible-holder decision gate.
            ManyuePrefetchManager.onPageChanged(chapterId, 8)
            withTimeout(500) {
                ManyuePrefetchManager.awaitCurrentDecision(chapterId, 8)
            }

            // The next older page has fallen outside the bound and needs a fresh holder decision.
            ManyuePrefetchManager.onPageChanged(chapterId, 7)
            assertNull(
                withTimeoutOrNull(100) {
                    ManyuePrefetchManager.awaitCurrentDecision(chapterId, 7)
                    true
                },
            )
        } finally {
            ManyuePrefetchManager.reset()
        }
    }
}
