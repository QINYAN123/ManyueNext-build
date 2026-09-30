package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ManyueRenderRegressionTest {
    @Test fun `custom scale keeps source dimensions at one and supports hundredth steps`() {
        assertEquals(690, ManyueAiUpscaler.customTargetWidth(690, 100))
        assertEquals(863, ManyueAiUpscaler.customTargetWidth(690, 125))
        assertEquals(1042, ManyueAiUpscaler.customTargetWidth(690, 151))
        assertEquals(1380, ManyueAiUpscaler.customTargetWidth(690, 200))
    }

    @Test fun `custom scale clamps invalid settings and never reduces large original`() {
        assertEquals(800, ManyueAiUpscaler.customTargetWidth(800, -100))
        assertEquals(1600, ManyueAiUpscaler.customTargetWidth(800, 500))
        assertEquals(4000, ManyueAiUpscaler.customTargetWidth(2000, 200))
        assertEquals(8000, ManyueAiUpscaler.customTargetWidth(4000, 200))
    }

    @Test fun `replacement keeps screen position and magnification`() {
        val viewport = requireNotNull(ManyueViewportPolicy.rescale(2f, 250f, 600f, 1000, 2000, 1500, 3000))
        assertEquals(4f / 3f, viewport.scale, 0.0001f)
        assertEquals(375f, viewport.centerX)
        assertEquals(900f, viewport.centerY)
        assertEquals(500f, viewport.centerX * viewport.scale, 0.001f)
        assertEquals(1200f, viewport.centerY * viewport.scale, 0.001f)
    }

    @Test fun `replacement uses actual rounded height for odd sized pages`() {
        val viewport = requireNotNull(ManyueViewportPolicy.rescale(1f, 345f, 1000f, 690, 2001, 1035, 3002))
        assertEquals(517.5f, viewport.centerX)
        assertEquals(1000f / 2001 * 3002, viewport.centerY, 0.001f)
    }

    @Test fun `invalid viewport falls back to initial zoom`() {
        assertNull(ManyueViewportPolicy.rescale(1f, 0f, 0f, 0, 10, 10, 20))
        assertNull(ManyueViewportPolicy.rescale(Float.NaN, 0f, 0f, 10, 10, 20, 20))
    }
}
