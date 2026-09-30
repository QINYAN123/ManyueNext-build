package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ManyueAiRuntimeTest {

    @Test fun `both native models use bounded reader-first execution`() {
        for (model in ManyueAiModel.entries) {
            val command = ManyueAiRuntime.buildCommand("worker", "input", "output", "models", model, "webp")
            assertEquals("0", command[command.indexOf("-t") + 1])
            assertEquals("1:1:1", command[command.indexOf("-j") + 1])
            assertEquals("2", command[command.indexOf("-s") + 1])
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

    @Test fun `fixed 2x native output gate requires exact dimensions`() {
        assertTrue(ManyueAiUpscaler.hasExpectedFixed2xOutput(1200, 1800, 2400, 3600))
        assertFalse(ManyueAiUpscaler.hasExpectedFixed2xOutput(1200, 1800, 2398, 3600))
        assertFalse(ManyueAiUpscaler.hasExpectedFixed2xOutput(1200, 1800, 2400, 3598))
        assertFalse(ManyueAiUpscaler.hasExpectedFixed2xOutput(Int.MAX_VALUE, 1, 2, 2))
        assertFalse(ManyueAiUpscaler.hasExpectedFixed2xOutput(0, 1, 0, 2))
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
        )

        assertFalse(command.contains("-n"))
    }
}
