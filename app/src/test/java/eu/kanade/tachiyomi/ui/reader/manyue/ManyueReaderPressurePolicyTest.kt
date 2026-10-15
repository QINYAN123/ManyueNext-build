package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
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
        assertFalse(policy.isDisplayBlocked(), "fast movement alone must not hide a decoded result")

        clock.advance(FAST_SCROLL_HOLD_NANOS - 1L)
        assertTrue(policy.isBlocked())
        assertFalse(policy.isDisplayBlocked())
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

        assertFalse(policy.isDisplayBlocked(), "stable 2x-vsync app cadence must not strand ready Lite results")
        assertFalse(policy.isVisibleWorkBlocked())
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
        assertFalse(policy.isDisplayBlocked(), "nonuniform multiples must not be mistaken for an app frame cap")

        repeat(3) {
            clock.advance(25_000_000L)
            policy.reportFrame(reader, 25_000_000L, expected120HzNanos)
        }
        assertTrue(policy.isDisplayBlocked(), "three consecutive unlearned misses must retain backpressure")
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
        assertFalse(policy.isDisplayBlocked())

        repeat(3) {
            clock.advance(40_000_000L)
            policy.reportFrame(reader, 40_000_000L, expected120HzNanos)
        }
        assertTrue(policy.isDisplayBlocked(), "a sustained 40ms stall exceeds the learned 16.7ms cadence")
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
        assertFalse(policy.isDisplayBlocked())

        repeat(8) {
            clock.advance(actual30FpsNanos)
            policy.reportFrame(reader, actual30FpsNanos, expected60HzNanos)
        }
        assertFalse(policy.isDisplayBlocked(), "the 120-to-60Hz switch must reset and relearn the 2x cadence")
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
        assertFalse(policy.isBlocked(), "moderate heat alone must not indefinitely hold distant work")
        assertFalse(policy.isVisibleWorkBlocked(), "current and near pages continue at moderate heat")
        assertFalse(policy.isDisplayBlocked())
    }

    @Test
    fun `moderate thermal restriction is priority aware and leaves display available`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        policy.update(reader, active = false)
        policy.reportThermalStatus(reader, 2)
        assertFalse(policy.isBlocked(), "the first job may start without waiting for thermal recovery")
        assertTrue(policy.isAiWorkBlocked(ManyueAiSafetyPolicy.CONTINUOUS_PREFETCH_PRIORITY))
        assertFalse(policy.isAiWorkBlocked(10), "window work may start immediately at MODERATE")
        assertFalse(policy.isAiWorkBlocked(40), "nearest read-ahead may start immediately at MODERATE")
        assertFalse(policy.isAiWorkBlocked(50), "N+1 remains on the existing nearest-work path")
        assertFalse(policy.isAiWorkBlocked(100), "visible work remains available at MODERATE")
        policy.afterAiWork(2_000_000_000L)
        assertTrue(policy.isBlocked())
        assertTrue(policy.isAiWorkBlocked(1), "far continuous prefetch remains held for the full warm period")
        assertTrue(policy.isAiWorkBlocked(10), "window work retains the existing finite thermal interval")
        assertTrue(policy.isAiWorkBlocked(40))
        assertFalse(policy.isVisibleWorkBlocked())
        assertFalse(policy.isAiWorkBlocked(50))
        assertFalse(policy.isAiWorkBlocked(100))
        assertFalse(policy.isDisplayBlocked())
        assertEquals(ManyueReaderPressurePolicy.BLOCK_REASON_THERMAL, policy.aiWorkBlockReasonMask(1))
        assertEquals(0, policy.aiWorkBlockReasonMask(50))
        assertEquals(0, policy.aiWorkBlockReasonMask(100))
        clock.advance(500_000_000L)
        assertFalse(policy.isBlocked())
        assertTrue(policy.isAiWorkBlocked(1), "the distant-work hold lasts until thermal status falls below MODERATE")
        assertFalse(policy.isAiWorkBlocked(10), "window work resumes when its finite interval expires")
        assertFalse(policy.isAiWorkBlocked(40))
        assertEquals(ManyueReaderPressurePolicy.BLOCK_REASON_THERMAL, policy.aiWorkBlockReasonMask(1))
        assertEquals(0, policy.aiWorkBlockReasonMask(40))
        listOf(1, 10, 40, 50, 100).forEach { priority ->
            assertEquals(
                policy.aiWorkBlockReasonMask(priority) != 0,
                policy.isAiWorkBlocked(priority),
                "diagnostic reason must match the scheduling decision for priority $priority",
            )
        }
        assertFalse(policy.isDisplayBlocked(), "warm thermal policy must not strand a completed image")
    }

    @Test
    fun `reprioritizing far work into the window or cooling releases only the relevant thermal hold`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        policy.update(reader, active = false)
        policy.reportThermalStatus(reader, 2)

        assertTrue(policy.isAiWorkBlocked(1), "future continuous prefetch is held while warm")
        assertFalse(policy.isAiWorkBlocked(40), "queued work can run after being reprioritized into the window")
        policy.afterAiWork(2_000_000_000L)
        assertTrue(policy.isAiWorkBlocked(40), "window work still obeys its existing finite interval")
        clock.advance(500_000_000L)
        assertFalse(policy.isAiWorkBlocked(40), "reprioritized work resumes when the interval expires")
        assertTrue(policy.isAiWorkBlocked(1), "the far-prefetch hold remains active at MODERATE")

        policy.reportThermalStatus(reader, 1)
        assertFalse(policy.isAiWorkBlocked(1), "cooling below MODERATE automatically restores far prefetch")
        assertEquals(0, policy.aiWorkBlockReasonMask(1))
    }

    @Test
    fun `severe heat blocks every new AI priority while display remains available`() {
        val policy = ManyueReaderPressurePolicy()
        val reader = Any()
        policy.update(reader, active = false)
        policy.reportThermalStatus(reader, ManyueReaderPressurePolicy.THERMAL_STATUS_SEVERE)

        listOf(1, 10, 40, 50, 100, 120).forEach { priority ->
            assertTrue(policy.isAiWorkBlocked(priority), "priority $priority must stay blocked at SEVERE")
            assertEquals(
                ManyueReaderPressurePolicy.BLOCK_REASON_THERMAL,
                policy.aiWorkBlockReasonMask(priority),
                "thermal diagnostic reason must match the scheduling decision for priority $priority",
            )
        }
        assertFalse(policy.isDisplayBlocked(), "SEVERE blocks new inference without hiding completed images")
    }

    @Test
    fun `cooling restores speculative work without changing image quality`() {
        val policy = ManyueReaderPressurePolicy()
        val reader = Any()
        policy.update(reader, active = false)
        policy.reportThermalStatus(reader, 2)
        policy.afterAiWork(2_000_000_000L)
        assertTrue(policy.isBlocked())
        assertTrue(policy.isAiWorkBlocked(1))
        policy.reportThermalStatus(reader, 1)
        assertFalse(policy.isBlocked())
        assertFalse(policy.isAiWorkBlocked(1))
        assertFalse(policy.isVisibleWorkBlocked())
        policy.reportThermalStatus(reader, 3)
        assertTrue(policy.isVisibleWorkBlocked())
        assertTrue(policy.isAiWorkBlocked(1))
        assertFalse(policy.isDisplayBlocked())
    }

    @Test
    fun `moderate thermal callbacks cannot perpetually postpone prefetch`() {
        val clock = FakeClock(-5_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        policy.update(reader, active = false)
        policy.reportThermalStatus(reader, 2)
        policy.afterAiWork(20_000_000_000L) // Even long pages get at most a one-second interval.
        repeat(9) {
            clock.advance(100_000_000L)
            policy.reportThermalStatus(reader, 2)
            assertTrue(policy.isBlocked())
            assertTrue(policy.isAiWorkBlocked(1))
        }
        clock.advance(100_000_000L)
        policy.reportThermalStatus(reader, 2)
        assertFalse(policy.isBlocked())
        assertTrue(policy.isAiWorkBlocked(1), "callbacks do not release the far-prefetch warm hold")
    }

    @Test
    fun `moderate warm reading keeps window work progressing while severe heat stops inference`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        policy.update(reader, active = false)
        policy.reportThermalStatus(reader, 2)
        repeat(12) {
            assertTrue(policy.isAiWorkBlocked(1), "moderate heat holds continuous work beyond the window")
            assertFalse(policy.isAiWorkBlocked(10), "window work remains available during text dwell")
            assertFalse(policy.isAiWorkBlocked(40))
            assertFalse(policy.isAiWorkBlocked(50), "N+1 remains available during text dwell")
            assertFalse(policy.isAiWorkBlocked(100))
            clock.advance(2_000_000_000L)
            policy.afterAiWork(2_000_000_000L)
            assertTrue(policy.isAiWorkBlocked(1))
            assertTrue(policy.isAiWorkBlocked(10), "window work retains its finite per-job thermal interval")
            assertTrue(policy.isAiWorkBlocked(40))
            assertFalse(policy.isVisibleWorkBlocked(), "a newly visible request bypasses thermal pacing")
            assertFalse(policy.isAiWorkBlocked(50))
            assertFalse(policy.isAiWorkBlocked(100))
            clock.advance(500_000_000L)
            assertFalse(policy.isAiWorkBlocked(10))
            assertFalse(policy.isAiWorkBlocked(40))
            assertTrue(policy.isAiWorkBlocked(1))
        }
        for (status in 3..6) {
            policy.reportThermalStatus(reader, status)
            clock.advance(10_000_000_000L)
            assertTrue(policy.isBlocked())
            listOf(1, 10, 40, 50, 100).forEach { priority ->
                assertTrue(policy.isAiWorkBlocked(priority), "priority $priority stays blocked at thermal status $status")
            }
            assertTrue(policy.isVisibleWorkBlocked())
            assertFalse(policy.isDisplayBlocked(), "completed pictures remain displayable")
        }
    }

    @Test
    fun `thermal work interval is cleared by detach and not inherited by the next reader`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        policy.update(reader, active = false)
        policy.reportThermalStatus(reader, 2)
        policy.afterAiWork(2_000_000_000L)
        assertTrue(policy.isBlocked())
        policy.release(reader)
        policy.afterAiWork(2_000_000_000L)
        policy.update(Any(), active = false)
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

    @Test
    fun `diagnostic reason snapshot matches the unchanged priority gate rules`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueReaderPressurePolicy(clock::now)
        val reader = Any()
        policy.update(reader, active = true)

        repeat(5) {
            clock.advance(8_000_000L)
            policy.reportScroll(reader, dx = 100, dy = 0, viewportHeightPx = 100)
        }
        assertEquals(ManyueReaderPressurePolicy.BLOCK_REASON_FAST_SCROLL, policy.aiWorkBlockReasonMask(49))
        assertEquals(0, policy.aiWorkBlockReasonMask(50), "nearest read-ahead continues during fast motion")
        assertEquals(0, policy.aiWorkBlockReasonMask(99))
        assertEquals(0, policy.aiWorkBlockReasonMask(100), "visible work continues during fast scroll")
        assertEquals(policy.aiWorkBlockReasonMask(49) != 0, policy.isBlocked())
        assertEquals(policy.aiWorkBlockReasonMask(100) != 0, policy.isVisibleWorkBlocked())
        listOf(1, 10, 40, 49, 50, 99, 100).forEach { priority ->
            assertEquals(
                policy.aiWorkBlockReasonMask(priority) != 0,
                policy.isAiWorkBlocked(priority),
                "diagnostic reason must match the scheduling decision for priority $priority",
            )
        }

        clock.advance(FAST_SCROLL_HOLD_NANOS + 1L)
        repeat(3) {
            clock.advance(20_000_000L)
            policy.reportFrame(reader, frameIntervalNanos = 20_000_000L, expectedIntervalNanos = 8_333_333L)
        }
        assertEquals(ManyueReaderPressurePolicy.BLOCK_REASON_BAD_FRAME, policy.aiWorkBlockReasonMask(100))
        policy.reportThermalStatus(reader, ManyueReaderWorkGate.THERMAL_STATUS_SEVERE)
        assertEquals(
            ManyueReaderPressurePolicy.BLOCK_REASON_THERMAL or ManyueReaderPressurePolicy.BLOCK_REASON_BAD_FRAME,
            policy.aiWorkBlockReasonMask(100),
        )
        assertEquals(policy.aiWorkBlockReasonMask(100) != 0, policy.isVisibleWorkBlocked())
        listOf(1, 10, 40, 49, 50, 99, 100).forEach { priority ->
            assertEquals(
                policy.aiWorkBlockReasonMask(priority) != 0,
                policy.isAiWorkBlocked(priority),
                "diagnostic reason must match the scheduling decision for priority $priority",
            )
        }
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
