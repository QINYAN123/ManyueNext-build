package eu.kanade.tachiyomi.ui.reader.manyue

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/** Copies the reused FrameMetrics on a dedicated callback thread; no per-frame I/O or Compose updates. */
class ManyueFrameMonitor(private val window: Window, private val isReading: () -> Boolean) : AutoCloseable {
    private val thread = HandlerThread("ManyueFrameMetrics").apply { start() }
    private val session = ManyuePerformanceDiagnostics.recorder.snapshot().session

    @Volatile private var closed = false
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, drops ->
        if (!closed) {
            ManyuePerformanceDiagnostics.recorder.frame(
                session,
                metrics.getMetric(FrameMetrics.TOTAL_DURATION),
                if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else -1,
                if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.GPU_DURATION) else -1,
                metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L,
                isReading(),
                drops,
            )
        }
    }

    init {
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    override fun close() {
        if (closed) return
        closed = true
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }
}
