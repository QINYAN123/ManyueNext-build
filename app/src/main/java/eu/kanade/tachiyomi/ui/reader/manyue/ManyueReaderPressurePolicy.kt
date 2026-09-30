package eu.kanade.tachiyomi.ui.reader.manyue

import kotlin.math.hypot

/** Pure, clock-injectable policy behind [ManyueReaderWorkGate]. */
internal class ManyueReaderPressurePolicy(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var owner: Any? = null
    private var thermalPressure = false
    private var fastScrollUntilNanos: Long? = null
    private var badFrameUntilNanos: Long? = null
    private var lastScrollAtNanos: Long? = null
    private var scrollWindowStartNanos: Long? = null
    private var scrollWindowDistancePx = 0.0
    private var fastScrollSamples = 0
    private var badFrameSamples = 0
    private var goodFrameSamples = 0

    @Synchronized
    fun update(reader: Any, active: Boolean) {
        if (owner !== reader) {
            clearSignals()
            owner = reader
        }
        if (!active) resetScrollSampling()
    }

    @Synchronized
    fun release(reader: Any) {
        if (owner !== reader) return
        owner = null
        clearSignals()
    }

    @Synchronized
    fun reportScroll(reader: Any, dx: Int, dy: Int, viewportHeightPx: Int) {
        if (owner !== reader) return

        val now = nanoTime()
        val previous = lastScrollAtNanos
        if (previous != null && now <= previous) {
            // Reject clock regressions instead of interpreting them as a very fast fling.
            resetScrollSampling()
            return
        }
        lastScrollAtNanos = now

        val distancePx = hypot(dx.toDouble(), dy.toDouble())
        if (viewportHeightPx <= 0 || distancePx <= 0.0 || previous == null) {
            fastScrollSamples = 0
            scrollWindowStartNanos = now
            scrollWindowDistancePx = 0.0
            return
        }

        val elapsedNanos = now - previous
        if (elapsedNanos > MAX_SCROLL_SAMPLE_NANOS) {
            fastScrollSamples = 0
            scrollWindowStartNanos = now
            scrollWindowDistancePx = 0.0
            return
        }

        val windowStart = scrollWindowStartNanos ?: previous
        scrollWindowDistancePx += distancePx
        val windowElapsedNanos = now - windowStart
        if (windowElapsedNanos < MIN_SCROLL_SAMPLE_NANOS) return

        val viewportsPerSecond = scrollWindowDistancePx * NANOS_PER_SECOND /
            (viewportHeightPx.toDouble() * windowElapsedNanos.toDouble())
        scrollWindowStartNanos = now
        scrollWindowDistancePx = 0.0
        if (viewportsPerSecond >= FAST_SCROLL_VIEWPORTS_PER_SECOND) {
            fastScrollSamples++
            // Two consecutive fast samples avoid gating on a single layout jump. A very fast
            // measured sample can gate immediately so a real fling gets prompt headroom.
            if (fastScrollSamples >= FAST_SCROLL_REQUIRED_SAMPLES ||
                viewportsPerSecond >= IMMEDIATE_FAST_SCROLL_VIEWPORTS_PER_SECOND
            ) {
                fastScrollUntilNanos = now + FAST_SCROLL_HOLD_NANOS
            }
        } else {
            fastScrollSamples = 0
        }
    }

    @Synchronized
    fun reportFrame(reader: Any, frameIntervalNanos: Long, expectedIntervalNanos: Long) {
        if (owner !== reader) return

        if (frameIntervalNanos <= 0L || expectedIntervalNanos <= 0L) {
            badFrameSamples = 0
            goodFrameSamples = 0
            return
        }

        val now = nanoTime()
        val isBadFrame = frameIntervalNanos.toDouble() >
            expectedIntervalNanos.toDouble() * BAD_FRAME_MULTIPLIER
        if (isBadFrame) {
            badFrameSamples++
            goodFrameSamples = 0
            if (badFrameSamples >= BAD_FRAME_REQUIRED_SAMPLES) {
                badFrameUntilNanos = now + FRAME_PRESSURE_HOLD_NANOS
            }
        } else {
            badFrameSamples = 0
            goodFrameSamples++
            if (goodFrameSamples >= GOOD_FRAME_SAMPLES_TO_CLEAR) {
                badFrameUntilNanos = null
            }
        }
    }

    /** Starts a fresh frame streak after the view becomes idle or detached from sampling. */
    @Synchronized
    fun resetFrameSampling(reader: Any) {
        if (owner !== reader) return
        badFrameSamples = 0
        goodFrameSamples = 0
    }

    @Synchronized
    fun reportThermalStatus(reader: Any, status: Int) {
        if (owner !== reader) return
        thermalPressure = status >= THERMAL_STATUS_SEVERE
    }

    @Synchronized
    fun isBlocked(): Boolean {
        val now = nanoTime()
        return thermalPressure || isBefore(now, fastScrollUntilNanos) || isBefore(now, badFrameUntilNanos)
    }

    private fun resetScrollSampling() {
        lastScrollAtNanos = null
        scrollWindowStartNanos = null
        scrollWindowDistancePx = 0.0
        fastScrollSamples = 0
    }

    private fun clearSignals() {
        thermalPressure = false
        fastScrollUntilNanos = null
        badFrameUntilNanos = null
        resetScrollSampling()
        badFrameSamples = 0
        goodFrameSamples = 0
    }

    private fun isBefore(now: Long, deadline: Long?): Boolean = deadline?.let { now - it < 0L } == true

    companion object {
        const val THERMAL_STATUS_SEVERE = 3
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MIN_SCROLL_SAMPLE_NANOS = 8_000_000L
        const val MAX_SCROLL_SAMPLE_NANOS = 250_000_000L
        const val FAST_SCROLL_VIEWPORTS_PER_SECOND = 1.5
        const val IMMEDIATE_FAST_SCROLL_VIEWPORTS_PER_SECOND = 4.0
        const val FAST_SCROLL_REQUIRED_SAMPLES = 2
        const val FAST_SCROLL_HOLD_NANOS = 250_000_000L
        const val BAD_FRAME_MULTIPLIER = 1.75
        const val BAD_FRAME_REQUIRED_SAMPLES = 3
        const val GOOD_FRAME_SAMPLES_TO_CLEAR = 2
        const val FRAME_PRESSURE_HOLD_NANOS = 350_000_000L
    }
}
