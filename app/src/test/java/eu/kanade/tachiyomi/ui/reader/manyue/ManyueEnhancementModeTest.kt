package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueEnhancementModeTest {
    @Test fun `fromInt maps known values`() {
        assertEquals(ManyueEnhancementMode.OFF, ManyueEnhancementMode.fromInt(0))
        assertEquals(ManyueEnhancementMode.CLASSIC, ManyueEnhancementMode.fromInt(1))
        assertEquals(ManyueEnhancementMode.AI_2X, ManyueEnhancementMode.fromInt(2))
        assertEquals(ManyueEnhancementMode.AI_2X_CLASSIC, ManyueEnhancementMode.fromInt(3))
    }
    @Test fun `unknown falls back to OFF`() {
        assertEquals(ManyueEnhancementMode.OFF, ManyueEnhancementMode.fromInt(99))
        assertEquals(ManyueEnhancementMode.OFF, ManyueEnhancementMode.fromInt(-1))
    }
}
