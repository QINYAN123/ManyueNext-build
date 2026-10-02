package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.AtomicFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceRecorder.Metric
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceRecorder.Stage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil

/** No network/logcat output. Export is explicit and overwrites a single bounded report. */
object ManyuePerformanceDiagnostics {
    internal val recorder = ManyuePerformanceRecorder()
    val enabled get() = recorder.enabled

    fun setEnabled(enabled: Boolean) = recorder.setEnabled(enabled)
    fun clear() = recorder.clear()

    internal fun event(stage: Stage, page: Int = -1, token: String? = null, vararg metrics: Pair<Metric, Long>) {
        if (!enabled) return
        recorder.event(stage, page, token?.hashCode() ?: 0, metrics.toMap())
    }

    internal fun nativeDetail(page: Int, token: String, telemetry: String) {
        if (!enabled) return
        runCatching {
            val native = JSONObject(telemetry)
            val metrics = linkedMapOf<Metric, Long>()
            mapOf(
                "decodeMs" to Metric.DECODE_MS,
                "inferMs" to Metric.INFER_MS,
                "encodeMs" to Metric.ENCODE_MS,
                "tiles" to Metric.TILES,
                "modelLoads" to Metric.MODEL_LOADS,
                "boundedWorkingBytes" to Metric.WORKING_BYTES,
                "outputWidth" to Metric.WIDTH,
                "outputHeight" to Metric.HEIGHT,
            ).forEach { (key, metric) ->
                if (native.has(key)) metrics[metric] = native.getDouble(key).toLong()
            }
            metrics[Metric.BACKEND] = if (native.optString("backend") == "vulkan") 1 else 2
            metrics[Metric.GPU_FALLBACK] = if (native.has("gpuFallbackReason")) 1 else 0
            recorder.event(Stage.NATIVE_DETAIL, page, token.hashCode(), metrics)
        }
    }

    /** Caller must use IO. A snapshot is copied before JSON/file work, outside the recorder lock. */
    @Synchronized
    fun export(context: Context): File {
        val snapshot = recorder.snapshot()
        val report = JSONObject().apply {
            put("schema", 1)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("commit", BuildConfig.COMMIT_SHA)
            put("deviceModel", Build.MODEL)
            put("androidApi", Build.VERSION.SDK_INT)
            put("session", snapshot.session)
            put("recording", snapshot.enabled)
            put("durationMs", snapshot.durationNs / 1_000_000)
            put("overwrittenEvents", snapshot.overwrittenEvents)
            put("overwrittenFrames", snapshot.overwrittenFrames)
            put("frameCallbackDrops", snapshot.callbackDrops)
            put(
                "notes",
                JSONArray(
                    listOf(
                        "Page indices are zero-based; job is a hash of an ephemeral task ID.",
                        "CACHE_HIT is distinct from new inference. Events can begin/end outside the capture window.",
                        "INFER_MS includes native tile preparation, dispatch and stitching, not only GPU execution.",
                        "SWAP_WAIT_NS includes scheduling and safety gates after base tiles are ready.",
                        "SWAP_CPU_NS measures the view commit; later GPU drawing is in frame samples.",
                        "Frame summaries exclude first draws and menus/dialogs. Deadline/GPU may be unavailable (-1).",
                        "Callback drops are missing observations, not measured janky frames. " +
                            "Samples are bounded recent history.",
                    ),
                ),
            )
            put("frameSummary", frameSummary(snapshot.frames.filter { it.reading && !it.firstDraw }))
            put(
                "events",
                JSONArray(
                    snapshot.events.map { event ->
                        JSONObject().apply {
                            put("tNs", event.timeNs)
                            put("stage", event.stage.name)
                            put("page", event.page)
                            put("job", event.job)
                            put(
                                "metrics",
                                JSONObject().apply { event.metrics.forEach { (key, value) -> put(key.name, value) } },
                            )
                        }
                    },
                ),
            )
            put(
                "frames",
                JSONArray(
                    snapshot.frames.map { frame ->
                        JSONArray(
                            listOf(
                                frame.timeNs,
                                frame.totalNs,
                                frame.deadlineNs,
                                frame.gpuNs,
                                frame.firstDraw,
                                frame.reading,
                            ),
                        )
                    },
                ),
            )
            put("frameColumns", JSONArray(listOf("tNs", "totalNs", "deadlineNs", "gpuNs", "firstDraw", "reading")))
        }
        val bytes = report.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 1_000_000) { "诊断报告超过大小限制" }
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.cacheDir
        directory.mkdirs()
        val file = File(directory, "manyue-performance.json")
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
        return file
    }

    private fun frameSummary(frames: List<ManyuePerformanceRecorder.Frame>): JSONObject {
        val durations = frames.map { it.totalNs }.sorted()
        val deadlines = frames.filter { it.deadlineNs > 0 }
        fun percentile(p: Double): Double? = if (durations.isEmpty()) {
            null
        } else {
            durations[(ceil(durations.size * p).toInt() - 1).coerceIn(durations.indices)] / 1_000_000.0
        }
        return JSONObject().apply {
            put("samples", durations.size)
            put("withDeadline", deadlines.size)
            put("missedDeadline", deadlines.count { it.totalNs >= it.deadlineNs })
            put("p50Ms", percentile(.50) ?: JSONObject.NULL)
            put("p95Ms", percentile(.95) ?: JSONObject.NULL)
            put("p99Ms", percentile(.99) ?: JSONObject.NULL)
        }
    }
}
