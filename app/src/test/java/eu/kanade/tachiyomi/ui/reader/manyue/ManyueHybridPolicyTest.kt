package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

class ManyueHybridPolicyTest {
    @Test fun `heat switches only next selection and thirty quiet seconds recovers original`() {
        var now = -100_000_000_000L
        val policy = ManyueHybridPolicy { now }
        assertEquals(ManyueAiModel.QUALITY_REAL_ESRGAN, policy.choose(1, 0, false))
        assertEquals(ManyueAiModel.QUALITY_NATIVE2, policy.choose(1, 1, false))
        now += 29_000_000_000L
        assertEquals(ManyueAiModel.QUALITY_NATIVE2, policy.choose(1, 0, false))
        // A new thermal sample extends the quiet period, not just the initial hold.
        policy.choose(1, 2, false)
        now += 29_999_999_999L
        assertEquals(ManyueAiModel.QUALITY_NATIVE2, policy.choose(1, 0, false))
        now++
        assertEquals(ManyueAiModel.QUALITY_REAL_ESRGAN, policy.choose(1, 0, false))
    }

    @Test fun `visible deadline backlog works without thermal support and new generation resets choice`() {
        val policy = ManyueHybridPolicy { 123L }
        assertEquals(ManyueAiModel.QUALITY_NATIVE2, policy.choose(1, -1, true))
        assertEquals(ManyueAiModel.QUALITY_REAL_ESRGAN, policy.choose(2, -1, false))
        assertEquals(ManyueAiModel.QUALITY_NATIVE2, policy.choose(3, 3, false))
    }

    @Test fun `physical cache identity pins once without replacing logical selection or generation`() {
        val request = ManyueAiUpscaler.Request(
            token = "hybrid", mangaId = 1, chapterId = 2, pageIndex = 3,
            inputFile = File("unused"), sourceWidth = 100, sourceHeight = 200,
            targetMode = 2, targetWidth = 200, model = ManyueAiModel.QUALITY_HYBRID,
            generation = 77,
        )
        request.bindPhysicalModel(ManyueAiModel.QUALITY_NATIVE2)
        assertEquals(ManyueAiModel.QUALITY_HYBRID, request.model)
        assertEquals(77L, request.generation)
        assertEquals(ManyueAiModel.QUALITY_NATIVE2, request.workModel)
        assertNotEquals(request.model.cacheIdentity, request.workModel.cacheIdentity)
        val originalKey = ManyueAiUpscaler.cacheKeyFor(request.copy(model = ManyueAiModel.QUALITY_REAL_ESRGAN))
        val nativeKey = ManyueAiUpscaler.cacheKeyFor(request.copy(model = ManyueAiModel.QUALITY_NATIVE2))
        assertNotEquals(originalKey, nativeKey)
        assertEquals(nativeKey, ManyueAiUpscaler.cacheKeyFor(request))
        assertEquals(
            ManyueEnhancementCache.profileFor(
                ManyueAiModel.QUALITY_HYBRID, request.expectedMode, request.configuredScalePercent,
                request.aiDetailStrength, request.benchmarkNamespace, request.targetMode, request.targetScaleTenths,
            ), request.cacheProfile,
        )
        assertThrows(IllegalStateException::class.java) {
            request.bindPhysicalModel(ManyueAiModel.QUALITY_REAL_ESRGAN)
        }
    }
}
