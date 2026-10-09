package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class ManyueReadingCoverageTest {
    private var now = 0L
    private val owner = Any()
    private val source = ManyueReadingCoverage.Source(9182, 3721, 3, "private-source-hash", 690, 1421)

    @Before fun setup() {
        ManyueRuntimeState.updateMode(2)
        ManyueRuntimeState.updateAiModel(ManyueAiModel.QUALITY_REAL_ESRGAN)
        ManyueRuntimeState.updateAiScale(137)
        ManyuePerformanceDiagnostics.setEnabled(true)
        ManyuePerformanceDiagnostics.clear()
        ManyueReadingCoverage.clockOverride = { now }
    }

    @After fun cleanup() {
        ManyuePerformanceDiagnostics.setEnabled(false)
        ManyuePerformanceDiagnostics.clear()
        ManyueReadingCoverage.clockOverride = null
        ManyueRuntimeState.updateMode(0)
        ManyueRuntimeState.updateAiModel(ManyueAiModel.DEFAULT)
        ManyueRuntimeState.updateAiScale(200)
    }

    private fun stamp(token: String = "private-task") = ManyueReadingCoverage.Result(
        source, ManyueRuntimeState.generation, ManyueRuntimeState.aiModel.ordinal, 2, 137, 945, 1946, token,
    )

    private fun expose(source: ManyueReadingCoverage.Source = this.source) =
        ManyueReadingCoverage.exposure(owner, source, 500, 400)

    @Test fun loadingEntryIsRetainedAndReadyAloneDoesNotCountAsDisplayed() {
        expose(source.copy(fingerprint = null, sourceWidth = 0, sourceHeight = 0))
        now = 1_000_000_000
        expose()
        ManyueReadingCoverage.ready(stamp(), true)
        now = 3_000_000_000
        ManyueReadingCoverage.hidden(owner)
        val row = ManyueReadingCoverage.snapshot().getJSONArray("exposures").getJSONObject(0)
        assertEquals(0L, row.getLong("enteredNs"))
        assertTrue(row.getBoolean("eligible"))
        assertFalse(row.getBoolean("displayedDuringExposure"))
        assertEquals("no_correct_ai_draw_before_exit", row.getString("reason"))
    }

    @Test fun placeholderOrStaleImageFramePreventsLateAiFromBeingCalledFirstFrame() {
        expose()
        now = 10_000_000
        ManyueReadingCoverage.frameSubmitted(owner)
        now = 3_000_000_000
        ManyueReadingCoverage.ready(stamp(), false)
        ManyueReadingCoverage.drawn(owner, source, stamp())
        now = 4_000_000_000
        ManyueReadingCoverage.hidden(owner)
        val row = ManyueReadingCoverage.snapshot().getJSONArray("exposures").getJSONObject(0)
        assertFalse(row.getBoolean("firstFrame"))
        assertTrue(row.getBoolean("late"))
        assertEquals(10_000_000L, row.getLong("firstDrawNs"))
    }

    @Test fun staleGenerationWrongSourcePageAndDimensionsNeverBecomeSuccess() {
        expose()
        val result = stamp()
        ManyueReadingCoverage.drawn(owner, source, null)
        listOf(
            result.copy(generation = result.generation - 1),
            result.copy(source = source.copy(page = 4)),
            result.copy(source = source.copy(fingerprint = "different")),
            result.copy(source = source.copy(sourceWidth = 691)),
            result.copy(scalePercent = 150),
            result.copy(outputWidth = 0),
        ).forEach { ManyueReadingCoverage.drawn(owner, source, it) }
        now = 200_000_000
        ManyueReadingCoverage.hidden(owner)
        val row = ManyueReadingCoverage.snapshot().getJSONArray("exposures").getJSONObject(0)
        assertEquals(6, row.getInt("rejectedDrawCount"))
        assertFalse(row.getBoolean("timely"))
    }

    @Test fun lateFirstVisitAndInstantCachedRevisitRemainSeparate() {
        expose()
        ManyueReadingCoverage.drawn(owner, source, null)
        now = 1_000_000_000
        ManyueReadingCoverage.ready(stamp(), false)
        now = 3_000_000_000
        ManyueReadingCoverage.drawn(owner, source, stamp())
        now = 4_000_000_000
        ManyueReadingCoverage.hidden(owner)
        now = 10_000_000_000
        expose()
        ManyueReadingCoverage.drawn(owner, source, stamp())
        now = 10_200_000_000
        ManyueReadingCoverage.hidden(owner)
        val rows = ManyueReadingCoverage.snapshot().getJSONArray("exposures")
        assertEquals(2, rows.length())
        assertTrue(rows.getJSONObject(0).getBoolean("late"))
        assertFalse(rows.getJSONObject(0).getBoolean("firstFrame"))
        assertTrue(rows.getJSONObject(1).getBoolean("firstFrame"))
        assertTrue(rows.getJSONObject(1).getBoolean("timely"))
        assertEquals(945, rows.getJSONObject(1).getInt("outputWidth"))
    }

    @Test fun activeImageErrorRemainsInDenominatorAndInvalidatesItsSuccess() {
        expose()
        ManyueReadingCoverage.drawn(owner, source, stamp())
        ManyueReadingCoverage.drawFailure(owner)
        now = 500_000_000
        ManyueReadingCoverage.hidden(owner)
        val row = ManyueReadingCoverage.snapshot().getJSONArray("exposures").getJSONObject(0)
        assertTrue(row.getBoolean("eligible"))
        assertFalse(row.getBoolean("displayedDuringExposure"))
        assertEquals("active_image_draw_error", row.getString("reason"))
    }

    @Test fun modelChangeClosesExposureAndRejectsOldResultOnSamePage() {
        val old = stamp()
        expose()
        now = 500_000_000
        ManyueRuntimeState.updateAiModel(ManyueAiModel.MOBILE_LITE)
        expose()
        ManyueReadingCoverage.drawn(owner, source, old)
        now = 800_000_000
        ManyueReadingCoverage.hidden(owner)
        val rows = ManyueReadingCoverage.snapshot().getJSONArray("exposures")
        assertEquals(2, rows.length())
        assertFalse(rows.getJSONObject(1).getBoolean("displayedDuringExposure"))
        assertEquals(1, rows.getJSONObject(1).getInt("rejectedDrawCount"))
    }

    @Test fun prefetchUseRequiresQualifyingDwellAndPrivacyIsPreserved() {
        ManyueReadingCoverage.ready(stamp(), true)
        ManyueReadingCoverage.ready(stamp("unread-prefetch"), true)
        expose()
        ManyueReadingCoverage.drawn(owner, source, stamp())
        now = 80_000_000
        ManyueReadingCoverage.hidden(owner)
        assertEquals(0, ManyueReadingCoverage.snapshot().getInt("usedPrefetchResults"))
        now = 100_000_000
        expose()
        ManyueReadingCoverage.drawn(owner, source, stamp())
        now = 300_000_000
        ManyueReadingCoverage.hidden(owner)
        val report = ManyueReadingCoverage.snapshot()
        assertEquals(2, report.getInt("completedPrefetchResults"))
        assertEquals(1, report.getInt("usedPrefetchResults"))
        assertFalse(report.toString().contains("private-source-hash"))
        assertFalse(report.toString().contains("private-task"))
        assertFalse(report.toString().contains("9182"))
        assertFalse(report.toString().contains("3721"))
    }

    @Test fun tinyRegionsAreExcludedButShortPagesHaveDefinedVisibilityRule() {
        ManyueReadingCoverage.exposure(owner, source, 199, 499, true)
        assertEquals(0, ManyueReadingCoverage.snapshot().getJSONArray("exposures").length())
        ManyueReadingCoverage.exposure(owner, source, 150, 500, true)
        now = 100_000_000
        ManyueReadingCoverage.hidden(owner)
        assertTrue(ManyueReadingCoverage.snapshot().getJSONArray("exposures").getJSONObject(0).getBoolean("eligible"))
    }
}
