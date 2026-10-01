package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManyueClassicEnhancerTest {

    @Test
    fun `effective level preserves classic and combined strength rules`() {
        assertEquals(25, ManyueClassicEnhancer.effectiveLevel(25, false))
        assertEquals(11, ManyueClassicEnhancer.effectiveLevel(25, true))
        assertEquals(1, ManyueClassicEnhancer.effectiveLevel(1, true))
        assertEquals(0, ManyueClassicEnhancer.effectiveLevel(0, false))
        assertEquals(0, ManyueClassicEnhancer.effectiveLevel(-5, true))
    }

    @Test
    fun `effective level is bounded to supported strength range`() {
        assertEquals(100, ManyueClassicEnhancer.effectiveLevel(140, false))
        assertEquals(45, ManyueClassicEnhancer.effectiveLevel(140, true))
    }
}
