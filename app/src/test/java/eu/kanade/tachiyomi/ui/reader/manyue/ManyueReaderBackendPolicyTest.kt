package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManyueReaderBackendPolicyTest {
    @Test fun autoAndExplicitQualityStayOnGpuContinuousAcrossSelectableModels() {
        for (model in ManyueAiModel.selectableModels) {
            for (mode in listOf(2, 4)) {
                assertFalse("$model/$mode must not switch a continuous reader to Canvas", canvas(true, model, mode))
            }
        }
    }

    @Test fun offDoesNotRequireCanvasInContinuousReading() {
        assertFalse(canvas(true, ManyueAiModel.QUALITY_NATIVE2, 0))
    }

    @Test fun pagedAiRetainsItsExistingCanvasFallback() {
        for (mode in listOf(2, 4)) assertTrue(canvas(false, ManyueAiModel.QUALITY_NATIVE2, mode))
    }

    @Test fun pagedDisplayFilterRetainsItsApi33CompatibilityRoute() {
        assertFalse(canvas(false, ManyueAiModel.QUALITY_NATIVE2, 0, filter = true, sdk = 32))
        assertTrue(canvas(false, ManyueAiModel.QUALITY_NATIVE2, 0, filter = true, sdk = 33))
        assertFalse(canvas(false, ManyueAiModel.QUALITY_NATIVE2, 0, filter = false, sdk = 36))
        assertFalse(canvas(true, ManyueAiModel.QUALITY_NATIVE2, 0, filter = true, sdk = 36))
    }

    private fun canvas(
        continuous: Boolean,
        model: ManyueAiModel,
        mode: Int,
        filter: Boolean = false,
        sdk: Int = 36,
    ) = ManyueReaderBackendPolicy.requiresCanvas(continuous, model, mode, filter, sdk)
}
