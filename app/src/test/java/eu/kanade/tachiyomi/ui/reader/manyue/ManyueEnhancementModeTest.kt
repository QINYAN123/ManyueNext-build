package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueEnhancementModeTest {
    @Test fun `legacy mode values canonicalize to the supported choices`() {
        assertEquals(ManyueEnhancementMode.OFF, ManyueEnhancementMode.fromInt(0))
        assertEquals(ManyueEnhancementMode.OFF, ManyueEnhancementMode.fromInt(1))
        assertEquals(ManyueEnhancementMode.AI_2X, ManyueEnhancementMode.fromInt(2))
        assertEquals(ManyueEnhancementMode.AI_2X, ManyueEnhancementMode.fromInt(3))
        assertEquals(ManyueEnhancementMode.AUTO, ManyueEnhancementMode.fromInt(4))
    }

    @Test fun `no supported or legacy mode starts classic processing`() {
        ManyueEnhancementMode.entries.forEach { mode ->
            assertFalse(mode.usesClassic(ManyueAiModel.QUALITY_REAL_ESRGAN))
            assertFalse(mode.usesClassic(ManyueAiModel.MOBILE_LITE))
        }
        assertTrue(ManyueEnhancementMode.fromInt(3).usesAi())
        assertFalse(ManyueEnhancementMode.fromInt(1).usesAi())
    }
    @Test fun `unknown falls back to OFF`() {
        assertEquals(ManyueEnhancementMode.OFF, ManyueEnhancementMode.fromInt(99))
        assertEquals(ManyueEnhancementMode.OFF, ManyueEnhancementMode.fromInt(-1))
    }
}
