package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueFoldableWidthPolicyTest {

    @Test fun `small phone is not inner screen`() {
        assertEquals(false, ManyueFoldableWidthPolicy.isInnerScreen(360))
        assertEquals(false, ManyueFoldableWidthPolicy.isInnerScreen(599))
    }

    @Test fun `tablet is inner screen`() {
        assertTrue(ManyueFoldableWidthPolicy.isInnerScreen(600))
        assertTrue(ManyueFoldableWidthPolicy.isInnerScreen(840))
    }

    @Test fun `FULL mode returns screen width`() {
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(2200, emptyList(), ManyueFoldableWidthPolicy.FOLD_FULL, 0)
        assertEquals(2200, w)
    }

    @Test fun `manual mode clamps width`() {
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(2200, emptyList(), 2000, 2000)
        assertEquals(2000, w)
    }

    @Test fun `auto with no samples falls back`() {
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(1200, emptyList(), ManyueFoldableWidthPolicy.FOLD_AUTO, 0)
        assertEquals(1200, w) // min(1920, screen)
    }

    @Test fun `extreme ratio uses full width`() {
        // very tall strip -> ratio > 2.35 -> full width
        val samples = listOf(400 to 1200, 500 to 1500)
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(2000, samples, ManyueFoldableWidthPolicy.FOLD_AUTO, 0)
        assertEquals(2000, w)
    }

    @Test fun `normal ratio narrows to at least 1632`() {
        val samples = listOf(1000 to 1400, 1100 to 1500) // ~1.36 ratio
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(2400, samples, ManyueFoldableWidthPolicy.FOLD_AUTO, 0)
        assertTrue(w in 1632..2344)
    }

    @Test fun `auto uses lower median for even sample count`() {
        val samples = listOf(1000 to 1500, 2000 to 3000)
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(3000, samples, ManyueFoldableWidthPolicy.FOLD_AUTO, 0)
        assertEquals(1632, w)
    }

    @Test fun `auto is not capped by AI target width`() {
        val samples = listOf(2400 to 3600)
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(3000, samples, ManyueFoldableWidthPolicy.FOLD_AUTO, 0)
        assertEquals(2880, w)
    }

    @Test fun `manual never exceeds current screen`() {
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(1800, emptyList(), 2500, 2500)
        assertEquals(1800, w)
    }
}
