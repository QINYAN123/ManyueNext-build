package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.os.Build
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceRecorder.Metric
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceRecorder.Stage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class ManyuePerformanceExportTest {
    @Test fun exportWhitelistsNativeTelemetryAndDoesNotIncludeSensitiveStrings() {
        try {
            ManyuePerformanceDiagnostics.setEnabled(true)
            ManyuePerformanceDiagnostics.nativeDetail(
                0,
                "ephemeral-task",
                """{
                "backend":"vulkan","decodeMs":3.4,"inferMs":7.2,"encodeMs":5,
                "outputTile":384,"gpuReadbackBytes":2000000,"gpuName":"Adreno 740",
                "gpuFallbackReason":"https://private.example/token?secret=abc",
                "file":"/private/account/image.jpg","unexpected":"private title"
            }""",
            )
            ManyuePerformanceDiagnostics.nativeDetail(
                1,
                "out-of-range-task",
                """{"backend":"cpu","outputTile":99999999,"gpuReadbackBytes":9999999999}""",
            )
            ManyuePerformanceDiagnostics.event(
                Stage.BASE_READY,
                0,
                null,
                Metric.BASE_READY_MS to 225L,
            )
            val file = ManyuePerformanceDiagnostics.export(RuntimeEnvironment.getApplication())
            val text = file.readText()
            assertFalse(text.contains("private.example"))
            assertFalse(text.contains("private title"))
            assertFalse(text.contains("/private/account"))
            assertFalse(text.contains("Adreno 740"))
            assertFalse(text.contains("ephemeral-task"))
            val report = JSONObject(text)
            val metrics = report.getJSONArray("events").getJSONObject(1).getJSONObject("metrics")
            assertEquals(1, metrics.getInt("BACKEND"))
            assertEquals(7, metrics.getInt("INFER_MS"))
            assertEquals(384, metrics.getInt("OUTPUT_TILE"))
            assertEquals(2_000_000, metrics.getInt("GPU_READBACK_BYTES"))
            val outOfRange = report.getJSONArray("events").getJSONObject(2).getJSONObject("metrics")
            assertFalse(outOfRange.has("OUTPUT_TILE"))
            assertFalse(outOfRange.has("GPU_READBACK_BYTES"))
            val baseReady = report.getJSONArray("events").getJSONObject(3).getJSONObject("metrics")
            assertEquals(225, baseReady.getInt("BASE_READY_MS"))
            assertEquals(Build.VERSION.SDK_INT, report.getInt("androidApi"))
            assertEquals(0, report.getJSONObject("frameSummary").getInt("samples"))
            assertTrue(file.length() < 1_000_000)
            ManyuePerformanceDiagnostics.event(Stage.AI_READY, 1, null, Metric.NATIVE_MS to 100)
            assertEquals(file, ManyuePerformanceDiagnostics.export(RuntimeEnvironment.getApplication()))
        } finally {
            ManyuePerformanceDiagnostics.setEnabled(false)
            ManyuePerformanceDiagnostics.clear()
        }
    }

    @Test fun frameSummaryExcludesMenusFirstDrawAndUnavailableDeadlines() {
        try {
            ManyuePerformanceDiagnostics.setEnabled(true)
            val recorder = ManyuePerformanceDiagnostics.recorder
            val session = recorder.snapshot().session
            recorder.frame(session, 99_000_000, 16_000_000, -1, true, true, 0)
            recorder.frame(session, 99_000_000, 16_000_000, -1, false, false, 0)
            recorder.frame(session, 6_000_000, 16_000_000, -1, false, true, 2)
            recorder.frame(session, 20_000_000, 16_000_000, -1, false, true, 0)
            recorder.frame(session, 8_000_000, -1, -1, false, true, 0)
            val report = JSONObject(ManyuePerformanceDiagnostics.export(RuntimeEnvironment.getApplication()).readText())
            val summary = report.getJSONObject("frameSummary")
            assertEquals(3, summary.getInt("samples"))
            assertEquals(2, summary.getInt("withDeadline"))
            assertEquals(1, summary.getInt("missedDeadline"))
            assertEquals(8.0, summary.getDouble("p50Ms"), 0.0)
            assertEquals(2, report.getInt("frameCallbackDrops"))
        } finally {
            ManyuePerformanceDiagnostics.setEnabled(false)
            ManyuePerformanceDiagnostics.clear()
        }
    }
}
