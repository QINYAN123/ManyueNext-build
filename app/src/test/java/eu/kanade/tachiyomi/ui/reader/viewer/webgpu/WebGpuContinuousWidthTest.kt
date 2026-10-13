package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebGpuContinuousWidthTest {
    @Test
    fun initialPreferenceEmissionDoesNotResetRestoredBookScale() {
        val width = WebGpuContinuousWidth(100)
        assertEquals(1f, width.initialScale, 0f)
        assertNull(width.changedScale(100))
    }

    @Test
    fun unrelatedRepeatedImageUpdatesDoNotRequestZoomReset() {
        val width = WebGpuContinuousWidth(100)
        repeat(16) { assertNull(width.changedScale(100)) }
    }

    @Test
    fun explicitWidthChangeAndReturnToOriginalRemainEffective() {
        val width = WebGpuContinuousWidth(100)
        assertEquals(.8f, width.changedScale(80)!!, 0f)
        assertNull(width.changedScale(80))
        assertEquals(1f, width.changedScale(100)!!, 0f)
        assertNull(width.changedScale(100))
    }

    @Test
    fun nonDefaultGlobalWidthIsInitializedBeforeBookRestoration() {
        val width = WebGpuContinuousWidth(80)
        assertEquals(.8f, width.initialScale, 0f)
        assertNull(width.changedScale(80))
    }
}
