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

    @Test fun `auto uses full viewport with no samples`() {
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(1200, emptyList(), ManyueFoldableWidthPolicy.FOLD_AUTO, 0)
        assertEquals(1200, w)
    }

    @Test fun `auto source samples do not narrow viewport`() {
        val mixedPageSizes = listOf(1000 to 1400, 400 to 2400, 2200 to 1800)
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(2400, mixedPageSizes, ManyueFoldableWidthPolicy.FOLD_AUTO, 0)
        assertEquals(2400, w)
    }

    @Test fun `auto uses full viewport regardless of source dimensions`() {
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(3000, listOf(2400 to 3600), ManyueFoldableWidthPolicy.FOLD_AUTO, 0)
        assertEquals(3000, w)
    }

    @Test fun `manual never exceeds current screen`() {
        val w = ManyueFoldableWidthPolicy.computeTargetWidth(1800, emptyList(), 2500, 2500)
        assertEquals(1800, w)
    }
}
