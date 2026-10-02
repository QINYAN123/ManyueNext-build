package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ManyueLiteSessionTest {
    private class Backend : ManyueLiteBackend {
        val creates = mutableListOf<String>()
        val jobs = mutableListOf<Long>()
        val cancels = mutableListOf<Long>()
        val destroys = mutableListOf<Long>()
        var execute: () -> Unit = {}
        override fun create(modelDirectory: String): Long = synchronized(this) {
            creates += modelDirectory
            creates.size.toLong()
        }
        override fun upscale(
            handle: Long,
            jobId: Long,
            inputPath: String,
            outputPath: String,
            targetWidth: Int,
            strength: Int,
        ): String {
            synchronized(this) { jobs += jobId }
            execute()
            return "ok"
        }
        override fun cancel(handle: Long, jobId: Long) {
            synchronized(this) { cancels += jobId }
        }
        override fun destroy(handle: Long) {
            synchronized(this) { destroys += handle }
        }
    }

    private fun run(
        session: ManyueLiteSession,
        directory: String = "models",
        cancelled: () -> Boolean = {
            false
        },
        ready: (() -> Unit) -> Unit = {},
    ) =
        session.upscale(File(directory), File("input"), File("output"), 1035, 60, cancelled, ready)

    @Test fun `successive pages reuse the model and changing its directory retires the old engine`() {
        val backend = Backend()
        val session = ManyueLiteSession(backend)
        run(session)
        run(session)
        assertEquals(1, backend.creates.size)
        assertEquals(listOf(1L, 2L), backend.jobs)
        assertTrue(backend.destroys.isEmpty())
        run(session, "different-model")
        assertEquals(2, backend.creates.size)
        assertEquals(listOf(1L), backend.destroys)
        session.close()
        session.close()
        assertEquals(listOf(1L, 2L), backend.destroys)
    }

    @Test fun `cancellation registered before native entry prevents work and later jobs have a different id`() {
        val backend = Backend()
        val session = ManyueLiteSession(backend)
        val cancelled = AtomicBoolean()
        assertThrows(ManyueAiRuntime.CancelledException::class.java) {
            run(session, cancelled = cancelled::get, ready = { cancel ->
                cancelled.set(true)
                cancel()
            })
        }
        assertTrue(backend.jobs.isEmpty())
        assertTrue(backend.cancels.all { it == 1L })
        cancelled.set(false)
        run(session)
        assertEquals(listOf(2L), backend.jobs)
        session.close()
    }

    @Test fun `cancel does not wait for inference and closing does wait for the active call`() {
        val backend = Backend()
        val session = ManyueLiteSession(backend)
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val cancelReady = CountDownLatch(1)
        var cancel: (() -> Unit)? = null
        val pool = Executors.newFixedThreadPool(2)
        backend.execute = {
            entered.countDown()
            check(leave.await(2, TimeUnit.SECONDS))
        }
        try {
            val active = pool.submit<Boolean> {
                try {
                    run(session, cancelled = cancelled::get, ready = {
                        cancel = it
                        cancelReady.countDown()
                    })
                    false
                } catch (_: ManyueAiRuntime.CancelledException) {
                    true
                }
            }
            assertTrue(cancelReady.await(1, TimeUnit.SECONDS))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val closed = pool.submit { session.close() }
            cancelled.set(true)
            requireNotNull(cancel).invoke()
            assertEquals(listOf(1L), backend.cancels)
            assertFalse(closed.isDone)
            assertTrue(backend.destroys.isEmpty())
            leave.countDown()
            assertTrue(active.get(1, TimeUnit.SECONDS))
            closed.get(1, TimeUnit.SECONDS)
            assertEquals(listOf(1L), backend.destroys)
        } finally {
            leave.countDown()
            pool.shutdownNow()
            session.close()
        }
    }

    @Test fun `current page wins and equally visible pages retain enqueue order`() {
        fun request(token: String, priority: Int) = ManyueAiUpscaler.Request(
            token, 1, 2, 0, File("input"), 690, 985, 2, 1035, priority = priority,
        )
        val firstVisible = request("first", 100)
        val secondVisible = request("second", 100)
        val current = request("current", 120)
        val queue = java.util.PriorityQueue<ManyueAiUpscaler.Request>()
        queue.addAll(listOf(secondVisible, current, firstVisible))
        assertEquals(listOf("current", "first", "second"), generateSequence { queue.poll() }.map { it.token }.toList())
    }

    @Test fun `visible work can preempt lite prefetch without cancelling another visible page or legacy work`() {
        fun request(
            token: String,
            priority: Int,
            model: ManyueAiModel = ManyueAiModel.MOBILE_LITE,
        ) = ManyueAiUpscaler.Request(
            token, 1, 2, 0, File("input"), 690, 985, 2, 1035, priority = priority, model = model,
        )
        val prefetch = request("prefetch", 40)
        val visible = request("visible", 120)
        assertTrue(ManyueAiUpscaler.shouldYieldToVisible(prefetch, visible))
        assertFalse(ManyueAiUpscaler.shouldYieldToVisible(prefetch, prefetch))
        assertFalse(ManyueAiUpscaler.shouldYieldToVisible(request("other-visible", 100), visible))
        assertFalse(ManyueAiUpscaler.shouldYieldToVisible(prefetch, request("next", 50)))
        assertFalse(
            ManyueAiUpscaler.shouldYieldToVisible(request("legacy", 40, ManyueAiModel.FAST_REAL_CUGAN), visible),
        )
        prefetch.completed = true
        assertFalse(ManyueAiUpscaler.shouldYieldToVisible(prefetch, visible))
    }
}
