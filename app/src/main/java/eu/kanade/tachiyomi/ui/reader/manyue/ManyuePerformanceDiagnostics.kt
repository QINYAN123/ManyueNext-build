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
    private const val MAX_OUTPUT_TILE = 4096
    private const val MAX_GPU_READBACK_BYTES = 192_000_000L

    internal val recorder = ManyuePerformanceRecorder()
    val enabled get() = recorder.enabled

    fun setEnabled(enabled: Boolean) {
        if (recorder.enabled == enabled) return
        if (!enabled) ManyueReadingCoverage.endReader()
        recorder.setEnabled(enabled)
        if (enabled) ManyueReadingCoverage.clear()
    }

    fun clear() {
        recorder.clear()
        ManyueReadingCoverage.clear()
    }

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
                "modelLoadMs" to Metric.MODEL_LOAD_MS,
                "resizeMs" to Metric.RESIZE_MS,
                "tileSize" to Metric.TILE_SIZE,
                "boundedWorkingBytes" to Metric.WORKING_BYTES,
                "outputWidth" to Metric.WIDTH,
                "outputHeight" to Metric.HEIGHT,
            ).forEach { (key, metric) ->
                if (native.has(key)) metrics[metric] = native.getDouble(key).toLong()
            }
            native.optLong("outputTile", -1L)
                .takeIf { it in 1L..MAX_OUTPUT_TILE.toLong() }
                ?.let { metrics[Metric.OUTPUT_TILE] = it }
            native.optLong("gpuReadbackBytes", -1L)
                .takeIf { it in 0L..MAX_GPU_READBACK_BYTES }
                ?.let { metrics[Metric.GPU_READBACK_BYTES] = it }
            metrics[Metric.BACKEND] = when (native.optString("backend")) {
                "vulkan" -> 1
                "cpu" -> 2
                "mixed" -> 3
                else -> 0
            }
            metrics[Metric.GPU_FALLBACK] = if (native.has("gpuFallbackReason")) 1 else 0
            recorder.event(Stage.NATIVE_DETAIL, page, token.hashCode(), metrics)
        }
    }

    /** Caller must use IO. A snapshot is copied before JSON/file work, outside the recorder lock. */
    @Synchronized
    fun export(context: Context): File {
        val snapshot = recorder.snapshot()
        val report = JSONObject().apply {
            put("schema", 2)
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
                        "Page indices are zero-based; nonzero job is a hash of an ephemeral task ID; job=0 means no request token exists yet.",
                        "Pre-request visibility waits pair by page, captured generation, and event time.",
                        "CACHE_HIT is distinct from new inference. Events can begin/end outside the capture window.",
                        "INFER_MS includes native tile preparation, dispatch and stitching, not only GPU execution.",
                        "OUTPUT_TILE and GPU_READBACK_BYTES are bounded numeric native telemetry; GPU device names are not exported.",
                        "SWAP_WAIT_NS includes scheduling and safety gates after base tiles are ready.",
                        "SWAP_CPU_NS measures the view commit; later GPU drawing is in frame samples.",
                        "Frame summaries exclude first draws and menus/dialogs. Deadline/GPU may be unavailable (-1).",
                        "Callback drops are missing observations, not measured janky frames. " +
                            "Samples are bounded recent history.",
                        "VISIBILITY_WAIT_FINISHED.WAIT_OUTCOME: 1 visible, 2 stale, 3 cancelled.",
                        "BLOCK_REASON_MASK bits: 1 thermal, 2 bad-frame, 4 fast-scroll; fast-scroll does not block priority >= 100.",
                        "WORK_BLOCKED status records the actual reader-gate and work-pacer decisions; reason mask is a diagnostic snapshot.",
                    ),
                ),
            )
            put("frameSummary", frameSummary(snapshot.frames.filter { it.reading && !it.firstDraw }))
            put("coverage", ManyueReadingCoverage.snapshot())
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
        check(bytes.size <= 3_000_000) { "诊断报告超过大小限制" }
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
