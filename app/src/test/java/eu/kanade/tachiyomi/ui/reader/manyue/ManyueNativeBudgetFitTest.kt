package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManyueNativeBudgetFitTest {
    private val budget = ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS

    @Test fun pageThatAlreadyFitsNeedsNoFit() {
        assertNull(ManyueAiSafetyPolicy.nativeBudgetFit(800, 1200, budget))
        // Exactly at the limit: 2x = 12,000,000 pixels.
        assertNull(ManyueAiSafetyPolicy.nativeBudgetFit(1000, 3000, budget))
    }

    @Test fun tallWebtoonPageIsFittedInsteadOfRejected() {
        // v99 baseline page: 2x would be 2400x7312 = 17.5 MP.
        val fit = ManyueAiSafetyPolicy.nativeBudgetFit(1200, 3656, budget)!!
        val (w, h) = fit
        assertTrue("2x output must fit", (2L * w) * (2L * h) <= budget)
        assertTrue("fit must stay below source", w < 1200)
        assertTrue("2x output still wider than original", 2 * w > 1200)
        // Aspect ratio preserved to within one pixel of rounding.
        val expectedH = (3656L * w + 600) / 1200
        assertEquals(expectedH, h.toLong())
        // Tight: one pixel wider would exceed the budget.
        val h1 = ((3656L * (w + 1) + 600) / 1200)
        assertTrue((2L * (w + 1)) * (2L * h1) > budget)
    }

    @Test fun fitThatWouldNotWidenThePageKeepsRejection() {
        // 1000x11000 = 11 MP source: fitted 2x width would be ~1044, under 5% gain.
        assertNull(ManyueAiSafetyPolicy.nativeBudgetFit(1000, 11_000, budget))
    }

    @Test fun invalidInputsAreNotFitted() {
        assertNull(ManyueAiSafetyPolicy.nativeBudgetFit(0, 100, budget))
        assertNull(ManyueAiSafetyPolicy.nativeBudgetFit(100, -1, budget))
        assertNull(ManyueAiSafetyPolicy.nativeBudgetFit(100, 100, 0))
    }

    @Test fun fittedRequestPassesEveryNativeSafetyCheck() {
        val (w, h) = ManyueAiSafetyPolicy.nativeBudgetFit(1200, 3656, budget)!!
        assertTrue(ManyueAiSafetyPolicy.isNativeWorkSafe(ManyueAiModel.QUALITY_NATIVE2, w, h, 2 * w))
        assertEquals(2 * h, ManyueAiUpscaler.targetHeight(w, h, 2 * w))
    }
}
