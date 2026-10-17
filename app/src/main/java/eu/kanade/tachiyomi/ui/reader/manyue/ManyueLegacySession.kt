package eu.kanade.tachiyomi.ui.reader.manyue

import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Single-worker owner. The existing reader scheduler still owns priority and request cancellation. */
internal class ManyueLegacySession(
    private val phaseClockNanos: (() -> Long)? = null,
) {
    enum class Phase {
        PROCESS_START,
        READY_WAIT,
        REQUEST_WRITE,
        RESULT_WAIT,
    }

    enum class PhaseOutcome {
        /** This phase returned; RESULT_WAIT does not imply later response/output validation passed. */
        SUCCEEDED,
        CANCELLED,
        FAILED,
    }

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

    /** A null listener skips phase clock reads; callback failures never affect the Native request. */
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
        cooperativeCancellation: Boolean = false,
        isConfigurationCurrent: () -> Boolean = { true },
        onPhase: ((Phase, Long, PhaseOutcome) -> Unit)? = null,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + 240_000L
        var current = connection
        var startup: ManyueLegacyProtocol.Response? = null
        var ownsOutput = false
        var cancellationMarker: File? = null
        var retainCancelledConnection = false
        var cancellationRequestedAt: Long? = null
        try {
            if (isCancelled()) throw ManyueAiRuntime.CancelledException()
            val inputPath = input.canonicalFile
            val outputPath = output.canonicalFile
            val logPath = logFile.canonicalFile
            val markerPath = File(outputPath.path + ".cancel").canonicalFile
            require(inputPath != outputPath && inputPath != logPath && outputPath != logPath &&
                (!cooperativeCancellation || (markerPath != inputPath && markerPath != logPath))
            ) {
                "Native work files must preserve the original file"
            }
            if (cooperativeCancellation) {
                check(!markerPath.exists() || markerPath.delete()) { "Unable to remove a stale cancellation marker" }
                cancellationMarker = markerPath
            }
            ownsOutput = true
            check(!output.exists() || output.delete()) { "Unable to remove a stale native output" }
            if (current == null || current.key != key || !current.process.isAlive) {
                close()
                val process = measurePhase(onPhase, Phase.PROCESS_START, { false }, startProcess)
                val newConnection = Connection(key, process).also { connection = it }
                current = newConnection
                onProcessStarted(newConnection.process)
                startup = measurePhase(onPhase, Phase.READY_WAIT, { false }) {
                    read(newConnection, isCancelled, deadline).also { response ->
                        check(
                            response.kind == ManyueLegacyProtocol.READY && response.status == 0 &&
                                response.requestId == 0L && response.modelLoads == 1,
                        ) {
                            "Native session failed to initialize"
                        }
                    }
                }
            } else {
                onProcessStarted(current.process)
            }
            val activeConnection = checkNotNull(current)
            val id = nextRequestId++
            check(id > 0) { "Native request sequence exhausted" }
            measurePhase(onPhase, Phase.REQUEST_WRITE, { false }) {
                ManyueLegacyProtocol.writeRequest(
                    activeConnection.process.outputStream,
                    id,
                    targetWidth,
                    input.path,
                    output.path,
                )
            }
            val response = measurePhase(
                onPhase,
                Phase.RESULT_WAIT,
                { response -> response.kind == ManyueLegacyProtocol.RESULT &&
                    response.status == ManyueLegacyProtocol.CANCELLED },
            ) {
                read(activeConnection, isCancelled, deadline, cancellationMarker, isConfigurationCurrent) {
                    cancellationRequestedAt = SystemClock.elapsedRealtime()
                }
            }
            check(
                response.kind == ManyueLegacyProtocol.RESULT && response.requestId == id &&
                    response.modelLoads == 0,
            ) {
                "Native session request failed (${response.status})"
            }
            if (response.status == ManyueLegacyProtocol.CANCELLED) {
                check(cooperativeCancellation && isCancelled() && response.width == 0 && response.height == 0 &&
                    response.outputBytes == 0L
                ) { "Unexpected native cancellation response" }
                retainCancelledConnection = isConfigurationCurrent()
                throw ManyueAiRuntime.CancelledException(
                    retainCancelledConnection,
                    cancellationRequestedAt?.let { SystemClock.elapsedRealtime() - it },
                )
            }
            check(response.status == 0) { "Native session request failed (${response.status})" }
            check(
                response.width == targetWidth && response.height > 0 &&
                    output.isFile && output.length() > 0 && output.length() == response.outputBytes,
            ) { "Native session output is missing or invalid" }
            if (isCancelled()) {
                retainCancelledConnection = cooperativeCancellation && isConfigurationCurrent()
                throw ManyueAiRuntime.CancelledException(
                    retainCancelledConnection,
                    cancellationRequestedAt?.let { SystemClock.elapsedRealtime() - it },
                )
            }
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
                if (!retainCancelledConnection || !isConfigurationCurrent()) close()
            } finally {
                if (ownsOutput) output.delete()
            }
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                throw ManyueAiRuntime.CancelledException()
            }
            throw error
        } finally {
            cancellationMarker?.delete()
            lastUsedAt = SystemClock.elapsedRealtime()
        }
    }

    private fun read(
        connection: Connection,
        isCancelled: () -> Boolean,
        deadline: Long,
        cancellationMarker: File? = null,
        isConfigurationCurrent: () -> Boolean = { true },
        onCancellationRequested: () -> Unit = {},
    ): ManyueLegacyProtocol.Response {
        val response = connection.responses.submit<ManyueLegacyProtocol.Response> {
            ManyueLegacyProtocol.readResponse(connection.process.inputStream)
        }
        var cancellationRequestedAt: Long? = null
        while (true) {
            if (!isConfigurationCurrent()) throw ManyueAiRuntime.CancelledException()
            if (isCancelled()) {
                if (cancellationMarker == null) throw ManyueAiRuntime.CancelledException()
                if (cancellationRequestedAt == null) {
                    cancellationMarker.writeText("1")
                    cancellationRequestedAt = SystemClock.elapsedRealtime()
                    onCancellationRequested()
                }
            }
            if (cancellationRequestedAt?.let { SystemClock.elapsedRealtime() - it >= 2_000L } == true) {
                throw ManyueAiRuntime.CancelledException()
            }
            check(SystemClock.elapsedRealtime() < deadline) { "Native session timed out" }
            try {
                return response.get(100, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // Bounded waits keep cancellation responsive while native stdout remains blocking.
            }
        }
    }

    private inline fun <T> measurePhase(
        noinline onPhase: ((Phase, Long, PhaseOutcome) -> Unit)?,
        phase: Phase,
        isCancelledResult: (T) -> Boolean,
        block: () -> T,
    ): T {
        if (onPhase == null) return block()
        val startedAtNanos = try {
            phaseClockNanos?.invoke() ?: SystemClock.elapsedRealtimeNanos()
        } catch (_: Throwable) {
            null
        }
        var outcome = PhaseOutcome.FAILED
        try {
            val result = block()
            outcome = if (isCancelledResult(result)) PhaseOutcome.CANCELLED else PhaseOutcome.SUCCEEDED
            return result
        } catch (error: Throwable) {
            outcome = if (error is ManyueAiRuntime.CancelledException || error is InterruptedException) {
                PhaseOutcome.CANCELLED
            } else {
                PhaseOutcome.FAILED
            }
            throw error
        } finally {
            val elapsedNanos = try {
                val finishedAtNanos = phaseClockNanos?.invoke() ?: SystemClock.elapsedRealtimeNanos()
                if (startedAtNanos == null) 0L else (finishedAtNanos - startedAtNanos).coerceAtLeast(0L)
            } catch (_: Throwable) {
                0L
            }
            // Diagnostics must not change request, cancellation, or process behavior.
            try {
                onPhase(phase, elapsedNanos, outcome)
            } catch (_: Throwable) {
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
