package eu.kanade.tachiyomi.ui.reader.manyue

/**
 * Signals reader pressure to background AI and prefetch work.
 *
 * Touch and ordinary scrolling alone are not a reason to stop work. The attached reader reports
 * measured fast movement, sustained missed frame intervals, and thermal status instead.
 */
object ManyueReaderWorkGate {
    const val THERMAL_STATUS_SEVERE = ManyueReaderPressurePolicy.THERMAL_STATUS_SEVERE

    private val policy = ManyueReaderPressurePolicy()

    /** Keeps the current reader owner. [active] resets motion sampling when interaction ends. */
    @Synchronized
    fun update(reader: Any, active: Boolean) {
        policy.update(reader, active)
    }

    @Synchronized
    fun release(reader: Any) {
        policy.release(reader)
    }

    @Synchronized
    fun reportScroll(reader: Any, dx: Int, dy: Int, viewportHeightPx: Int) {
        policy.reportScroll(reader, dx, dy, viewportHeightPx)
    }

    @Synchronized
    fun reportFrame(reader: Any, frameIntervalNanos: Long, expectedIntervalNanos: Long) {
        policy.reportFrame(reader, frameIntervalNanos, expectedIntervalNanos)
    }

    @Synchronized
    fun resetFrameSampling(reader: Any) {
        policy.resetFrameSampling(reader)
    }

    @Synchronized
    fun reportThermalStatus(reader: Any, status: Int) {
        policy.reportThermalStatus(reader, status)
    }

    @Synchronized
    fun isBlocked(): Boolean = policy.isBlocked()

    /** Keeps thermal protection for expensive work without indefinitely hiding a ready image. */
    @Synchronized
    fun isDisplayBlocked(): Boolean = policy.isDisplayBlocked()
}

/** Spaces native jobs only when the caller reports active reader pressure. */
internal class ManyueWorkPacer(private val nanoTime: () -> Long = System::nanoTime) {
    @Volatile private var readyAtNanos: Long? = null

    fun isBlocked(): Boolean = readyAtNanos?.let { nanoTime() - it < 0L } ?: false

    /** Normal reading continues immediately; pressure adds only a short, bounded gap. */
    fun afterWork(startedAtNanos: Long, pressureActive: Boolean = false) {
        val now = nanoTime()
        if (!pressureActive) {
            readyAtNanos = now
            return
        }

        val elapsed = (now - startedAtNanos).coerceAtLeast(0L)
        val rest = (elapsed / 4).coerceIn(MIN_PRESSURE_GAP_NANOS, MAX_PRESSURE_GAP_NANOS)
        readyAtNanos = now + rest
    }

    private companion object {
        const val MIN_PRESSURE_GAP_NANOS = 8_000_000L
        const val MAX_PRESSURE_GAP_NANOS = 100_000_000L
    }
}
