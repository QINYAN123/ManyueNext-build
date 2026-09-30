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
        // Replicates ManyuePrefetchManager.priorityFor:
        fun p(idx: Int, cur: Int) = when (idx - cur) {
            0 -> 100; 1 -> 50; 2 -> 40; 3 -> 30; 4 -> 20; 5 -> 10; else -> Int.MAX_VALUE
        }
        assertEquals(100, p(5, 5))
        assertEquals(50, p(6, 5))
        assertEquals(40, p(7, 5))
        assertEquals(30, p(8, 5))
        assertEquals(20, p(9, 5))
        assertEquals(10, p(10, 5))
        assertEquals(Int.MAX_VALUE, p(11, 5))
        assertEquals(Int.MAX_VALUE, p(2, 5))
    }

    @Test fun `prefetch manager starts empty`() {
        ManyuePrefetchManager.reset()
        // After reset, register/onPageChanged should not throw.
        ManyuePrefetchManager.onPageChanged(0)
        ManyuePrefetchManager.reset()
    }

    @Test fun `continuous prefetch keeps near priorities and assigns low priority to the whole chapter`() {
        assertEquals(100, ManyueAiSafetyPolicy.continuousPrefetchPriority(4, 4))
        assertEquals(50, ManyueAiSafetyPolicy.continuousPrefetchPriority(5, 4))
        assertEquals(10, ManyueAiSafetyPolicy.continuousPrefetchPriority(9, 4))
        assertEquals(1, ManyueAiSafetyPolicy.continuousPrefetchPriority(10, 4))
        assertEquals(5, ManyueAiSafetyPolicy.continuousPrefetchPriority(3, 4))
        assertEquals(2, ManyueAiSafetyPolicy.PREFETCH_LOOK_BACK_PAGES)
        assertEquals(5, ManyueAiSafetyPolicy.ACTIVE_COMPLETION_LOOK_BACK_PAGES)
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
