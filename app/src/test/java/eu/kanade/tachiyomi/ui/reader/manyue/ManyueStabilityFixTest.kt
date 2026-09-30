package eu.kanade.tachiyomi.ui.reader.manyue

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueStabilityFixTest {
    @Test fun priorityHigherRunsFirst() {
        val low = ManyueAiUpscaler.Request("a", 0, 0, 0, java.io.File(""), 100, 100, 2001, 2344, priority = 10)
        val high = ManyueAiUpscaler.Request("b", 0, 0, 0, java.io.File(""), 100, 100, 2001, 2344, priority = 100)
        assertTrue(high.compareTo(low) < 0) // high is "less" => head of queue
    }

    @Test fun requestEqualsOnlyToken() {
        val a1 = ManyueAiUpscaler.Request("tok", 0, 0, 0, java.io.File(""), 100, 100, 2001, 2344, priority = 10)
        val a2 = ManyueAiUpscaler.Request("tok", 99, 99, 99, java.io.File("other"), 200, 200, 2002, 1360, priority = 100)
        assertEquals(a1, a2)
        assertEquals(a1.hashCode(), a2.hashCode())
    }

    @Test fun prefetchManagerResetClearsTokens() {
        ManyuePrefetchManager.reset()
        // register then reset
        ManyuePrefetchManager.register(0, "token1")
        ManyuePrefetchManager.reset()
        // after reset, onPageChanged should not crash
        ManyuePrefetchManager.onPageChanged(0)
    }

    @Test fun foldableControllerSamples() {
        ManyueFoldableController.clearSamples()
        ManyueFoldableController.recordSample(800, 1200)
        ManyueFoldableController.recordSample(0, 0) // invalid, should be ignored
        // no crash = pass
    }

    @Test fun foldableNonInnerNoOp() {
        // isFoldable() depends on lastConfig which is null by default => false
        assertFalse(ManyueFoldableController.isFoldable())
    }

    @Test fun identicalAiRequestsReuseOneToken() {
        val first = Files.createTempFile("manyue-reuse-a", ".bin").toFile().apply { writeBytes(byteArrayOf(1)) }
        val second = Files.createTempFile("manyue-reuse-b", ".bin").toFile().apply { writeBytes(byteArrayOf(1)) }
        val token1 = ManyueAiUpscaler.register(
            10, 20, 3, first, 690, 1240, 2001, 2344, 10,
            expectedMode = ManyueEnhancementMode.AI_2X.value,
            generation = 99,
            sourceFingerprint = "same-source",
        )
        val token2 = ManyueAiUpscaler.register(
            10, 20, 3, second, 690, 1240, 2001, 2344, 100,
            expectedMode = ManyueEnhancementMode.AI_2X.value,
            generation = 99,
            sourceFingerprint = "same-source",
        )
        assertEquals(token1, token2)
        assertFalse(second.exists())
        ManyueAiUpscaler.cancel(token1)
    }
}
