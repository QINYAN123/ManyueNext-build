package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueWebGpuResultPolicyTest {
    private val identity = ManyuePageBridge.Identity(11, 12, 3)
    private val result = ManyueReadingCoverage.Result(
        ManyueReadingCoverage.Source(11, 12, 3, "original-hash", 691, 1421),
        50, 2, 2, 200, 1382, 2842, "prepared-token",
    )
    private fun accepts(candidate: ManyueReadingCoverage.Result, fingerprint: String? = "original-hash") =
        ManyueWebGpuResultPolicy.matches(identity, 50, 2, 2, 200, fingerprint, candidate)

    @Test fun `native2x retains odd input dimensions`() { assertTrue(accepts(result)) }
    @Test fun `late old session cannot claim a current frame`() { assertFalse(accepts(result.copy(generation = 49))) }
    @Test fun `other chapter cannot borrow same numbered page`() {
        assertFalse(accepts(result.copy(source = result.source.copy(chapterId = 13))))
    }
    @Test fun `recycled page slot rejects another image`() {
        assertFalse(accepts(result.copy(source = result.source.copy(page = 4))))
    }
    @Test fun `same dimensions with different source are rejected`() {
        assertFalse(accepts(result.copy(source = result.source.copy(fingerprint = "different-hash"))))
    }
    @Test fun `unknown source is never successfulAI`() { assertFalse(accepts(result, null)) }
    @Test fun `model or mode switch isolates old results`() {
        assertFalse(accepts(result.copy(model = 0)))
        assertFalse(accepts(result.copy(mode = 0)))
    }
    @Test fun `wrong倍率 or decoded dimensions are rejected`() {
        assertFalse(accepts(result.copy(scalePercent = 125)))
        assertFalse(accepts(result.copy(outputWidth = 691)))
        assertFalse(accepts(result.copy(outputHeight = 2841)))
    }
}
