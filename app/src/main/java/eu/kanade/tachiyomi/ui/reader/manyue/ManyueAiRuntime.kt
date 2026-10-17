package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import android.os.Build
import android.os.SystemClock
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the bundled arm64 ncnn command-line workers executed via ProcessBuilder.
 */
object ManyueAiRuntime {

    enum class Capability(val userMessage: String) {
        READY("原生程序文件完整性已校验"),
        UNSUPPORTED_ABI("仅支持 arm64-v8a"),
        MISSING_BINARY("AI 原生程序缺失"),
        CORRUPT_BINARY("AI 原生程序校验失败"),
        NOT_EXECUTABLE("AI 原生程序不可执行"),
    }

    const val MAX_MODEL_OUTPUT_PIXELS = 48_000_000
    const val MAX_INPUT_BYTES = 67108864 // 64 MiB
    private const val TIMEOUT_SECONDS = 240L
    private const val OVERLAY_TIMEOUT_SECONDS = 90L
    private const val WAIT_SLICE_MS = 250L
    private const val LOG_TAIL_BYTES = 4096

    // Keep the proven automatic tile selection; tiny fixed tiles previously increased dispatch
    // overhead. Reduce pipeline concurrency and pace pages instead of guessing a device tile size.
    private const val TILE_SIZE = 0

    // A single process can still fan out its load/proc/save pipeline. Prefer reader headroom.
    private const val JOBS = "1:1:1"

    // The packaged Real-CUGAN model is up2x-no-denoise. Upstream selects
    // up2x-conservative for -1, so the model selector must be 0.
    private const val REAL_CUGAN_NOISE_LEVEL = "0"

    private val legacySession = ManyueLegacySession()

    class CancelledException(
        val residentRetained: Boolean = false,
        val acknowledgementMillis: Long? = null,
    ) : CancellationException("Manyue AI request cancelled")

    private const val EXPECTED_REAL_ESRGAN_SHA256 = "a4c5581ef5b0d6e0f96e3b7c6d607d7a6f97565f80cc1f6760b1f3953c3f2698"
    private const val EXPECTED_REAL_CUGAN_SHA256 = "d4ab907cb698b621f429b09ec5757318b23a1e05f8a7f77575c9af8ff24cb464"
    private const val EXPECTED_NCNN_SHA256 = "87d150e735157b09aa20f26f5e57f72468c548e7ce98ce407ec50ee7e14a52dd"
    private const val EXPECTED_LIBOMP_SHA256 = "da75dcbe6026a3e08d01bfe86860159432051b329a84deb5ee042ce9b8e1a302"

    @Volatile private var cachedProbe: Triple<String, String, Capability>? = null

    fun isSupported(): Boolean = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")

    fun isSupported(context: Context): Boolean = probe(context) == Capability.READY

    /** Fast after the first call; verifies the exact packaged native files once per install path. */
    fun probe(context: Context, model: ManyueAiModel = ManyueRuntimeState.aiModel): Capability {
        if (model.continuousScale) return probeLite(context)
        if (!isSupported()) return Capability.UNSUPPORTED_ABI
        val nativeDir = context.applicationInfo.nativeLibraryDir
        cachedProbe?.takeIf { it.first == nativeDir && it.second == model.id }?.let { return it.third }
        val expected = requiredLegacyRuntimeHashes(model)
        val result = runCatching {
            when {
                expected.keys.any { name ->
                    val file = File(nativeDir, name)
                    !file.isFile || file.length() == 0L
                } -> Capability.MISSING_BINARY
                expected.any { (name, hash) -> sha256(File(nativeDir, name)) != hash } -> Capability.CORRUPT_BINARY
                run {
                    val runner = File(nativeDir, model.runnerName)
                    !runner.canExecute() && (!runner.setExecutable(true, false) || !runner.canExecute())
                } -> Capability.NOT_EXECUTABLE
                else -> Capability.READY
            }
        }.getOrElse {
            logcat { "Manyue capability probe failed: ${it.javaClass.simpleName}" }
            Capability.CORRUPT_BINARY
        }
        cachedProbe = Triple(nativeDir, model.id, result)
        logcat { "Manyue capability=$result nativeDir=$nativeDir" }
        return result
    }

    /** Verify only the selected legacy worker and its shared runtime dependencies. */
    internal fun requiredLegacyRuntimeHashes(model: ManyueAiModel): Map<String, String> {
        require(!model.continuousScale) { "Lite has a separate resident runtime" }
        val runnerHash = when (model) {
            ManyueAiModel.FAST_REAL_CUGAN -> EXPECTED_REAL_CUGAN_SHA256
            ManyueAiModel.QUALITY_REAL_ESRGAN, ManyueAiModel.QUALITY_NATIVE2,
            ManyueAiModel.QUALITY_HYBRID -> EXPECTED_REAL_ESRGAN_SHA256
            else -> error("Unknown legacy model: ${model.id}")
        }
        return mapOf(
            model.runnerName to runnerHash,
            "libncnn.so" to EXPECTED_NCNN_SHA256,
            "libomp.so" to EXPECTED_LIBOMP_SHA256,
        )
    }

    @Volatile private var cachedLiteProbe: Pair<String, Capability>? = null

    private fun probeLite(context: Context): Capability {
        if (!isSupported()) return Capability.UNSUPPORTED_ABI
        val nativeDir = context.applicationInfo.nativeLibraryDir
        cachedLiteProbe?.takeIf { it.first == nativeDir }?.let { return it.second }
        val expected = mapOf(
            ManyueAiModel.MOBILE_LITE.runnerName to ManyueLiteAssets.NATIVE_SHA256,
            "libncnn.so" to EXPECTED_NCNN_SHA256,
            "libomp.so" to EXPECTED_LIBOMP_SHA256,
        )
        val result = runCatching {
            if (expected.keys.any { !File(nativeDir, it).isFile }) {
                Capability.MISSING_BINARY
            } else if (expected.any { (name, hash) -> sha256(File(nativeDir, name)) != hash }) {
                Capability.CORRUPT_BINARY
            } else {
                Capability.READY
            }
        }.getOrDefault(Capability.CORRUPT_BINARY)
        cachedLiteProbe = nativeDir to result
        return result
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Runs the native upscaler. Throws on any failure; caller falls back to original image.
     */
    fun upscale(
        context: Context,
        inputFile: File,
        outputFile: File,
        modelDir: File,
        logFile: File,
        model: ManyueAiModel = ManyueAiModel.DEFAULT,
        outputFormat: String = "webp",
        targetWidth: Int,
        isCancelled: () -> Boolean = { false },
        onProcessStarted: (Process) -> Unit = {},
    ) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, model.runnerName)
        val ncnn = File(nativeDir, "libncnn.so")
        require(!model.continuousScale) { "Lite uses resident JNI, not the fixed-2x CLI" }
        require(probe(context, model) == Capability.READY) { "AI runtime unavailable: ${probe(context, model)}" }
        require(inputFile.isFile && inputFile.length() > 0L) { "AI input missing or empty: $inputFile" }
        require(binary.isFile && binary.length() > 0L) { "native binary missing: $binary" }
        require(ncnn.isFile && ncnn.length() > 0L) { "libncnn missing: $ncnn" }
        if (!binary.canExecute() && (!binary.setExecutable(true, false) || !binary.canExecute())) {
            error("native binary is not executable: $binary")
        }
        if (model.modelFiles.any { !File(modelDir, it).isFile }) {
            error("model files missing in $modelDir")
        }
        outputFile.delete()
        logFile.parentFile?.mkdirs()
        logFile.delete()

        val command = buildCommand(
            binaryPath = binary.absolutePath,
            inputPath = inputFile.absolutePath,
            outputPath = outputFile.absolutePath,
            modelPath = modelDir.absolutePath,
            model = model,
            outputFormat = outputFormat,
            targetWidth = targetWidth,
        )
        val pb = ProcessBuilder(command)
        pb.directory(File(nativeDir))
        pb.environment()["LD_LIBRARY_PATH"] = nativeDir
        pb.environment()["OMP_NUM_THREADS"] = "1"
        pb.environment()["OMP_WAIT_POLICY"] = "PASSIVE"
        pb.redirectErrorStream(true)
        // Always drain native stdout/stderr to a file. An unread Process pipe can fill and
        // deadlock waitFor() on real devices even though JVM unit tests stay green.
        pb.redirectOutput(logFile)

        val startedAt = SystemClock.elapsedRealtime()
        logcat {
            "Manyue native start model=${model.id} binary=${binary.absolutePath} inputBytes=${inputFile.length()} " +
                "targetWidth=$targetWidth tile=$TILE_SIZE jobs=$JOBS omp=1 passive=true LD_LIBRARY_PATH=$nativeDir"
        }
        val process = pb.start()
        runTrackedProcess(process, outputFile, onProcessStarted) {
            logcat { "Manyue native process started" }
            val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)
            while (!process.waitFor(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) {
                    throw CancelledException()
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    throw RuntimeException(
                        "AI upscaler timeout after ${TIMEOUT_SECONDS}s; ${logTail(logFile)}",
                    )
                }
            }
            if (isCancelled()) throw CancelledException()
            val code = process.exitValue()
            if (code != 0) {
                throw RuntimeException("AI upscaler exit code=$code; ${logTail(logFile)}")
            }
            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw RuntimeException("AI upscaler produced no output; ${logTail(logFile)}")
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val telemetry = runtimeTelemetry(logFile)
            logcat {
                "Manyue native exit code=0 model=${model.id} outputBytes=${outputFile.length()} " +
                    "durationMs=$elapsedMs $telemetry"
            }
        }
    }

    /** Keeps the selected model loaded; the one-file CLI remains available for A/B validation. */
    internal fun upscaleResident(
        context: Context,
        inputFile: File,
        outputFile: File,
        modelDir: File,
        logFile: File,
        model: ManyueAiModel,
        targetWidth: Int,
        isCancelled: () -> Boolean,
        onProcessStarted: (Process) -> Unit,
        isConfigurationCurrent: () -> Boolean = { true },
        onPhase: ((ManyueLegacySession.Phase, Long, ManyueLegacySession.PhaseOutcome) -> Unit)? = null,
    ): String {
        require(!model.continuousScale && probe(context, model) == Capability.READY)
        require(inputFile.isFile && inputFile.length() in 1..MAX_INPUT_BYTES.toLong())
        require(targetWidth > 0 && model.modelFiles.all { File(modelDir, it).isFile })
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val command = buildResidentCommand(File(nativeDir, model.runnerName).path, modelDir.path, model)
        logFile.parentFile?.mkdirs()
        return legacySession.upscale(
            key = "$nativeDir:${model.id}:${modelDir.canonicalPath}",
            startProcess = {
                ProcessBuilder(command).apply {
                    directory(File(nativeDir))
                    environment()["LD_LIBRARY_PATH"] = nativeDir
                    environment()["OMP_NUM_THREADS"] = "1"
                    environment()["OMP_WAIT_POLICY"] = "PASSIVE"
                    // Bound each quality-model GPU submission without changing its arithmetic.
                    environment()["MANYUE_GPU_CHUNK"] = "1"
                    redirectErrorStream(false)
                }.start()
            },
            input = inputFile,
            output = outputFile,
            targetWidth = targetWidth,
            logFile = logFile,
            isCancelled = isCancelled,
            onProcessStarted = onProcessStarted,
            cooperativeCancellation = model.usesQualityRuntime,
            isConfigurationCurrent = isConfigurationCurrent,
            onPhase = onPhase,
        )
    }

    internal fun buildResidentCommand(binaryPath: String, modelPath: String, model: ManyueAiModel): List<String> {
        require(!model.continuousScale)
        return buildList {
            add(binaryPath)
            addAll(listOf("-S", "-s", "2", "-t", TILE_SIZE.toString(), "-m", modelPath, "-j", JOBS))
            if (model == ManyueAiModel.FAST_REAL_CUGAN) addAll(listOf("-n", REAL_CUGAN_NOISE_LEVEL, "-c", "3"))
        }
    }

    internal fun releaseResidentOnWorker() = legacySession.close()

    internal fun releaseResidentWhenIdleOnWorker(retainForReading: Boolean) =
        legacySession.releaseWhenIdle(retainForReading)

    /** Owns the child from publication through validation and removes any incomplete output. */
    internal fun runTrackedProcess(
        process: Process,
        outputFile: File,
        onProcessStarted: (Process) -> Unit,
        action: () -> Unit,
    ) {
        try {
            onProcessStarted(process)
            action()
        } catch (t: Throwable) {
            stop(process)
            outputFile.delete()
            if (t is InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancelledException()
            }
            throw t
        }
    }

    /** Exposed internally so unit tests can lock the native CLI/model pairing. */
    internal fun buildCommand(
        binaryPath: String,
        inputPath: String,
        outputPath: String,
        modelPath: String,
        model: ManyueAiModel,
        outputFormat: String,
        targetWidth: Int,
    ): List<String> {
        require(!model.continuousScale) { "Lite cannot run as a CLI model" }
        require(targetWidth > 0) { "AI target width must be positive" }
        return buildList {
            add(binaryPath)
            addAll(
                listOf(
                    "-i", inputPath,
                    "-o", outputPath,
                    "-s", "2",
                    "-w", targetWidth.toString(),
                    "-t", TILE_SIZE.toString(),
                    "-m", modelPath,
                    "-j", JOBS,
                    "-f", outputFormat,
                ),
            )
            if (model == ManyueAiModel.FAST_REAL_CUGAN) {
                addAll(listOf("-n", REAL_CUGAN_NOISE_LEVEL))
            }
        }
    }

    /**
     * Applies the optional Anime4KCPP ACNet B4 refinement on CPU at factor 1.0, keeping the
     * requested AI output dimensions. This is not a GPU shader. It is best-effort and never discards
     * an otherwise valid AI result.
     */
    fun applyAnime4kOverlay(
        context: Context,
        inputFile: File,
        outputFile: File,
        logFile: File,
        isCancelled: () -> Boolean = { false },
    ): Boolean {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, "libmanyue_anime4k.so")
        if (!binary.isFile || binary.length() == 0L) return false
        if (!binary.canExecute() && (!binary.setExecutable(true, false) || !binary.canExecute())) return false
        if (!inputFile.isFile || inputFile.length() == 0L) return false
        outputFile.delete()
        val overlayLog = File(logFile.parentFile ?: context.cacheDir, "anime4k.log")
        overlayLog.delete()
        val pb = ProcessBuilder(
            binary.absolutePath,
            "--input", inputFile.absolutePath,
            "--output", outputFile.absolutePath,
            "--model", "ACNetB4",
            "--processor", "CPU",
            "--factor", "1",
            "--threads", "1",
        )
        pb.directory(File(nativeDir))
        pb.environment()["LD_LIBRARY_PATH"] = nativeDir
        pb.redirectErrorStream(true)
        pb.redirectOutput(overlayLog)
        val process = try {
            pb.start()
        } catch (t: Throwable) {
            logcat { "Manyue Anime4K start failed: ${t.javaClass.simpleName}" }
            return false
        }
        val deadline = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(OVERLAY_TIMEOUT_SECONDS)
        try {
            while (!process.waitFor(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)) {
                if (isCancelled()) {
                    stop(process)
                    throw CancelledException()
                }
                if (SystemClock.elapsedRealtime() >= deadline) {
                    stop(process)
                    logcat { "Manyue Anime4K timeout; ${readLogTail(overlayLog)}" }
                    return false
                }
            }
        } catch (t: InterruptedException) {
            stop(process)
            Thread.currentThread().interrupt()
            throw CancelledException()
        }
        if (isCancelled()) throw CancelledException()
        if (process.exitValue() != 0 || !outputFile.isFile || outputFile.length() == 0L) {
            logcat { "Manyue Anime4K exit=${process.exitValue()}; ${readLogTail(overlayLog)}" }
            return false
        }
        logcat { "Manyue Anime4KCPP ACNetB4 CPU overlay complete; ${readLogTail(overlayLog)}" }
        return true
    }

    internal fun stop(process: Process) {
        runCatching { process.destroy() }
        var interrupted = false
        try {
            val exitedGracefully = try {
                process.waitFor(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
            if (!exitedGracefully) {
                process.destroyForcibly()
                // Do not let callers delete a partial output while the child can still
                // write it. waitFor() is retried uninterruptibly after the force-kill.
                while (true) {
                    try {
                        process.waitFor()
                        break
                    } catch (_: InterruptedException) {
                        interrupted = true
                        process.destroyForcibly()
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun readLogTail(file: File): String {
        val text = runCatching {
            RandomAccessFile(file, "r").use { log ->
                val length = log.length()
                val start = (length - LOG_TAIL_BYTES).coerceAtLeast(0L)
                val bytes = ByteArray((length - start).toInt())
                log.seek(start)
                var offset = 0
                while (offset < bytes.size) {
                    val count = log.read(bytes, offset, bytes.size - offset)
                    if (count <= 0) break
                    offset += count
                }
                String(bytes, 0, offset, StandardCharsets.UTF_8)
            }
        }.getOrDefault("")
            .replace('\n', ' ')
            .trim()
        return text.takeIf(String::isNotBlank)?.let { "nativeLog=$it" }.orEmpty()
    }

    /** Keep the useful device/tile line visible without copying the whole native log to UI. */
    private fun runtimeTelemetry(file: File): String {
        val line = runCatching {
            file.useLines { lines ->
                lines.firstOrNull {
                    it.contains("Gpu[") ||
                        it.contains("init gpu tilesize") ||
                        it.contains("init cpu tilesize")
                }
            }
        }.getOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return ""
        return "telemetry=${line.take(320)}"
    }

    private fun logTail(file: File): String = readLogTail(file)
}
