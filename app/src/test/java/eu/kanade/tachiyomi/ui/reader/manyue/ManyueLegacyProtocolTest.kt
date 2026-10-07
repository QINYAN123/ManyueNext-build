package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ManyueLegacyProtocolTest {

    @Test
    fun `request frame uses little endian lengths and UTF-8 paths`() {
        val inputPath = "C:\\漫画缓存\\道诡异仙 第4页.png"
        val outputPath = "C:\\cache\\Manyue 增强结果.webp"
        val inputBytes = inputPath.toByteArray(Charsets.UTF_8)
        val outputBytes = outputPath.toByteArray(Charsets.UTF_8)
        val requestId = 0x0102030405060708L
        val targetWidth = 2400
        val stream = ByteArrayOutputStream()

        ManyueLegacyProtocol.writeRequest(stream, requestId, targetWidth, inputPath, outputPath)

        val frame = stream.toByteArray()
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertArrayEquals("MNYQ".toByteArray(Charsets.US_ASCII), frame.copyOfRange(0, 4))
        assertEquals(20 + inputBytes.size + outputBytes.size, buffer.getInt(4))
        assertEquals(requestId, buffer.getLong(8))
        assertEquals(targetWidth, buffer.getInt(16))
        assertEquals(inputBytes.size, buffer.getInt(20))
        assertEquals(outputBytes.size, buffer.getInt(24))
        assertArrayEquals(inputBytes, frame.copyOfRange(28, 28 + inputBytes.size))
        assertArrayEquals(outputBytes, frame.copyOfRange(28 + inputBytes.size, frame.size))
        assertEquals(8 + 20 + inputBytes.size + outputBytes.size, frame.size)
    }

    @Test
    fun `request rejects nonpositive id and target width before writing`() {
        val invalidRequests = listOf(
            Triple(0L, 100, "zero request id"),
            Triple(-1L, 100, "negative request id"),
            Triple(1L, 0, "zero target width"),
            Triple(1L, -100, "negative target width"),
        )

        invalidRequests.forEach { (id, width, label) ->
            val output = ByteArrayOutputStream()
            assertThrows("$label", IllegalArgumentException::class.java) {
                ManyueLegacyProtocol.writeRequest(output, id, width, "input", "output")
            }
            assertEquals("$label must not emit a partial frame", 0, output.size())
        }
    }

    @Test
    fun `path validation is byte bounded rejects empty and NUL paths before writing`() {
        val maxPath = "é".repeat(2048)
        assertEquals(4096, maxPath.toByteArray(Charsets.UTF_8).size)
        val accepted = ByteArrayOutputStream()
        ManyueLegacyProtocol.writeRequest(accepted, 1, 1, maxPath, "out")
        assertEquals(8 + 20 + 4096 + 3, accepted.size())

        val rejectedPaths = listOf(
            "" to "out",
            "in" to "",
            "bad\u0000input" to "out",
            "in" to "bad\u0000output",
            ("é".repeat(2049)) to "out",
            "in" to ("x".repeat(4097)),
        )
        rejectedPaths.forEach { (inputPath, outputPath) ->
            val output = ByteArrayOutputStream()
            assertThrows(IllegalArgumentException::class.java) {
                ManyueLegacyProtocol.writeRequest(output, 1, 1, inputPath, outputPath)
            }
            assertEquals("invalid paths must not emit a partial frame", 0, output.size())
        }
    }

    @Test
    fun `response parser reads the fixed 92 byte little endian payload`() {
        val frame = responseFrame(
            kind = ManyueLegacyProtocol.RESULT,
            status = 0,
            requestId = 0x102030405060708L,
            initUs = 101,
            decodeUs = 202,
            inferUs = 303,
            resizeUs = 404,
            encodeUs = 505,
            totalUs = 606,
            outputBytes = 707,
            width = 2400,
            height = 4320,
            backend = 1,
            tile = 384,
            modelLoads = 1,
        )

        assertEquals(8 + RESPONSE_PAYLOAD_BYTES, frame.size)
        assertArrayEquals("MNYR".toByteArray(Charsets.US_ASCII), frame.copyOfRange(0, 4))
        assertEquals(RESPONSE_PAYLOAD_BYTES, ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(4))

        val response = ManyueLegacyProtocol.readResponse(ByteArrayInputStream(frame))
        assertEquals(ManyueLegacyProtocol.RESULT, response.kind)
        assertEquals(0, response.status)
        assertEquals(0x102030405060708L, response.requestId)
        assertEquals(101L, response.initUs)
        assertEquals(202L, response.decodeUs)
        assertEquals(303L, response.inferUs)
        assertEquals(404L, response.resizeUs)
        assertEquals(505L, response.encodeUs)
        assertEquals(606L, response.totalUs)
        assertEquals(707L, response.outputBytes)
        assertEquals(2400, response.width)
        assertEquals(4320, response.height)
        assertEquals(1, response.backend)
        assertEquals(384, response.tile)
        assertEquals(1, response.modelLoads)
    }

    @Test
    fun `response rejects bad magic and invalid lengths before reading payload`() {
        val badMagic = responseFrame().also { it[0] = 'X'.code.toByte() }
        assertThrows(IllegalStateException::class.java) {
            ManyueLegacyProtocol.readResponse(ByteArrayInputStream(badMagic))
        }

        for (length in listOf(-1, RESPONSE_PAYLOAD_BYTES - 1, RESPONSE_PAYLOAD_BYTES + 1, Int.MAX_VALUE)) {
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .put("MNYR".toByteArray(Charsets.US_ASCII))
                .putInt(length)
                .array()
            val input = ByteArrayInputStream(header)
            assertThrows("length=$length", IllegalStateException::class.java) {
                ManyueLegacyProtocol.readResponse(input)
            }
            assertEquals("invalid length must be rejected after only the header", 0, input.available())
        }
    }

    @Test
    fun `response rejects partial header and truncated payload`() {
        val complete = responseFrame()
        assertThrows(EOFException::class.java) {
            ManyueLegacyProtocol.readResponse(ByteArrayInputStream(complete.copyOfRange(0, 7)))
        }
        assertThrows(EOFException::class.java) {
            ManyueLegacyProtocol.readResponse(ByteArrayInputStream(complete.copyOf(complete.size - 1)))
        }
    }

    @Test
    fun `response rejects negative timing and byte metrics`() {
        // Absolute frame offsets for init, decode, infer, resize, encode, total and output bytes.
        val metricOffsets = listOf(24, 32, 40, 48, 56, 64, 72)
        metricOffsets.forEach { offset ->
            val frame = responseFrame()
            ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, -1L)
            assertThrows("negative metric at frame offset $offset", IllegalStateException::class.java) {
                ManyueLegacyProtocol.readResponse(ByteArrayInputStream(frame))
            }
        }
    }

    @Test
    fun `response rejects invalid identifiers dimensions backend and counters`() {
        val invalidFrames = listOf(
            responseFrame(requestId = -1),
            responseFrame(kind = ManyueLegacyProtocol.FATAL + 1),
            responseFrame(width = -1),
            responseFrame(height = -1),
            responseFrame(backend = 4),
            responseFrame(tile = -1),
            responseFrame(modelLoads = 2),
        )

        invalidFrames.forEach { frame ->
            assertThrows(IllegalStateException::class.java) {
                ManyueLegacyProtocol.readResponse(ByteArrayInputStream(frame))
            }
        }
    }

    private fun responseFrame(
        kind: Int = ManyueLegacyProtocol.RESULT,
        status: Int = 0,
        requestId: Long = 1,
        initUs: Long = 1,
        decodeUs: Long = 2,
        inferUs: Long = 3,
        resizeUs: Long = 4,
        encodeUs: Long = 5,
        totalUs: Long = 6,
        outputBytes: Long = 7,
        width: Int = 2400,
        height: Int = 4320,
        backend: Int = 1,
        tile: Int = 384,
        modelLoads: Int = 1,
    ): ByteArray = ByteBuffer.allocate(8 + RESPONSE_PAYLOAD_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("MNYR".toByteArray(Charsets.US_ASCII))
        .putInt(RESPONSE_PAYLOAD_BYTES)
        .putInt(kind)
        .putInt(status)
        .putLong(requestId)
        .putLong(initUs)
        .putLong(decodeUs)
        .putLong(inferUs)
        .putLong(resizeUs)
        .putLong(encodeUs)
        .putLong(totalUs)
        .putLong(outputBytes)
        .putInt(width)
        .putInt(height)
        .putInt(backend)
        .putInt(tile)
        .putInt(modelLoads)
        .array()

    private companion object {
        const val RESPONSE_PAYLOAD_BYTES = 92
    }
}
