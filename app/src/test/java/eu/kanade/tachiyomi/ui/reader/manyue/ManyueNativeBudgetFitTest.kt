package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Over-budget pages keep full-resolution input; only the native output is area-resampled. */
class ManyueNativeBudgetFitTest {
    @Test fun pageThatAlreadyFitsNeedsNoFit() {
        assertNull(ManyueAiSafetyPolicy.outputBudgetWidth(800, 1200))
        // Exactly at the limit: 2x = 12,000,000 pixels.
        assertNull(ManyueAiSafetyPolicy.outputBudgetWidth(1000, 3000))
    }

    @Test fun tallWebtoonPageGetsLargestOutputThatFits() {
        // v99 baseline page: full 2x would be 2400x7312 = 17.5 MP.
        val w = ManyueAiSafetyPolicy.outputBudgetWidth(1200, 3656)!!
        assertEquals(1984, w)
        val h = ManyueAiUpscaler.targetHeight(1200, 3656, w)
        assertEquals(6045, h)
        assertTrue(w.toLong() * h <= ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS)
        val h1 = ManyueAiUpscaler.targetHeight(1200, 3656, w + 1)
        assertTrue((w + 1).toLong() * h1 > ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS)
        // Full-resolution input passes the native safety check with this target.
        assertTrue(ManyueAiSafetyPolicy.isNativeWorkSafe(ManyueAiModel.QUALITY_NATIVE2, 1200, 3656, w))
    }

    @Test fun fitThatWouldNotWidenThePageKeepsRejection() {
        // 1000x11000: the largest fitting output (~1044 wide) is under the 5% gain.
        assertNull(ManyueAiSafetyPolicy.outputBudgetWidth(1000, 11_000))
    }

    @Test fun nativeIntermediateBeyondItsLimitIsNotFitted() {
        // 1200x5000 -> 2x 24 MP: at the native limit, fitted (device-tested 1697x7071).
        assertEquals(1697, ManyueAiSafetyPolicy.outputBudgetWidth(1200, 5000))
        // 1200x5001 -> 2x just over 24 MP: refused, the page keeps its original.
        assertNull(ManyueAiSafetyPolicy.outputBudgetWidth(1200, 5001))
    }

    @Test fun invalidInputsAreNotFitted() {
        assertNull(ManyueAiSafetyPolicy.outputBudgetWidth(0, 100))
        assertNull(ManyueAiSafetyPolicy.outputBudgetWidth(100, -1))
        assertNull(ManyueAiSafetyPolicy.outputBudgetWidth(100, 100, maxDisplayPixels = 0))
    }
}

class ManyueDisplayFitTest {
    @Test fun wideOutputIsFittedToReadingWidth() {
        assertEquals(1045 to 1613, ManyueAiSafetyPolicy.displayFit(1200, 1852, 2090))
    }

    @Test fun outputAlreadyNarrowerThanDisplayIsUnchanged() {
        assertNull(ManyueAiSafetyPolicy.displayFit(690, 2000, 2090))
        // Within 5% of the display width: not worth a separate path.
        assertNull(ManyueAiSafetyPolicy.displayFit(1080, 2000, 2090))
    }

    @Test fun fitNotWiderThanOriginalIsRejected() {
        // Display barely wider than the source: fitted 2x output would not widen the page.
        assertNull(ManyueAiSafetyPolicy.displayFit(2000, 3000, 2050))
        assertNull(ManyueAiSafetyPolicy.displayFit(1200, 1852, 0))
    }
}
