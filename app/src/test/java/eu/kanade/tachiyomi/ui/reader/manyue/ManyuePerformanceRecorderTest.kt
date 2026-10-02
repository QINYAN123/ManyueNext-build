package eu.kanade.tachiyomi.ui.reader.manyue

import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceRecorder.Metric
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceRecorder.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManyuePerformanceRecorderTest {
    @Test fun disabledDoesNotCollectAndStopFreezesReport() {
        var now = 10L
        val recorder = ManyuePerformanceRecorder(clock = { now })
        recorder.event(Stage.QUEUED)
        assertTrue(recorder.snapshot().events.isEmpty())
        recorder.setEnabled(true)
        now = 30
        recorder.event(Stage.AI_READY, 4, 7, mapOf(Metric.NATIVE_MS to 50L))
        recorder.setEnabled(false)
        val stopped = recorder.snapshot()
        now = 1000
        recorder.event(Stage.AI_FAILED)
        assertEquals(stopped, recorder.snapshot())
        assertEquals(20L, stopped.durationNs)
        assertFalse(stopped.enabled)
    }

    @Test fun boundedBuffersReportEvictionsAndCallbackDropsSeparately() {
        val recorder = ManyuePerformanceRecorder(maxEvents = 2, maxFrames = 2)
        recorder.setEnabled(true)
        val session = recorder.snapshot().session
        repeat(4) { recorder.event(Stage.QUEUED, it) }
        repeat(3) { recorder.frame(session, 10, 20, -1, false, true, 2) }
        val snapshot = recorder.snapshot()
        assertEquals(listOf(2, 3), snapshot.events.map { it.page })
        assertEquals(3L, snapshot.overwrittenEvents)
        assertEquals(2, snapshot.frames.size)
        assertEquals(1L, snapshot.overwrittenFrames)
        assertEquals(6L, snapshot.callbackDrops)
    }

    @Test fun resettingRejectsLateFrameCallbacksFromThePreviousSession() {
        val recorder = ManyuePerformanceRecorder()
        recorder.setEnabled(true)
        val previous = recorder.snapshot().session
        recorder.clear()
        recorder.frame(previous, 10, 20, -1, false, true, 9)
        assertTrue(recorder.snapshot().frames.isEmpty())
        assertEquals(0L, recorder.snapshot().callbackDrops)
        recorder.frame(recorder.snapshot().session, 10, 20, 5, false, true, 0)
        assertEquals(1, recorder.snapshot().frames.size)
        recorder.setEnabled(false)
        recorder.setEnabled(true)
        assertTrue(recorder.snapshot().frames.isEmpty())
    }

    @Test fun callerCannotMutateStoredMetrics() {
        val recorder = ManyuePerformanceRecorder()
        recorder.setEnabled(true)
        val values = mutableMapOf(Metric.NATIVE_MS to 4L)
        recorder.event(Stage.AI_READY, metrics = values)
        values[Metric.NATIVE_MS] = 900
        assertEquals(4L, recorder.snapshot().events.last().metrics[Metric.NATIVE_MS])
    }

    @Test fun concurrentFramesAndJobsStayWithinCapacity() {
        val recorder = ManyuePerformanceRecorder(maxEvents = 32, maxFrames = 16)
        recorder.setEnabled(true)
        val session = recorder.snapshot().session
        val workers = (0..3).map {
            Thread {
                repeat(300) {
                    recorder.event(Stage.STARTED, it)
                    recorder.frame(session, 20, 10, -1, false, true, 0)
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }
        val snapshot = recorder.snapshot()
        assertEquals(32, snapshot.events.size)
        assertEquals(16, snapshot.frames.size)
        assertEquals(1201L - 32, snapshot.overwrittenEvents)
        assertEquals(1200L - 16, snapshot.overwrittenFrames)
    }
}
