package eu.kanade.tachiyomi.ui.reader.manyue

import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Single-worker owner. The existing reader scheduler still owns priority and request cancellation. */
internal class ManyueLegacySession {
    private var connection: Connection? = null
    private var nextRequestId = 1L
    private var lastUsedAt = 0L

    private class Connection(val key: String, val process: Process) {
        val responses = Executors.newSingleThreadExecutor { task ->
            Thread(task, "manyue-legacy-response").apply { isDaemon = true }
        }
        private val tail = StringBuilder()
        val logThread = Thread({
            runCatching {
                process.errorStream.reader(Charsets.UTF_8).use { reader ->
                    val chars = CharArray(1024)
                    while (true) {
                        val count = reader.read(chars)
                        if (count < 0) break
                        synchronized(tail) {
                            tail.append(chars, 0, count)
                            if (tail.length > 4096) tail.delete(0, tail.length - 4096)
                        }
                    }
                }
            }
        }, "manyue-legacy-log").apply {
            isDaemon = true
            start()
        }

        fun logTail(): String = synchronized(tail) { tail.toString() }

        fun close() {
            try {
                ManyueAiRuntime.stop(process)
            } finally {
                responses.shutdownNow()
                runCatching { process.outputStream.close() }
                runCatching { process.inputStream.close() }
            }
        }
    }

    @Synchronized
    fun upscale(
        key: String,
        startProcess: () -> Process,
        input: File,
        output: File,
        targetWidth: Int,
        logFile: File,
        isCancelled: () -> Boolean,
        onProcessStarted: (Process) -> Unit,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + 240_000L
        var current = connection
        var startup: ManyueLegacyProtocol.Response? = null
        var ownsOutput = false
        try {
            if (isCancelled()) throw ManyueAiRuntime.CancelledException()
            val inputPath = input.canonicalFile
            val outputPath = output.canonicalFile
            val logPath = logFile.canonicalFile
            require(inputPath != outputPath && inputPath != logPath && outputPath != logPath) {
                "Native work files must preserve the original file"
            }
            ownsOutput = true
            check(!output.exists() || output.delete()) { "Unable to remove a stale native output" }
            if (current == null || current.key != key || !current.process.isAlive) {
                close()
                current = Connection(key, startProcess()).also { connection = it }
                onProcessStarted(current.process)
                startup = read(current, isCancelled, deadline)
                check(
                    startup.kind == ManyueLegacyProtocol.READY && startup.status == 0 &&
                        startup.requestId == 0L && startup.modelLoads == 1,
                ) {
                    "Native session failed to initialize"
                }
            } else {
                onProcessStarted(current.process)
            }
            val id = nextRequestId++
            check(id > 0) { "Native request sequence exhausted" }
            ManyueLegacyProtocol.writeRequest(current.process.outputStream, id, targetWidth, input.path, output.path)
            val response = read(current, isCancelled, deadline)
            check(
                response.kind == ManyueLegacyProtocol.RESULT && response.requestId == id &&
                    response.status == 0 && response.modelLoads == 0,
            ) {
                "Native session request failed (${response.status})"
            }
            if (isCancelled()) throw ManyueAiRuntime.CancelledException()
            check(
                response.width == targetWidth && response.height > 0 &&
                    output.isFile && output.length() > 0 && output.length() == response.outputBytes,
            ) { "Native session output is missing or invalid" }
            return JSONObject().apply {
                val backend = when (response.backend) {
                    1 -> "vulkan"
                    2 -> "cpu"
                    else -> "mixed"
                }
                put("backend", backend)
                put("modelLoadMs", (startup?.initUs ?: 0L) / 1000.0)
                put("decodeMs", response.decodeUs / 1000.0)
                put("inferMs", response.inferUs / 1000.0)
                put("resizeMs", response.resizeUs / 1000.0)
                put("encodeMs", response.encodeUs / 1000.0)
                put("totalMs", response.totalUs / 1000.0)
                put("modelLoads", startup?.modelLoads ?: 0)
                put("outputWidth", response.width)
                put("outputHeight", response.height)
                put("tileSize", response.tile)
            }.toString()
        } catch (error: Throwable) {
            if (ownsOutput) runCatching { logFile.writeText(current?.logTail().orEmpty()) }
            try {
                close()
            } finally {
                if (ownsOutput) output.delete()
            }
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                throw ManyueAiRuntime.CancelledException()
            }
            throw error
        } finally {
            lastUsedAt = SystemClock.elapsedRealtime()
        }
    }

    private fun read(
        connection: Connection,
        isCancelled: () -> Boolean,
        deadline: Long,
    ): ManyueLegacyProtocol.Response {
        val response = connection.responses.submit<ManyueLegacyProtocol.Response> {
            ManyueLegacyProtocol.readResponse(connection.process.inputStream)
        }
        while (true) {
            if (isCancelled()) throw ManyueAiRuntime.CancelledException()
            check(SystemClock.elapsedRealtime() < deadline) { "Native session timed out" }
            try {
                return response.get(100, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Bounded waits keep cancellation responsive while native stdout remains blocking.
            }
        }
    }

    @Synchronized
    fun close() {
        val previous = connection
        connection = null
        previous?.close()
    }

    @Synchronized
    fun releaseWhenIdle(retainForReading: Boolean) {
        if (!retainForReading || SystemClock.elapsedRealtime() - lastUsedAt >= 30_000L) close()
    }
}
