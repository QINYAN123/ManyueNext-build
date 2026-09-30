package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueAiSafetyPolicyTest {

    @Test fun `pixel budget rejects zero negative and overflowing dimensions`() {
        assertFalse(ManyueAiSafetyPolicy.isPixelBudgetSafe(0, 100, 1_000_000))
        assertFalse(ManyueAiSafetyPolicy.isPixelBudgetSafe(100, -1, 1_000_000))
        assertFalse(ManyueAiSafetyPolicy.isPixelBudgetSafe(50_000, 50_000, 48_000_000))
        assertTrue(ManyueAiSafetyPolicy.isPixelBudgetSafe(2000, 3000, 8_000_000))
    }

    @Test fun `predicted native 2x output is checked before launch`() {
        assertTrue(ManyueAiSafetyPolicy.isPredictedNativeOutputSafe(2000, 3000, 48_000_000))
        assertFalse(ManyueAiSafetyPolicy.isPredictedNativeOutputSafe(2000, 7000, 48_000_000))
    }

    @Test fun `completion requires token identity AI mode and generation to match`() {
        assertTrue(
            ManyueAiSafetyPolicy.isCompletionCurrent(
                expectedToken = "token",
                currentToken = "token",
                expectedIdentity = "1:2:3",
                currentIdentity = "1:2:3",
                expectedMode = ManyueEnhancementMode.AI_2X.value,
                currentMode = ManyueEnhancementMode.AI_2X.value,
                expectedGeneration = 7,
                currentGeneration = 7,
            ),
        )
    }

    @Test fun `completion is rejected after OFF mode generation identity or token change`() {
        fun valid(
            currentToken: String = "token",
            currentIdentity: String = "1:2:3",
            currentMode: Int = ManyueEnhancementMode.AI_2X.value,
            currentGeneration: Long = 7,
        ) = ManyueAiSafetyPolicy.isCompletionCurrent(
            expectedToken = "token",
            currentToken = currentToken,
            expectedIdentity = "1:2:3",
            currentIdentity = currentIdentity,
            expectedMode = ManyueEnhancementMode.AI_2X.value,
            currentMode = currentMode,
            expectedGeneration = 7,
            currentGeneration = currentGeneration,
        )

        assertFalse(valid(currentToken = "new-token"))
        assertFalse(valid(currentIdentity = "1:2:4"))
        assertFalse(valid(currentMode = ManyueEnhancementMode.OFF.value))
        assertFalse(valid(currentGeneration = 8))
    }
}
