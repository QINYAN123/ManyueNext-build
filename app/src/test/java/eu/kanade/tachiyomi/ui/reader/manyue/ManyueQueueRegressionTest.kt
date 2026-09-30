package eu.kanade.tachiyomi.ui.reader.manyue

import java.nio.file.Files
import java.util.concurrent.PriorityBlockingQueue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManyueQueueRegressionTest {
    @Test fun `concurrent enqueue is unique and selecting prefetched page promotes it`() {
        val scheduler = ManyueAiUpscaler
        val started = scheduler.javaClass.getDeclaredField("started").apply { isAccessible = true }
        val queueField = scheduler.javaClass.getDeclaredField("queue").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val queue = queueField.get(scheduler) as PriorityBlockingQueue<ManyueAiUpscaler.Request>
        val wasStarted = started.getBoolean(scheduler)
        // Exercise the real queue without launching Android native processes in JVM tests.
        started.setBoolean(scheduler, true)
        val tokens = mutableListOf<String>()
        try {
            fun register(index: Int, priority: Int): String {
                val input = Files.createTempFile("manyue-queue-", ".bin").toFile()
                val token = scheduler.register(
                    91, 92, index, input, 800, 1000, 2006, 2344, priority,
                    expectedMode = 2, generation = 991, sourceFingerprint = "queue-$index",
                )
                tokens.add(token)
                return token
            }
            val next = register(1, 50)
            val visible = register(5, 10)
            scheduler.queue(next)
            val threads = List(8) { Thread { scheduler.queue(visible) }.apply { start() } }
            threads.forEach { it.join() }
            assertEquals(1, queue.count { it.token == visible })
            scheduler.promotePriority(visible, 100)
            assertEquals(visible, queue.peek().token)
            scheduler.promotePriority(visible, 10)
            assertEquals(visible, queue.peek().token)
            scheduler.updatePriority(visible, 10)
            assertEquals(next, queue.peek().token)
        } finally {
            tokens.forEach(scheduler::cancel)
            started.setBoolean(scheduler, wasStarted)
        }
    }
}
