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
        ManyueRuntimeState.updateAiScale(200)
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
        source, ManyueRuntimeState.generation, ManyueRuntimeState.aiModel.ordinal, 2, 200, 1380, 2842, token,
    )

    private fun expose(source: ManyueReadingCoverage.Source = this.source) =
        ManyueReadingCoverage.exposure(owner, source, 500, 400)

    @Test fun webGpuPlaceholderFrameCannotBecomeFirstFrameAiCoverage() {
        expose()
        ManyueReadingCoverage.frameSubmitted(owner, ManyueReadingCoverage.DrawBackend.WEBGPU_SUBMISSION)
        now = 100_000_000
        ManyueReadingCoverage.drawn(owner, source, stamp(), ManyueReadingCoverage.DrawBackend.WEBGPU_SUBMISSION)
        now = 300_000_000
        ManyueReadingCoverage.hidden(owner)
        val row = ManyueReadingCoverage.snapshot().getJSONArray("exposures").getJSONObject(0)
        assertTrue(row.getBoolean("displayedDuringExposure"))
        assertFalse(row.getBoolean("firstFrame"))
        assertEquals("WEBGPU_SUBMISSION", row.getString("firstDrawBackend"))
        assertEquals("WEBGPU_SUBMISSION", row.getString("aiDrawBackend"))
    }

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

    @Test fun lateSourceIdentityUpgradeKeepsExposureEntryAndRequiresAnActualDraw() {
        val unknown = source.copy(fingerprint = null)
        expose(unknown)
        now = 500_000_000L

        assertTrue(ManyueReadingCoverage.upgradeActiveSource(owner, unknown, stamp()))
        var row = ManyueReadingCoverage.snapshot().getJSONArray("exposures").getJSONObject(0)
        assertEquals(0L, row.getLong("enteredNs"))
        assertTrue(row.getInt("source") > 0)
        assertFalse(row.getBoolean("displayedDuringExposure"))

        now = 600_000_000L
        ManyueReadingCoverage.drawn(owner, source, stamp())
        row = ManyueReadingCoverage.snapshot().getJSONArray("exposures").getJSONObject(0)
        assertTrue(row.getBoolean("displayedDuringExposure"))
        assertEquals(600_000_000L, row.getLong("aiDrawNs"))
        assertEquals(0L, row.getLong("enteredNs"))
    }

    @Test fun lateSourceIdentityRejectsKnownMismatchWrongPageWrongDimensionsAndOldGeneration() {
        val unknown = source.copy(fingerprint = null)
        expose(unknown)
        val valid = stamp()
        val invalid = listOf(
            valid.copy(source = valid.source.copy(page = valid.source.page + 1)),
            valid.copy(source = valid.source.copy(sourceWidth = valid.source.sourceWidth + 1)),
            valid.copy(generation = valid.generation - 1),
        )
        invalid.forEach { assertFalse(ManyueReadingCoverage.upgradeActiveSource(owner, unknown, it)) }
        assertFalse(ManyueReadingCoverage.upgradeActiveSource(owner, source.copy(fingerprint = "known-mismatch"), valid))
        val priorKnownOwner = Any()
        ManyueReadingCoverage.exposure(priorKnownOwner, source.copy(fingerprint = "known-mismatch"), 500, 400)
        assertFalse(ManyueReadingCoverage.upgradeActiveSource(priorKnownOwner, unknown, valid))

        val row = ManyueReadingCoverage.snapshot().getJSONArray("exposures").getJSONObject(0)
        assertEquals(0, row.getInt("source"))
        assertFalse(row.getBoolean("displayedDuringExposure"))
    }

    @Test fun clearDropsOldExposureAndAllowsVisiblePageToRegisterAgain() {
        expose()
        val oldEpoch = ManyueReadingCoverage.epoch
        now = 400_000_000L

        ManyuePerformanceDiagnostics.clear()

        assertEquals(oldEpoch + 1, ManyueReadingCoverage.epoch)
        assertEquals(0, ManyueReadingCoverage.snapshot().getJSONArray("exposures").length())
        ManyueReadingCoverage.drawn(owner, source, stamp())
        expose()
        ManyueReadingCoverage.frameSubmitted(owner)
        now = 500_000_000L
        ManyueReadingCoverage.drawn(owner, source, stamp())
        ManyueReadingCoverage.hidden(owner)

        val row = ManyueReadingCoverage.snapshot().getJSONArray("exposures").getJSONObject(0)
        assertEquals(1, ManyueReadingCoverage.snapshot().getJSONArray("exposures").length())
        assertEquals(400_000_000L, row.getLong("enteredNs"))
        assertTrue(row.getLong("firstDrawNs") >= row.getLong("enteredNs"))
        assertTrue(row.getBoolean("displayedDuringExposure"))
    }

    @Test fun loadedResultDrawAfterResetRegistersStampWithoutInventingReadyOrPrefetch() {
        val loaded = stamp("loaded-result-after-reset")
        ManyueReadingCoverage.ready(loaded, prefetch = true)

        // A diagnostic reset clears READY provenance, but the already-loaded page is not rebound.
        ManyuePerformanceDiagnostics.clear()
        expose()
        now = 100_000_000L
        ManyueReadingCoverage.drawn(owner, source, loaded)
        now = 200_000_000L
        ManyueReadingCoverage.hidden(owner)

        val report = ManyueReadingCoverage.snapshot()
        val exposure = report.getJSONArray("exposures").getJSONObject(0)
        val result = report.getJSONArray("results").getJSONObject(0)
        assertTrue(exposure.getBoolean("displayedDuringExposure"))
        assertTrue(exposure.getBoolean("firstFrame"))
        assertTrue(exposure.isNull("aiReadyNs"))
        assertTrue(result.isNull("readyNs"))
        assertTrue(result.getBoolean("observedLoadedResult"))
        assertFalse(result.getBoolean("prefetch"))
        assertEquals(0, report.getInt("completedResults"))
        assertEquals(0, report.getInt("completedPrefetchResults"))
        assertEquals(0, report.getInt("usedPrefetchResults"))
        assertTrue(ManyuePerformanceDiagnostics.recorder.snapshot().events.isEmpty())
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
        assertEquals(0, ManyueReadingCoverage.snapshot().getJSONArray("results").length())
    }

    @Test fun incompleteLoadedResultMetadataCannotBeRegistered() {
        expose()
        val valid = stamp()
        listOf(
            valid.copy(source = source.copy(fingerprint = "")),
            valid.copy(source = source.copy(sourceWidth = 0)),
            valid.copy(source = source.copy(sourceHeight = 0)),
            valid.copy(source = source.copy(sourceWidth = source.sourceWidth + 1)),
            valid.copy(outputWidth = 0),
            valid.copy(outputHeight = 0),
            valid.copy(token = ""),
        ).forEach { ManyueReadingCoverage.drawn(owner, source, it) }
        now = 200_000_000L
        ManyueReadingCoverage.hidden(owner)

        val report = ManyueReadingCoverage.snapshot()
        val row = report.getJSONArray("exposures").getJSONObject(0)
        assertEquals(7, row.getInt("rejectedDrawCount"))
        assertFalse(row.getBoolean("displayedDuringExposure"))
        assertEquals(0, report.getJSONArray("results").length())
    }

    @Test fun loadedResultWithUnknownSourceDimensionsCannotBeRegistered() {
        val incompleteSource = source.copy(sourceWidth = 0, sourceHeight = 0)
        expose(incompleteSource)
        ManyueReadingCoverage.drawn(owner, incompleteSource, stamp().copy(source = incompleteSource))
        now = 200_000_000L
        ManyueReadingCoverage.hidden(owner)

        val report = ManyueReadingCoverage.snapshot()
        val row = report.getJSONArray("exposures").getJSONObject(0)
        assertEquals(1, row.getInt("rejectedDrawCount"))
        assertFalse(row.getBoolean("displayedDuringExposure"))
        assertEquals(0, report.getJSONArray("results").length())
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
        assertEquals(1380, rows.getJSONObject(1).getInt("outputWidth"))
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
