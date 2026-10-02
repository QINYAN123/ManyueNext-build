package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueLitePolicyTest {

    @Test
    fun `continuous lite auto mode enhances a sharp one times source`() {
        val lite = ManyueAutoEnhancementPolicy.decide(
            sourceWidth = 1000,
            displayWidth = 1000,
            maximumScalePercent = 200,
            continuousScale = ManyueAiModel.MOBILE_LITE.continuousScale,
        )
        val legacy = ManyueAutoEnhancementPolicy.decide(
            sourceWidth = 1000,
            displayWidth = 1000,
            maximumScalePercent = 200,
            continuousScale = ManyueAiModel.FAST_REAL_CUGAN.continuousScale,
        )

        assertEquals(ManyueAiModel.MOBILE_LITE, ManyueAiModel.DEFAULT)
        assertEquals(ManyueAutoEnhancementPolicy.Decision(ManyueAutoEnhancementPolicy.Path.AI, 1000), lite)
        assertEquals(ManyueAutoEnhancementPolicy.Decision(ManyueAutoEnhancementPolicy.Path.ORIGINAL_SIZE, 1000), legacy)
    }

    @Test
    fun `continuous lite auto mode still waits for the reader width`() {
        val decision = ManyueAutoEnhancementPolicy.decide(
            sourceWidth = 1000,
            displayWidth = 0,
            maximumScalePercent = 200,
            continuousScale = true,
        )

        assertEquals(ManyueAutoEnhancementPolicy.Decision(ManyueAutoEnhancementPolicy.Path.WAIT_FOR_WIDTH, 0), decision)
    }

    @Test
    fun `lite checks its requested output budget without requiring a hypothetical two times image`() {
        val sourceWidth = 2000
        val sourceHeight = 3000

        assertTrue(ManyueAiSafetyPolicy.isNativeWorkSafe(ManyueAiModel.MOBILE_LITE, sourceWidth, sourceHeight, 2000))
        assertFalse(
            ManyueAiSafetyPolicy.isNativeWorkSafe(ManyueAiModel.FAST_REAL_CUGAN, sourceWidth, sourceHeight, 2000),
        )

        // The rounded output is 2828x4242 (under 12 MP); one pixel wider rounds to 2829x4244.
        assertTrue(ManyueAiSafetyPolicy.isNativeWorkSafe(ManyueAiModel.MOBILE_LITE, sourceWidth, sourceHeight, 2828))
        assertFalse(ManyueAiSafetyPolicy.isNativeWorkSafe(ManyueAiModel.MOBILE_LITE, sourceWidth, sourceHeight, 2829))
    }

    @Test
    fun `lite safety rejects invalid scale ratios and invalid source geometry`() {
        val model = ManyueAiModel.MOBILE_LITE

        assertFalse(ManyueAiSafetyPolicy.isNativeWorkSafe(model, 2000, 3000, 0))
        assertFalse(ManyueAiSafetyPolicy.isNativeWorkSafe(model, 2000, 3000, 1999))
        assertFalse(ManyueAiSafetyPolicy.isNativeWorkSafe(model, 2000, 3000, 4001))
        assertFalse(ManyueAiSafetyPolicy.isNativeWorkSafe(model, 0, 3000, 2000))
        assertFalse(ManyueAiSafetyPolicy.isNativeWorkSafe(model, 2000, Int.MAX_VALUE, 2000))
    }

    @Test
    fun `only visible lite work bypasses fast scroll`() {
        val owner = Any()
        try {
            ManyueReaderWorkGate.update(owner, active = true)
            ManyueReaderWorkGate.reportScroll(owner, dx = 0, dy = 1, viewportHeightPx = 400)
            // The pressure policy needs one measured window of at least 8 ms before classifying motion.
            Thread.sleep(12)
            ManyueReaderWorkGate.reportScroll(owner, dx = 0, dy = 5000, viewportHeightPx = 400)

            assertTrue(ManyueReaderWorkGate.isBlocked())
            assertTrue(ManyueReaderWorkGate.isDisplayBlocked())
            assertFalse(ManyueReaderWorkGate.isDisplayBlocked(continuousScale = true))
            assertFalse(ManyueReaderWorkGate.isAiWorkBlocked(ManyueAiModel.MOBILE_LITE, priority = 100))
            assertTrue(ManyueReaderWorkGate.isAiWorkBlocked(ManyueAiModel.MOBILE_LITE, priority = 99))
            assertTrue(ManyueReaderWorkGate.isAiWorkBlocked(ManyueAiModel.FAST_REAL_CUGAN, priority = 100))
        } finally {
            ManyueReaderWorkGate.release(owner)
        }
    }

    @Test
    fun `visible lite work remains blocked by sustained bad frames`() {
        val owner = Any()
        try {
            ManyueReaderWorkGate.update(owner, active = true)
            repeat(3) {
                ManyueReaderWorkGate.reportFrame(
                    owner,
                    frameIntervalNanos = 30_000_000L,
                    expectedIntervalNanos = 16_666_667L,
                )
            }

            assertTrue(ManyueReaderWorkGate.isAiWorkBlocked(ManyueAiModel.MOBILE_LITE, priority = 100))
            assertTrue(ManyueReaderWorkGate.isDisplayBlocked(continuousScale = true))

            repeat(2) {
                ManyueReaderWorkGate.reportFrame(
                    owner,
                    frameIntervalNanos = 16_666_667L,
                    expectedIntervalNanos = 16_666_667L,
                )
            }
            assertFalse(ManyueReaderWorkGate.isAiWorkBlocked(ManyueAiModel.MOBILE_LITE, priority = 100))
            assertFalse(ManyueReaderWorkGate.isDisplayBlocked(continuousScale = true))
        } finally {
            ManyueReaderWorkGate.release(owner)
        }
    }

    @Test
    fun `visible lite work remains blocked at severe thermal status`() {
        val owner = Any()
        try {
            ManyueReaderWorkGate.update(owner, active = false)
            ManyueReaderWorkGate.reportThermalStatus(owner, ManyueReaderWorkGate.THERMAL_STATUS_SEVERE)

            assertTrue(ManyueReaderWorkGate.isAiWorkBlocked(ManyueAiModel.MOBILE_LITE, priority = 100))
        } finally {
            ManyueReaderWorkGate.release(owner)
        }
    }

    @Test
    fun `cache identity separates lite strength and model`() {
        val key = cacheKey(modelId = ManyueAiModel.MOBILE_LITE.id, strength = 60)

        assertEquals(key, cacheKey(modelId = ManyueAiModel.MOBILE_LITE.id, strength = 60))
        assertNotEquals(key, cacheKey(modelId = ManyueAiModel.MOBILE_LITE.id, strength = 61))
        assertNotEquals(key, cacheKey(modelId = ManyueAiModel.FAST_REAL_CUGAN.id, strength = 60))
    }

    @Test
    fun `detail strength invalidates lite requests but not legacy model requests`() {
        val originalModel = ManyueRuntimeState.aiModel
        val originalStrength = ManyueRuntimeState.aiDetailStrength
        try {
            ManyueRuntimeState.updateAiModel(ManyueAiModel.FAST_REAL_CUGAN)
            val legacyGeneration = ManyueRuntimeState.generation
            ManyueRuntimeState.updateAiDetailStrength(if (ManyueRuntimeState.aiDetailStrength == 25) 26 else 25)
            assertEquals(legacyGeneration, ManyueRuntimeState.generation)
            ManyueRuntimeState.updateAiDetailStrength(101)
            assertEquals(100, ManyueRuntimeState.aiDetailStrength)
            assertEquals(legacyGeneration, ManyueRuntimeState.generation)

            ManyueRuntimeState.updateAiModel(ManyueAiModel.MOBILE_LITE)
            val liteGeneration = ManyueRuntimeState.generation
            ManyueRuntimeState.updateAiDetailStrength(60)
            assertEquals(liteGeneration + 1, ManyueRuntimeState.generation)
            assertEquals(60, ManyueRuntimeState.aiDetailStrength)

            val beforeClamp = ManyueRuntimeState.generation
            ManyueRuntimeState.updateAiDetailStrength(-1)
            assertEquals(0, ManyueRuntimeState.aiDetailStrength)
            assertEquals(beforeClamp + 1, ManyueRuntimeState.generation)
        } finally {
            ManyueRuntimeState.updateAiModel(originalModel)
            ManyueRuntimeState.updateAiDetailStrength(originalStrength)
        }
    }

    private fun cacheKey(modelId: String, strength: Int): String = ManyueEnhancementCache.cacheKey(
        mangaId = 1,
        chapterId = 2,
        pageIndex = 3,
        mode = ManyueEnhancementMode.AI_2X.value,
        targetMode = 2,
        targetWidth = 1200,
        classicStrength = 0,
        sourceFingerprint = "source-hash",
        targetScaleTenths = 20,
        modelId = modelId,
        anime4kOverlay = false,
        aiDetailStrength = strength,
    )
}
