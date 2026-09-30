package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueClassicEnhancerTest {

    @Test fun `effective level pure classic equals strength`() {
        assertEquals(25, ManyueClassicEnhancer.effectiveLevel(25, false))
        assertEquals(0, ManyueClassicEnhancer.effectiveLevel(0, false))
    }

    @Test fun `effective level AI combined scales and floors at 1`() {
        assertEquals(1, ManyueClassicEnhancer.effectiveLevel(1, true))   // round(0.45)=0 -> max(1,0)=1
        assertEquals(11, ManyueClassicEnhancer.effectiveLevel(25, true)) // round(11.25)=11
    }

    @Test fun `sharpen delta grows with level`() {
        val d0 = ManyueClassicEnhancer.sharpenDelta(0.0)
        val d1 = ManyueClassicEnhancer.sharpenDelta(1.0)
        assertEquals(0.0, d0, 1e-9)
        assertTrue(d1 > d0)
    }

    @Test fun `contrast saturate brightness monotonic`() {
        val e = 0.5
        assertTrue(ManyueClassicEnhancer.contrast(e) > 1.0)
        assertTrue(ManyueClassicEnhancer.saturate(e) > 1.0)
        assertTrue(ManyueClassicEnhancer.brightness(e) > 1.0)
    }
}
