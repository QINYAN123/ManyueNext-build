package eu.kanade.tachiyomi.ui.reader.viewer

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueAiModel
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceDiagnostics
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueReadingCoverage
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueRuntimeState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderPageImageViewCoverageResetTest {
    private var now = 0L

    private val source = ManyueReadingCoverage.Source(
        mangaId = 531L,
        chapterId = 94L,
        page = 7,
        fingerprint = null,
        sourceWidth = 200,
        sourceHeight = 300,
    )

    @Before
    fun setUp() {
        ManyuePerformanceDiagnostics.setEnabled(false)
        ManyuePerformanceDiagnostics.clear()
        ManyueRuntimeState.updateMode(2)
        ManyueRuntimeState.updateAiModel(ManyueAiModel.QUALITY_REAL_ESRGAN)
        ManyueRuntimeState.updateAiScale(200)
        ManyueReadingCoverage.clockOverride = { now }
    }

    @After
    fun tearDown() {
        ManyuePerformanceDiagnostics.setEnabled(false)
        ManyuePerformanceDiagnostics.clear()
        ManyueReadingCoverage.clockOverride = null
        ManyueRuntimeState.updateMode(0)
        ManyueRuntimeState.updateAiModel(ManyueAiModel.DEFAULT)
        ManyueRuntimeState.updateAiScale(200)
    }

    @Test
    fun lateMatchingResultUpgradesVisibleExposureAndActualViewDrawRecordsIt() {
        val (activity, frame, bitmap) = createLoadedFrame()
        try {
            ManyuePerformanceDiagnostics.setEnabled(true)
            frame.updateReadingCoverageExposure(500, 600, isShortPage = false, eligible = true)
            draw(frame)

            val beforeResult = exposureRow()
            assertEquals(0, beforeResult.getInt("source"))
            assertFalse(beforeResult.getBoolean("displayedDuringExposure"))

            now = 200_000_000L
            val result = result()
            frame.setDisplayedCoverageResult(result, result.generation)

            val upgraded = exposureRow()
            assertEquals(beforeResult.getLong("enteredNs"), upgraded.getLong("enteredNs"))
            assertTrue(upgraded.getInt("source") > 0)
            assertFalse(upgraded.getBoolean("displayedDuringExposure"))

            now = 250_000_000L
            draw(frame)
            val afterDraw = exposureRow()
            assertTrue(afterDraw.getBoolean("displayedDuringExposure"))
            assertEquals(now, afterDraw.getLong("aiDrawNs"))
        } finally {
            frame.recycle()
            bitmap.recycle()
            activity.finish()
        }
    }

    @Test
    fun resetReRegistersAlreadyDisplayedImageAndInvalidatesOneFreshDraw() {
        val (activity, frame, bitmap) = createLoadedFrame()
        try {
            val result = result()
            // The image was committed before passive diagnostics were enabled.
            frame.setDisplayedCoverageResult(result, result.generation)
            ManyuePerformanceDiagnostics.setEnabled(true)
            frame.updateReadingCoverageExposure(500, 600, isShortPage = false, eligible = true)
            assertEquals(1, ManyueReadingCoverage.snapshot().getJSONArray("exposures").length())
            assertTrue(exposureRow().getInt("source") > 0)
            draw(frame)
            assertTrue(exposureRow().getBoolean("displayedDuringExposure"))

            now = 300_000_000L
            ManyuePerformanceDiagnostics.clear()
            // Same holder remains locally exposure-eligible. Its epoch check must re-register it.
            frame.updateReadingCoverageExposure(500, 600, isShortPage = false, eligible = true)
            val resetRow = exposureRow()
            assertEquals(1, ManyueReadingCoverage.snapshot().getJSONArray("exposures").length())
            assertEquals(now, resetRow.getLong("enteredNs"))
            assertTrue(resetRow.getInt("source") > 0)
            assertFalse(resetRow.getBoolean("displayedDuringExposure"))

            now += 50_000_000L
            draw(frame)
            assertTrue(exposureRow().getBoolean("displayedDuringExposure"))
        } finally {
            frame.recycle()
            bitmap.recycle()
            activity.finish()
        }
    }

    @Test
    fun diagnosticsOffSourceAdoptionRejectsWrongPageAndInputDimensions() {
        ManyuePerformanceDiagnostics.setEnabled(false)
        val resultSource = source.copy(fingerprint = "late-source-fingerprint")

        assertTrue(canAdoptManyueCoverageResultSource(source, resultSource))
        assertFalse(canAdoptManyueCoverageResultSource(source.copy(page = source.page + 1), resultSource))
        assertFalse(canAdoptManyueCoverageResultSource(source.copy(sourceWidth = source.sourceWidth + 1), resultSource))
        assertFalse(canAdoptManyueCoverageResultSource(source.copy(sourceHeight = source.sourceHeight + 1), resultSource))
    }

    private fun createLoadedFrame(): Triple<Activity, ReaderPageImageView, Bitmap> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val bitmap = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val frame = ReaderPageImageView(activity).apply {
            setReadingCoverageSource(source)
            setImage(
                BitmapDrawable(activity.resources, bitmap),
                ReaderPageImageView.Config(
                    zoomDuration = 1,
                    minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
                ),
            )
        }
        activity.setContentView(frame, ViewGroup.LayoutParams(400, 600))
        draw(frame)
        check(activeImage(frame).isReady)
        return Triple(activity, frame, bitmap)
    }

    private fun result(): ManyueReadingCoverage.Result {
        val runtime = ManyueRuntimeState
        return ManyueReadingCoverage.Result(
            source = source.copy(fingerprint = "late-source-fingerprint"),
            generation = runtime.generation,
            model = runtime.aiModel.ordinal,
            mode = runtime.modeInt,
            scalePercent = runtime.aiScalePercent,
            outputWidth = 400,
            outputHeight = 600,
            token = "late-source-result",
        )
    }

    private fun activeImage(frame: ReaderPageImageView): SubsamplingScaleImageView =
        (0 until frame.childCount)
            .asSequence()
            .mapNotNull { frame.getChildAt(it) as? SubsamplingScaleImageView }
            .first { it.isReady }

    private fun draw(frame: ReaderPageImageView) {
        frame.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        frame.layout(0, 0, 400, 600)
        val output = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        try {
            frame.draw(Canvas(output))
        } finally {
            output.recycle()
        }
    }

    private fun exposureRow() = ManyueReadingCoverage.snapshot()
        .getJSONArray("exposures")
        .getJSONObject(0)
}
