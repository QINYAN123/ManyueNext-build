package eu.kanade.tachiyomi.ui.reader.manyue

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded binary IPC; image bytes and paths never appear in native stdout logs. */
internal object ManyueLegacyProtocol {
    const val READY = 0
    const val RESULT = 1
    const val FATAL = 2
    private const val RESPONSE_BYTES = 92
    private const val MAX_PATH_BYTES = 4096

    data class Response(
        val kind: Int,
        val status: Int,
        val requestId: Long,
        val initUs: Long,
        val decodeUs: Long,
        val inferUs: Long,
        val resizeUs: Long,
        val encodeUs: Long,
        val totalUs: Long,
        val outputBytes: Long,
        val width: Int,
        val height: Int,
        val backend: Int,
        val tile: Int,
        val modelLoads: Int,
    )

    fun writeRequest(output: OutputStream, id: Long, targetWidth: Int, inputPath: String, outputPath: String) {
        require(id > 0 && targetWidth > 0)
        val input = pathBytes(inputPath)
        val destination = pathBytes(outputPath)
        val payloadBytes = 20 + input.size + destination.size
        val frame = ByteBuffer.allocate(8 + payloadBytes).order(ByteOrder.LITTLE_ENDIAN)
        frame.put("MNYQ".toByteArray(Charsets.US_ASCII))
        frame.putInt(payloadBytes).putLong(id).putInt(targetWidth).putInt(input.size).putInt(destination.size)
        frame.put(input).put(destination)
        output.write(frame.array())
        output.flush()
    }

    private fun pathBytes(path: String): ByteArray {
        require('\u0000' !in path) { "Invalid native path" }
        return path.toByteArray(Charsets.UTF_8).also { require(it.size in 1..MAX_PATH_BYTES) }
    }

    fun readResponse(input: InputStream): Response {
        val reader = DataInputStream(input)
        val header = ByteArray(8)
        reader.readFully(header)
        check(header.copyOfRange(0, 4).contentEquals("MNYR".toByteArray(Charsets.US_ASCII))) {
            "Native session protocol mismatch"
        }
        check(ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int == RESPONSE_BYTES) {
            "Invalid native session response length"
        }
        val payload = ByteArray(RESPONSE_BYTES)
        reader.readFully(payload)
        val data = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return Response(
            data.int, data.int, data.long,
            data.long, data.long, data.long, data.long, data.long, data.long, data.long,
            data.int, data.int, data.int, data.int, data.int,
        ).also {
            check(it.kind in READY..FATAL && it.requestId >= 0 && it.backend in 0..3)
            check(
                listOf(it.initUs, it.decodeUs, it.inferUs, it.resizeUs, it.encodeUs, it.totalUs, it.outputBytes)
                    .all { value -> value >= 0 },
            ) { "Invalid native session metrics" }
            check(it.width >= 0 && it.height >= 0 && it.tile >= 0 && it.modelLoads in 0..1)
        }
    }
}
