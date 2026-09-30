package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueReaderPressurePolicyTest {

    @Test
    fun `touch and repeated slow movement leave AI work available`() {
        val clock = FakeClock(-5_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()

        policy.update(reader, active = true)
        repeat(20) {
            clock.advance(16_000_000L)
            policy.reportScroll(reader, dx = 4, dy = 0, viewportHeightPx = 400)
        }

        assertFalse(policy.isBlocked())
    }

    @Test
    fun `fast scroll at high refresh rates blocks briefly and then recovers`() {
        val clock = FakeClock(-5_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()

        policy.update(reader, active = true)
        repeat(5) {
            clock.advance(6_944_444L) // 144Hz frames
            policy.reportScroll(reader, dx = 10, dy = 0, viewportHeightPx = 400)
        }
        assertTrue(policy.isBlocked(), "fast samples should accumulate across sub-8ms frame intervals")

        clock.advance(FAST_SCROLL_HOLD_NANOS - 1L)
        assertTrue(policy.isBlocked())
        clock.advance(1L)
        assertFalse(policy.isBlocked(), "negative monotonic clock origins must not look like an active deadline")
    }

    @Test
    fun `isolated long frame does not gate but sustained missed intervals do`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        val expected120HzNanos = 8_333_333L

        policy.update(reader, active = true)
        policy.reportFrame(reader, 20_000_000L, expected120HzNanos)
        policy.reportFrame(reader, expected120HzNanos, expected120HzNanos)
        policy.reportFrame(reader, 20_000_000L, expected120HzNanos)
        assertFalse(policy.isBlocked(), "one missed frame must not start a pressure hold")

        policy.reportFrame(reader, 20_000_000L, expected120HzNanos)
        policy.reportFrame(reader, 20_000_000L, expected120HzNanos)
        policy.reportFrame(reader, 20_000_000L, expected120HzNanos)
        assertTrue(policy.isBlocked())

        policy.reportFrame(reader, expected120HzNanos, expected120HzNanos)
        policy.reportFrame(reader, expected120HzNanos, expected120HzNanos)
        assertFalse(policy.isBlocked(), "two healthy frames clear a recovered scroll session")
    }

    @Test
    fun `pausing frame sampling breaks a bad frame streak`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        policy.update(reader, active = true)

        repeat(2) { policy.reportFrame(reader, 30_000_000L, 16_666_667L) }
        policy.resetFrameSampling(reader)
        policy.reportFrame(reader, 30_000_000L, 16_666_667L)
        assertFalse(policy.isBlocked())

        repeat(2) { policy.reportFrame(reader, 30_000_000L, 16_666_667L) }
        assertTrue(policy.isBlocked())
    }

    @Test
    fun `severe thermal status gates only its current attached owner`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val detachedReader = Any()
        val currentReader = Any()

        policy.update(detachedReader, active = false)
        policy.reportThermalStatus(detachedReader, ManyueReaderWorkGate.THERMAL_STATUS_SEVERE)
        assertTrue(policy.isBlocked())

        policy.release(detachedReader)
        assertFalse(policy.isBlocked(), "detach releases a latched thermal report")
        policy.reportThermalStatus(detachedReader, ManyueReaderWorkGate.THERMAL_STATUS_SEVERE)
        assertFalse(policy.isBlocked(), "late thermal callbacks after detach are ignored")

        policy.update(currentReader, active = false)
        policy.reportThermalStatus(detachedReader, ManyueReaderWorkGate.THERMAL_STATUS_SEVERE)
        assertFalse(policy.isBlocked(), "a stale non-owner cannot take over the gate")
        // Android reports SEVERE as 3; 4 is already CRITICAL.
        policy.reportThermalStatus(currentReader, 3)
        assertTrue(policy.isBlocked())
        policy.reportThermalStatus(currentReader, 2)
        assertFalse(policy.isBlocked())
    }

    @Test
    fun `nonmonotonic scroll timestamp is discarded`() {
        val clock = FakeClock(-5_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        policy.update(reader, active = true)

        policy.reportScroll(reader, dx = 0, dy = 0, viewportHeightPx = 400)
        clock.advance(16_000_000L)
        policy.reportScroll(reader, dx = 10_000, dy = 0, viewportHeightPx = 400)
        assertTrue(policy.isBlocked(), "a very fast measured jump is reported")

        policy.release(reader)
        policy.update(reader, active = true)
        policy.reportScroll(reader, dx = 0, dy = 0, viewportHeightPx = 400)
        clock.moveTo(-6_000_000_000L)
        policy.reportScroll(reader, dx = 10_000, dy = 0, viewportHeightPx = 400)
        assertFalse(policy.isBlocked(), "clock regression must not be treated as an infinite speed")
    }

    private class FakeClock(private var currentNanos: Long) {
        fun now(): Long = currentNanos
        fun advance(nanos: Long) { currentNanos += nanos }
        fun moveTo(nanos: Long) { currentNanos = nanos }
    }

    private companion object {
        const val FAST_SCROLL_HOLD_NANOS = 250_000_000L
    }
}
