package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class ManyueLegacySessionTest {

    @Test
    fun `same live key reuses worker and reports startup metrics only once`() = withSession { root, session ->
        val process = fakeProcess(
            responseFrame(kind = ManyueLegacyProtocol.READY, requestId = 0, initUs = 125_000, modelLoads = 1),
            responseFrame(kind = ManyueLegacyProtocol.RESULT, requestId = 1, outputBytes = 4),
            responseFrame(kind = ManyueLegacyProtocol.RESULT, requestId = 2, outputBytes = 4),
        )
        var starts = 0
        var callbacks = 0

        val first = upscale(
            session,
            root,
            "model-A",
            {
                starts++
                process
            },
            "page-1.webp",
            onProcessStarted = { callbacks++ },
        )
        val second = upscale(
            session,
            root,
            "model-A",
            {
                starts++
                process
            },
            "page-2.webp",
            onProcessStarted = { callbacks++ },
        )

        assertEquals(1, starts)
        assertEquals(2, callbacks)
        assertEquals(2, process.requestsSeen)
        assertTrue(process.isAlive)
        assertEquals(4L, File(root, "page-1.out").length())
        assertEquals(4L, File(root, "page-2.out").length())
        assertEquals("vulkan", JSONObject(first).getString("backend"))
        assertEquals(125.0, JSONObject(first).getDouble("modelLoadMs"), 0.0)
        assertEquals(1, JSONObject(first).getInt("modelLoads"))
        assertEquals(0.0, JSONObject(second).getDouble("modelLoadMs"), 0.0)
        assertEquals(0, JSONObject(second).getInt("modelLoads"))
    }

    @Test
    fun `changing model key stops old worker before starting replacement`() = withSession { root, session ->
        val first = fakeProcess(
            responseFrame(kind = ManyueLegacyProtocol.READY, requestId = 0),
            responseFrame(kind = ManyueLegacyProtocol.RESULT, requestId = 1, outputBytes = 4),
        )
        val second = fakeProcess(
            responseFrame(kind = ManyueLegacyProtocol.READY, requestId = 0),
            responseFrame(kind = ManyueLegacyProtocol.RESULT, requestId = 2, outputBytes = 4),
        )
        var starts = 0

        upscale(session, root, "model-A", {
            starts++
            first
        }, "before-key-change.webp")
        upscale(session, root, "model-B", {
            starts++
            second
        }, "after-key-change.webp")

        assertEquals(2, starts)
        assertFalse(first.isAlive)
        assertTrue(first.destroyCalls > 0)
        assertTrue(second.isAlive)
        assertEquals(4L, File(root, "after-key-change.out").length())
    }

    @Test
    fun `dead worker is replaced for the next request`() = withSession { root, session ->
        val first = fakeProcess(
            responseFrame(kind = ManyueLegacyProtocol.READY, requestId = 0),
            responseFrame(kind = ManyueLegacyProtocol.RESULT, requestId = 1, outputBytes = 4),
        )
        val replacement = fakeProcess(
            responseFrame(kind = ManyueLegacyProtocol.READY, requestId = 0),
            responseFrame(kind = ManyueLegacyProtocol.RESULT, requestId = 2, outputBytes = 4),
        )
        var starts = 0

        upscale(session, root, "model-A", {
            starts++
            first
        }, "before-exit.webp")
        first.destroy()
        upscale(session, root, "model-A", {
            starts++
            replacement
        }, "after-exit.webp")

        assertEquals(2, starts)
        assertFalse(first.isAlive)
        assertTrue(replacement.isAlive)
        assertEquals(4L, File(root, "after-exit.out").length())
    }

    @Test
    fun `invalid startup response closes worker and removes partial output`() = withSession { root, session ->
        val output = File(root, "startup-partial.out")
        val process = fakeProcess(responseFrame(kind = ManyueLegacyProtocol.RESULT, requestId = 0))

        val error = assertThrows(IllegalStateException::class.java) {
            upscale(session, root, "model-A", {
                output.writeBytes(byteArrayOf(1, 2, 3))
                process
            }, "startup-partial.webp")
        }

        assertTrue(error.message.orEmpty().contains("failed to initialize"))
        assertFalse(process.isAlive)
        assertFalse(output.exists())
    }

    @Test
    fun `worker is not started when a stale output cannot be removed`() = withSession { root, session ->
        val input = File(root, "stale-check.input").apply { writeBytes(byteArrayOf(8, 9)) }
        val output = File(root, "stale-check.out").apply { assertTrue(mkdir()) }
        val sentinel = File(output, "keep-existing-child").apply { writeBytes(byteArrayOf(1)) }
        var starts = 0

        val error = assertThrows(IllegalStateException::class.java) {
            session.upscale(
                key = "model-A",
                startProcess = {
                    starts++
                    fakeProcess()
                },
                input = input,
                output = output,
                targetWidth = TARGET_WIDTH,
                logFile = File(root, "native.log"),
                isCancelled = { false },
                onProcessStarted = {},
            )
        }

        assertTrue(error.message.orEmpty().contains("Unable to remove a stale native output"))
        assertEquals(0, starts)
        assertTrue(sentinel.exists())
    }

    @Test
    fun `input output path collision preserves original image`() = withSession { root, session ->
        val originalBytes = byteArrayOf(12, 34, 56, 78)
        val input = File(root, "same-path.webp").apply { writeBytes(originalBytes) }
        var starts = 0

        assertThrows(IllegalArgumentException::class.java) {
            session.upscale(
                key = "model-A",
                startProcess = {
                    starts++
                    fakeProcess()
                },
                input = input,
                output = input,
                targetWidth = TARGET_WIDTH,
                logFile = File(root, "native.log"),
                isCancelled = { false },
                onProcessStarted = {},
            )
        }

        assertEquals(0, starts)
        assertTrue("input must survive a rejected output-path collision", input.isFile)
        assertArrayEquals(originalBytes, input.readBytes())
    }

    @Test
    fun `mismatched result id closes worker and removes generated output`() = withSession { root, session ->
        val output = File(root, "wrong-id.out")
        val process = fakeProcess(
            responseFrame(kind = ManyueLegacyProtocol.READY, requestId = 0),
            responseFrame(kind = ManyueLegacyProtocol.RESULT, requestId = 17, outputBytes = 4),
        )

        assertThrows(IllegalStateException::class.java) {
            upscale(session, root, "model-A", { process }, "wrong-id.webp")
        }

        assertEquals(1, process.requestsSeen)
        assertFalse(process.isAlive)
        assertFalse(output.exists())
    }

    @Test
    fun `cancellation before start does not create a worker`() = withSession { root, session ->
        var starts = 0

        assertThrows(ManyueAiRuntime.CancelledException::class.java) {
            upscale(
                session,
                root,
                "model-A",
                {
                    starts++
                    fakeProcess()
                },
                "cancel-before-start.webp",
                isCancelled = { true },
            )
        }

        assertEquals(0, starts)
    }

    @Test
    fun `cancellation while response is blocked closes worker and removes output`() = withSession { root, session ->
        val blocked = CloseAwareBlockingInputStream()
        val input = java.io.SequenceInputStream(
            ByteArrayInputStream(responseFrame(kind = ManyueLegacyProtocol.READY, requestId = 0)),
            blocked,
        )
        val process = FakeProcess(input, ::writeOutputForRequest)
        var cancellationChecks = 0
        val output = File(root, "cancel-during-read.out")

        assertThrows(ManyueAiRuntime.CancelledException::class.java) {
            upscale(
                session,
                root,
                "model-A",
                { process },
                "cancel-during-read.webp",
                isCancelled = { ++cancellationChecks >= 3 },
            )
        }

        assertEquals(1, process.requestsSeen)
        assertFalse(process.isAlive)
        assertFalse(output.exists())
        assertTrue("blocked response stream should be closed", blocked.closed.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `process-start callback failure shuts down worker and removes partial output`() = withSession { root, session ->
        val output = File(root, "callback-partial.out")
        val process = fakeProcess()

        val error = assertThrows(IllegalArgumentException::class.java) {
            upscale(
                session,
                root,
                "model-A",
                {
                    output.writeBytes(byteArrayOf(5, 6, 7))
                    process
                },
                "callback-partial.webp",
                onProcessStarted = { throw IllegalArgumentException("observer failed") },
            )
        }

        assertEquals("observer failed", error.message)
        assertFalse(process.isAlive)
        assertFalse(output.exists())
    }

    @Test
    fun `log write failure does not prevent worker shutdown or output cleanup`() = withSession { root, session ->
        val output = File(root, "log-failure-partial.out")
        val logDirectory = File(root, "log-is-a-directory").apply { assertTrue(mkdir()) }
        val process = fakeProcess(
            responseFrame(kind = ManyueLegacyProtocol.READY, requestId = 0),
            responseFrame(kind = ManyueLegacyProtocol.RESULT, requestId = 23, outputBytes = 4),
        )

        val error = assertThrows(IllegalStateException::class.java) {
            upscale(
                session,
                root,
                "model-A",
                { process },
                "log-failure-partial.webp",
                logFile = logDirectory,
            )
        }

        assertTrue(error.message.orEmpty().contains("request failed"))
        assertEquals(1, process.requestsSeen)
        assertFalse(process.isAlive)
        assertFalse(output.exists())
    }

    @Test
    fun `release when not retaining reading stops an otherwise healthy worker`() = withSession { root, session ->
        val process = fakeProcess(
            responseFrame(kind = ManyueLegacyProtocol.READY, requestId = 0),
            responseFrame(kind = ManyueLegacyProtocol.RESULT, requestId = 1, outputBytes = 4),
        )
        upscale(session, root, "model-A", { process }, "before-release.webp")
        assertTrue(process.isAlive)

        session.releaseWhenIdle(retainForReading = false)

        assertFalse(process.isAlive)
        assertTrue(process.destroyCalls > 0)
    }

    private fun upscale(
        session: ManyueLegacySession,
        root: File,
        key: String,
        startProcess: () -> Process,
        name: String,
        isCancelled: () -> Boolean = { false },
        onProcessStarted: (Process) -> Unit = {},
        logFile: File = File(root, "native.log"),
    ): String {
        val input = File(root, "$name.input").apply { writeBytes(byteArrayOf(9)) }
        return session.upscale(
            key = key,
            startProcess = startProcess,
            input = input,
            output = File(root, name.removeSuffix(".webp") + ".out"),
            targetWidth = TARGET_WIDTH,
            logFile = logFile,
            isCancelled = isCancelled,
            onProcessStarted = onProcessStarted,
        )
    }

    private fun fakeProcess(vararg frames: ByteArray): FakeProcess = FakeProcess(
        ByteArrayInputStream(concat(*frames)),
        ::writeOutputForRequest,
    )

    private fun writeOutputForRequest(frame: ByteArray) {
        val data = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        require(frame.copyOfRange(0, 4).contentEquals("MNYQ".toByteArray(Charsets.US_ASCII)))
        require(data.getInt(4) == frame.size - 8)
        val inputLength = data.getInt(20)
        val outputLength = data.getInt(24)
        val outputPath = String(frame, 28 + inputLength, outputLength, Charsets.UTF_8)
        File(outputPath).writeBytes(byteArrayOf(1, 2, 3, 4))
    }

    private fun responseFrame(
        kind: Int,
        requestId: Long,
        status: Int = 0,
        initUs: Long = 0,
        decodeUs: Long = 1_000,
        inferUs: Long = 2_000,
        resizeUs: Long = 3_000,
        encodeUs: Long = 4_000,
        totalUs: Long = 10_000,
        outputBytes: Long = 0,
        width: Int = TARGET_WIDTH,
        height: Int = TARGET_WIDTH * 2,
        backend: Int = 1,
        tile: Int = 384,
        modelLoads: Int = if (kind == ManyueLegacyProtocol.READY) 1 else 0,
    ): ByteArray = ByteBuffer.allocate(8 + RESPONSE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("MNYR".toByteArray(Charsets.US_ASCII))
        .putInt(RESPONSE_BYTES)
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

    private fun concat(vararg frames: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        frames.forEach { write(it) }
    }.toByteArray()

    private inline fun withSession(block: (File, ManyueLegacySession) -> Unit) {
        val root = Files.createTempDirectory("manyue-legacy-session-test").toFile()
        val session = ManyueLegacySession()
        try {
            block(root, session)
        } finally {
            session.close()
            root.deleteRecursively()
        }
    }

    private class FakeProcess(
        private val responseInput: InputStream,
        private val onRequest: (ByteArray) -> Unit,
    ) : Process() {
        @Volatile
        private var alive = true
        var destroyCalls = 0
            private set
        var requestsSeen = 0
            private set
        private val requestBytes = ByteArrayOutputStream()
        private val requestOutput = object : OutputStream() {
            override fun write(value: Int) {
                requestBytes.write(value)
            }

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                requestBytes.write(bytes, offset, length)
            }

            override fun flush() {
                val frame = requestBytes.toByteArray()
                if (frame.isNotEmpty()) {
                    requestBytes.reset()
                    requestsSeen++
                    onRequest(frame)
                }
            }
        }

        override fun getOutputStream(): OutputStream = requestOutput
        override fun getInputStream(): InputStream = responseInput
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            if (alive) throw IllegalThreadStateException("fake worker is still alive")
            return 0
        }

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive

        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("fake worker is still alive")
            return 0
        }

        override fun destroy() {
            destroyCalls++
            alive = false
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }

        override fun isAlive(): Boolean = alive
    }

    private class CloseAwareBlockingInputStream : InputStream() {
        val closed = CountDownLatch(1)

        override fun read(): Int {
            closed.await()
            return -1
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            closed.await()
            return -1
        }

        override fun close() {
            closed.countDown()
        }
    }

    private companion object {
        const val TARGET_WIDTH = 2400
        const val RESPONSE_BYTES = 92
    }
}
