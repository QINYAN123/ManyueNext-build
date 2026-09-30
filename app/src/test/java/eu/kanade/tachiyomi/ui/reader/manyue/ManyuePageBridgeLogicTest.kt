package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyuePageBridgeLogicTest {

    @Test fun `identity equality distinguishes pages`() {
        val a = ManyuePageBridge.Identity(1, 2, 3)
        val b = ManyuePageBridge.Identity(1, 2, 3)
        val c = ManyuePageBridge.Identity(1, 2, 4)
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test fun `runtime state defaults to OFF`() {
        assertEquals(0, ManyueRuntimeState.modeInt)
    }

    @Test fun `OFF mode maps correctly`() {
        assertEquals(ManyueEnhancementMode.OFF, ManyueEnhancementMode.fromInt(0))
        assertEquals(ManyueEnhancementMode.AI_2X, ManyueEnhancementMode.fromInt(2))
        assertEquals(ManyueEnhancementMode.AI_2X_CLASSIC, ManyueEnhancementMode.fromInt(3))
    }

    @Test fun `classic combined strength scales and floors at 1`() {
        // AI+classic: level 1 -> round(0.45)=0 -> max(1,0)=1
        assertEquals(1, ManyueClassicEnhancer.effectiveLevel(1, true))
        assertEquals(11, ManyueClassicEnhancer.effectiveLevel(25, true))
        assertEquals(25, ManyueClassicEnhancer.effectiveLevel(25, false))
        assertEquals(0, ManyueClassicEnhancer.effectiveLevel(0, true))
        assertEquals(0, ManyueClassicEnhancer.effectiveLevel(0, false))
    }

    @Test fun `segment heights manifest build helper`() {
        val m = ManyueEnhancementCache.buildJsonObject(
            "parts" to 3, "w" to 2344, "h" to 5000,
        )
        assertTrue(m.contains("\"parts\":3"))
        assertTrue(m.contains("\"w\":2344"))
    }

    @Test fun `display details merge without losing partial result warnings`() {
        assertEquals(
            "Anime4KCPP 未生效；部分高清分块读取失败",
            ManyuePageBridge.mergeDisplayDetails(
                " Anime4KCPP 未生效 ",
                "部分高清分块读取失败",
                "Anime4KCPP 未生效",
            ),
        )
        assertNull(ManyuePageBridge.mergeDisplayDetails(null, " "))
    }
}
