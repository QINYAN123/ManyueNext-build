package eu.kanade.tachiyomi.ui.reader.manyue

import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Names are kept by R8 because native JNI entry points use them directly. */
internal object ManyueLiteNative {
    external fun nativeCreate(modelDirectory: String, preferGpu: Boolean): Long
    external fun nativeUpscale(
        handle: Long,
        jobId: Long,
        inputPath: String,
        outputPath: String,
        targetWidth: Int,
        strengthPercent: Int,
    ): String
    external fun nativeCancel(handle: Long, jobId: Long)
    external fun nativeDestroy(handle: Long)
}

internal interface ManyueLiteBackend {
    fun create(modelDirectory: String): Long
    fun upscale(
        handle: Long,
        jobId: Long,
        inputPath: String,
        outputPath: String,
        targetWidth: Int,
        strength: Int,
    ): String
    fun cancel(handle: Long, jobId: Long)
    fun destroy(handle: Long)
}

/** One serial inference owner. Cancellation never waits on the inference monitor. */
internal class ManyueLiteSession(private val backend: ManyueLiteBackend) {
    private val inferenceLock = Any()
    private val nextJobId = AtomicLong()
    private var handle = 0L
    private var modelDirectory: String? = null

    fun upscale(
        modelDir: File,
        input: File,
        output: File,
        targetWidth: Int,
        strength: Int,
        isCancelled: () -> Boolean,
        onCancellationReady: (() -> Unit) -> Unit,
    ): String = synchronized(inferenceLock) {
        if (isCancelled()) throw ManyueAiRuntime.CancelledException()
        val directory = modelDir.canonicalPath
        if (handle != 0L && modelDirectory != directory) closeLocked()
        if (handle == 0L) {
            handle = backend.create(directory)
            check(handle != 0L) { "轻量 AI 初始化失败" }
            modelDirectory = directory
        }
        val currentHandle = handle
        val jobId = nextJobId.incrementAndGet()
        val cancel = { backend.cancel(currentHandle, jobId) }
        onCancellationReady(cancel)
        // nativeCancel remembers this job even if it races nativeUpscale's entry.
        if (isCancelled()) {
            cancel()
            throw ManyueAiRuntime.CancelledException()
        }
        val result = try {
            backend.upscale(
                currentHandle,
                jobId,
                input.absolutePath,
                output.absolutePath,
                targetWidth,
                strength.coerceIn(0, 100),
            )
        } catch (failure: Exception) {
            if (isCancelled()) throw ManyueAiRuntime.CancelledException()
            throw failure
        }
        if (isCancelled()) throw ManyueAiRuntime.CancelledException()
        result
    }

    /** Called on the worker boundary, never from a UI settings callback. */
    fun close() = synchronized(inferenceLock) { closeLocked() }

    private fun closeLocked() {
        if (handle != 0L) backend.destroy(handle)
        handle = 0L
        modelDirectory = null
    }
}

internal object ManyueLiteRuntime {
    private val session by lazy {
        System.loadLibrary("manyue_lite")
        ManyueLiteSession(object : ManyueLiteBackend {
            override fun create(modelDirectory: String) = ManyueLiteNative.nativeCreate(modelDirectory, true)
            override fun upscale(
                handle: Long,
                jobId: Long,
                inputPath: String,
                outputPath: String,
                targetWidth: Int,
                strength: Int,
            ) =
                ManyueLiteNative.nativeUpscale(handle, jobId, inputPath, outputPath, targetWidth, strength)
            override fun cancel(handle: Long, jobId: Long) = ManyueLiteNative.nativeCancel(handle, jobId)
            override fun destroy(handle: Long) = ManyueLiteNative.nativeDestroy(handle)
        })
    }

    @Volatile private var initialized = false
    private var lastUsedAt = 0L

    fun upscale(
        modelDir: File,
        input: File,
        output: File,
        targetWidth: Int,
        strength: Int,
        isCancelled: () -> Boolean,
        onCancellationReady: (() -> Unit) -> Unit,
    ): String {
        val currentSession = session
        initialized = true
        val telemetry = try {
            currentSession.upscale(modelDir, input, output, targetWidth, strength, isCancelled, onCancellationReady)
        } finally {
            lastUsedAt = SystemClock.elapsedRealtime()
        }
        check(output.isFile && output.length() > 0L) { "轻量 AI 未生成图片" }
        val backend = JSONObject(telemetry).getString("backend")
        check(backend == "vulkan" || backend == "cpu") { "轻量 AI 后端状态无效" }
        return telemetry
    }

    fun releaseOnWorker() {
        if (initialized) session.close()
    }

    fun releaseWhenIdleOnWorker(retainForReading: Boolean) {
        if (!retainForReading || SystemClock.elapsedRealtime() - lastUsedAt >= 30_000L) releaseOnWorker()
    }

    fun displayDetail(telemetry: String): String {
        val data = JSONObject(telemetry)
        return if (data.getString("backend") == "vulkan") "轻量 AI · GPU" else "轻量 AI · CPU（GPU 不可用）"
    }
}
