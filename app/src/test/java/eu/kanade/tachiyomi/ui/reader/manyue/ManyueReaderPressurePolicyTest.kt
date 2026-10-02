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
        assertTrue(policy.isDisplayBlocked(), "fast movement must still defer ready-image commits")

        clock.advance(FAST_SCROLL_HOLD_NANOS - 1L)
        assertTrue(policy.isBlocked())
        assertTrue(policy.isDisplayBlocked())
        clock.advance(1L)
        assertFalse(policy.isBlocked(), "negative monotonic clock origins must not look like an active deadline")
        assertFalse(policy.isDisplayBlocked())
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
        assertTrue(policy.isDisplayBlocked(), "sustained missed frames must defer ready-image commits")

        policy.reportFrame(reader, expected120HzNanos, expected120HzNanos)
        policy.reportFrame(reader, expected120HzNanos, expected120HzNanos)
        assertFalse(policy.isBlocked(), "two healthy frames clear a recovered scroll session")
        assertFalse(policy.isDisplayBlocked())
    }

    @Test
    fun `stable 60fps cadence on a 120Hz display is learned and releases the Lite display gate`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        val expected120HzNanos = 8_333_333L
        val actual60FpsNanos = 16_666_667L

        policy.update(reader, active = true)
        repeat(8) {
            clock.advance(actual60FpsNanos)
            policy.reportFrame(reader, actual60FpsNanos, expected120HzNanos)
        }

        assertFalse(policy.isLiteDisplayBlocked(), "stable 2x-vsync app cadence must not strand ready Lite results")
        assertFalse(policy.isLiteVisibleWorkBlocked())
    }

    @Test
    fun `irregular slow frames do not teach a cadence and sustained misses still gate`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        val expected120HzNanos = 8_333_333L
        val irregularIntervals = longArrayOf(
            16_666_666L,
            25_000_000L,
            8_333_333L,
            33_333_332L,
            25_000_000L,
            8_333_333L,
            16_666_666L,
            8_333_333L,
        )

        policy.update(reader, active = true)
        irregularIntervals.forEach { interval ->
            clock.advance(interval)
            policy.reportFrame(reader, interval, expected120HzNanos)
        }
        assertFalse(policy.isLiteDisplayBlocked(), "nonuniform multiples must not be mistaken for an app frame cap")

        repeat(3) {
            clock.advance(25_000_000L)
            policy.reportFrame(reader, 25_000_000L, expected120HzNanos)
        }
        assertTrue(policy.isLiteDisplayBlocked(), "three consecutive unlearned misses must retain backpressure")
    }

    @Test
    fun `real burst beyond a learned 60fps cadence still gates the Lite display`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        val expected120HzNanos = 8_333_333L
        val stable60FpsNanos = 16_666_667L

        policy.update(reader, active = true)
        repeat(8) {
            clock.advance(stable60FpsNanos)
            policy.reportFrame(reader, stable60FpsNanos, expected120HzNanos)
        }
        assertFalse(policy.isLiteDisplayBlocked())

        repeat(3) {
            clock.advance(40_000_000L)
            policy.reportFrame(reader, 40_000_000L, expected120HzNanos)
        }
        assertTrue(policy.isLiteDisplayBlocked(), "a sustained 40ms stall exceeds the learned 16.7ms cadence")
    }

    @Test
    fun `display refresh change resets cadence and relearns the new stable rate`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        val expected120HzNanos = 8_333_333L
        val actual60FpsNanos = 16_666_667L
        val expected60HzNanos = 16_666_667L
        val actual30FpsNanos = 33_333_333L

        policy.update(reader, active = true)
        repeat(8) {
            clock.advance(actual60FpsNanos)
            policy.reportFrame(reader, actual60FpsNanos, expected120HzNanos)
        }
        assertFalse(policy.isLiteDisplayBlocked())

        repeat(8) {
            clock.advance(actual30FpsNanos)
            policy.reportFrame(reader, actual30FpsNanos, expected60HzNanos)
        }
        assertFalse(policy.isLiteDisplayBlocked(), "the 120-to-60Hz switch must reset and relearn the 2x cadence")
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
        assertFalse(policy.isDisplayBlocked(), "thermal protection must not indefinitely hide a ready frame")
        policy.reportThermalStatus(currentReader, 2)
        assertFalse(policy.isBlocked())
        assertFalse(policy.isDisplayBlocked())
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
        fun advance(nanos: Long) {
            currentNanos += nanos
        }
        fun moveTo(nanos: Long) {
            currentNanos = nanos
        }
    }

    private companion object {
        const val FAST_SCROLL_HOLD_NANOS = 250_000_000L
    }
}
