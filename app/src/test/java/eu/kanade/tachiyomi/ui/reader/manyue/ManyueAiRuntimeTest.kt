package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ManyueAiRuntimeTest {

    @Test fun `both native models use bounded reader-first execution`() {
        for (model in ManyueAiModel.entries.filterNot { it.continuousScale }) {
            val command = ManyueAiRuntime.buildCommand("worker", "input", "output", "models", model, "webp", 1035)
            assertEquals("0", command[command.indexOf("-t") + 1])
            assertEquals("1:1:1", command[command.indexOf("-j") + 1])
            assertEquals("2", command[command.indexOf("-s") + 1])
            assertEquals("1035", command[command.indexOf("-w") + 1])
            assertFalse(command.contains("-v"))
        }
    }

    @Test fun `normal work continues immediately and pressure adds only a bounded short gap`() {
        var now = -1_000_000_000L
        val pacer = ManyueWorkPacer { now }
        assertFalse(pacer.isBlocked())
        pacer.afterWork(now - 100_000_000L)
        assertFalse(pacer.isBlocked())

        pacer.afterWork(now - 20_000_000_000L)
        assertFalse(pacer.isBlocked(), "idle reading should allow the next AI request to continue")

        pacer.afterWork(now - 20_000_000_000L, pressureActive = true)
        now += 99_999_999L
        assertTrue(pacer.isBlocked())
        now += 1L
        assertFalse(pacer.isBlocked())

        pacer.afterWork(now - 100_000_000L, pressureActive = true)
        now += 24_999_999L
        assertTrue(pacer.isBlocked())
        now += 1L
        assertFalse(pacer.isBlocked())
    }

    @Test fun `pressure pacing polls stop at deadline without an extra eighty milliseconds`() {
        var now = -1_000_000_000L
        val pacer = ManyueWorkPacer { now }
        pacer.afterWork(now - 1_000_000_000L, pressureActive = true)
        var waitedMillis = 0L
        while (pacer.isBlocked()) {
            val delayMillis = pacer.pollDelayMillis(readerGateBlocked = false)
            waitedMillis += delayMillis
            now += TimeUnit.MILLISECONDS.toNanos(delayMillis)
        }
        assertEquals(100L, waitedMillis, "the configured100ms deadline must not turn into160ms")
    }

    @Test fun `pressure deadline rounds up tiny remainder and preserves independent reader gate`() {
        var now = 0L
        val pacer = ManyueWorkPacer { now }
        pacer.afterWork(now - 4_000_000L, pressureActive = true)
        now += 7_999_999L
        assertTrue(pacer.isBlocked())
        assertEquals(1L, pacer.pollDelayMillis(readerGateBlocked = false))
        assertEquals(80L, pacer.pollDelayMillis(readerGateBlocked = true))
        now += 1L
        assertFalse(pacer.isBlocked())
        assertEquals(80L, pacer.pollDelayMillis(readerGateBlocked = true))
    }

    // isSupported() depends on Build.SUPPORTED_64_BIT_ABIS; on a JVM unit test it returns false.
    // The class loads and constants are reachable.
    @Test fun `runtime constants exposed`() {
        assertEquals(48_000_000, ManyueAiRuntime.MAX_MODEL_OUTPUT_PIXELS)
        assertEquals(67108864, ManyueAiRuntime.MAX_INPUT_BYTES)
    }

    @Test fun `fixed target width always doubles`() {
        val w = ManyueAiUpscaler.resolveTargetWidth(2500, 2001, 2344)
        assertEquals(5000, w)
    }

    @Test fun `fixed target width doubles small originals`() {
        val w = ManyueAiUpscaler.resolveTargetWidth(800, 2001, 2344)
        assertEquals(1600, w)
    }

    @Test fun `native 2x doubles when under cap`() {
        val w = ManyueAiUpscaler.resolveTargetWidth(1000, 2003, 0)
        assertEquals(2000, w)
    }

    @Test fun `fixed 2x does not silently fall back to source width`() {
        val w = ManyueAiUpscaler.resolveTargetWidth(2000, 2003, 0)
        assertEquals(4000, w)
    }

    @Test fun `target output gate rejects a legacy worker that still writes fixed 2x`() {
        assertTrue(ManyueAiUpscaler.hasExpectedTargetOutput(690, 1421, 1035, 1035, 2132))
        assertFalse(ManyueAiUpscaler.hasExpectedTargetOutput(690, 1421, 1035, 1380, 2842))
        assertFalse(ManyueAiUpscaler.hasExpectedTargetOutput(690, 1421, 1035, 1035, 2131))
        assertTrue(ManyueAiUpscaler.hasExpectedTargetOutput(690, 1421, 1380, 1380, 2842))
        assertTrue(ManyueAiUpscaler.hasExpectedTargetOutput(690, 1421, 690, 690, 1421))
    }

    @Test fun `target geometry keeps rounded width aspect ratio at fractional scales`() {
        // 690 * 1.25 rounds to 863; height follows that actual width, not nominal 1.25.
        assertEquals(863, ManyueAiUpscaler.customTargetWidth(690, 125))
        assertEquals(1777, ManyueAiUpscaler.targetHeight(690, 1421, 863))
        assertEquals(2132, ManyueAiUpscaler.targetHeight(690, 1421, 1035))
        assertEquals(1421, ManyueAiUpscaler.targetHeight(690, 1421, 690))
        assertEquals(2842, ManyueAiUpscaler.targetHeight(690, 1421, 1380))
    }

    @Test fun `target geometry rejects invalid range and integer overflow`() {
        assertEquals(0, ManyueAiUpscaler.targetHeight(690, 1421, 689))
        assertEquals(0, ManyueAiUpscaler.targetHeight(690, 1421, 1381))
        assertEquals(0, ManyueAiUpscaler.targetHeight(0, 1, 1))
        assertEquals(0, ManyueAiUpscaler.targetHeight(1, 0, 2))
        assertEquals(0, ManyueAiUpscaler.targetHeight(1, Int.MAX_VALUE, 2))
        assertEquals(Int.MAX_VALUE, ManyueAiUpscaler.targetHeight(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
        assertFalse(ManyueAiUpscaler.hasExpectedTargetOutput(0, 1, 1, 1, 1))
    }

    @Test fun `native command requires an explicit positive target width`() {
        assertThrows(IllegalArgumentException::class.java) {
            ManyueAiRuntime.buildCommand(
                "worker",
                "input",
                "output",
                "models",
                ManyueAiModel.FAST_REAL_CUGAN,
                "webp",
                0,
            )
        }
    }

    @Test fun `native log tail reads only bounded bytes`() {
        val file = Files.createTempFile("manyue-native-log", ".log").toFile()
        try {
            file.writeText("x".repeat(20_000) + "\nTAIL_SENTINEL")
            val tail = ManyueAiRuntime.readLogTail(file)
            assertTrue(tail.length <= 4_106)
            assertTrue(tail.endsWith("TAIL_SENTINEL"))
        } finally {
            file.delete()
        }
    }

    @Test fun `real cugan selects the packaged no-denoise model`() {
        val command = ManyueAiRuntime.buildCommand(
            binaryPath = "/native/libmanyue_realcugan.so",
            inputPath = "/cache/input.webp",
            outputPath = "/cache/output.webp",
            modelPath = "/cache/models-se",
            model = ManyueAiModel.FAST_REAL_CUGAN,
            outputFormat = "webp",
            targetWidth = 1035,
        )

        val noiseIndex = command.indexOf("-n")
        assertTrue(noiseIndex >= 0)
        assertEquals("0", command[noiseIndex + 1])
        assertTrue(ManyueAiModel.FAST_REAL_CUGAN.modelFiles.contains("up2x-no-denoise.bin"))
        assertFalse(command.contains("-1"))
    }

    @Test fun `real esrgan command does not receive cugan noise selector`() {
        val command = ManyueAiRuntime.buildCommand(
            binaryPath = "/native/libmanyue_realesr.so",
            inputPath = "/cache/input.webp",
            outputPath = "/cache/output.webp",
            modelPath = "/cache/models-esrgan",
            model = ManyueAiModel.QUALITY_REAL_ESRGAN,
            outputFormat = "webp",
            targetWidth = 863,
        )

        assertFalse(command.contains("-n"))
    }

    @Test fun `legacy capability probe checks the selected worker and shared libraries only`() {
        val cugan = ManyueAiRuntime.requiredLegacyRuntimeHashes(ManyueAiModel.FAST_REAL_CUGAN)
        assertEquals(
            setOf("libmanyue_realcugan.so", "libncnn.so", "libomp.so"),
            cugan.keys,
        )
        assertFalse(cugan.containsKey(ManyueAiModel.QUALITY_REAL_ESRGAN.runnerName))

        val realesr = ManyueAiRuntime.requiredLegacyRuntimeHashes(ManyueAiModel.QUALITY_REAL_ESRGAN)
        assertEquals(
            setOf("libmanyue_realesr.so", "libncnn.so", "libomp.so"),
            realesr.keys,
        )
        assertFalse(realesr.containsKey(ManyueAiModel.FAST_REAL_CUGAN.runnerName))
        assertThrows(IllegalArgumentException::class.java) {
            ManyueAiRuntime.requiredLegacyRuntimeHashes(ManyueAiModel.MOBILE_LITE)
        }
    }

    @Test fun `quality model runtime hashes match packaged arm64 JNI assets`() {
        val workingDirectory = Path.of(System.getProperty("user.dir"))
        val relativeNativeDirectory = Path.of("src/main/jniLibs/arm64-v8a")
        val nativeDirectory = listOf(
            workingDirectory.resolve(relativeNativeDirectory),
            workingDirectory.resolve("app").resolve(relativeNativeDirectory),
        ).firstOrNull { Files.isDirectory(it) }
        assertTrue(nativeDirectory != null, "Could not locate app/src/main/jniLibs/arm64-v8a")
        val assetsDirectory = nativeDirectory ?: error("JNI asset directory was not found")

        val models = listOf(
            ManyueAiModel.QUALITY_REAL_ESRGAN,
            ManyueAiModel.QUALITY_NATIVE2,
            ManyueAiModel.QUALITY_HYBRID,
        )
        val actualHashes = mutableMapOf<String, String>()
        for (model in models) {
            for ((name, expectedHash) in ManyueAiRuntime.requiredLegacyRuntimeHashes(model)) {
                val asset = assetsDirectory.resolve(name)
                assertTrue(Files.isRegularFile(asset), "Packaged arm64 JNI asset is missing: $name")
                val actualHash = actualHashes.getOrPut(name) { sha256(asset) }
                assertEquals(expectedHash, actualHash, "${model.id} runtime hash differs for $name")
            }
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    @Test fun `legacy process callback failure stops child and removes partial output`() {
        val output = Files.createTempFile("manyue-native-output", ".webp").toFile()
        val process = TestNativeProcess()
        var actionReached = false
        try {
            assertThrows(IllegalStateException::class.java) {
                ManyueAiRuntime.runTrackedProcess(
                    process = process,
                    outputFile = output,
                    onProcessStarted = { throw IllegalStateException("tracking failed") },
                ) {
                    actionReached = true
                }
            }

            assertFalse(actionReached)
            assertTrue(process.destroyed)
            assertFalse(output.exists())
        } finally {
            output.delete()
        }
    }

    @Test fun `interrupted graceful stop force kills child before deleting partial output`() {
        val output = Files.createTempFile("manyue-native-interrupted", ".webp").toFile()
        val process = InterruptedWaitNativeProcess { assertTrue(output.exists()) }
        val thread = Thread.currentThread()
        val wasInterrupted = Thread.interrupted()
        try {
            assertThrows(ManyueAiRuntime.CancelledException::class.java) {
                ManyueAiRuntime.runTrackedProcess(
                    process = process,
                    outputFile = output,
                    onProcessStarted = {},
                ) {
                    throw InterruptedException("cancel while waiting for child")
                }
            }

            assertTrue(process.forceKilled)
            assertFalse(process.isAlive)
            assertTrue(output.exists().not(), "partial output must be removed after the child exits")
            assertTrue(thread.isInterrupted, "interruption must be restored after process cleanup")
        } finally {
            Thread.interrupted()
            if (wasInterrupted) thread.interrupt()
            output.delete()
        }
    }

    private class TestNativeProcess : Process() {
        var destroyed = false
            private set

        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = destroyed
        override fun exitValue(): Int = if (destroyed) 143 else 0
        override fun destroy() {
            destroyed = true
        }
        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
        override fun isAlive(): Boolean = !destroyed
    }

    private class InterruptedWaitNativeProcess(private val onWait: () -> Unit) : Process() {
        var forceKilled = false
            private set

        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int {
            onWait()
            return 137
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            throw InterruptedException("simulated interrupt during graceful stop")
        }
        override fun exitValue(): Int = if (forceKilled) 137 else throw IllegalThreadStateException()
        override fun destroy() = Unit
        override fun destroyForcibly(): Process {
            forceKilled = true
            return this
        }
        override fun isAlive(): Boolean = !forceKilled
    }
}
