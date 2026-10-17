package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueSevereHeadPolicyTest {

    @Test
    fun `trial is off by default and only admits explicit head at exactly severe`() {
        val clock = FakeClock(1_000_000_000L)
        val policy = ManyueSevereHeadPolicy(clock::now)

        assertFalse(policy.isEnabled)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))

        policy.setEnabled(true)
        assertTrue(policy.isEnabled)
        listOf(1, 10, 50, 100, 119, 121).forEach { priority ->
            assertFalse(
                policy.permitsSevereHead(priority, SEVERE),
                "priority $priority must remain closed",
            )
        }
        listOf(0, 1, 2, 4, 5, 6).forEach { status ->
            assertFalse(
                policy.permitsSevereHead(HEAD_PRIORITY, status),
                "thermal status $status must not use the SEVERE-only exception",
            )
        }
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "enable starts with cooldown")

        clock.advance(MIN_ADMISSION_INTERVAL_NANOS - 1L)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "one nanosecond early is closed")
        clock.advance(1L)
        repeat(4) {
            assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
        }
        policy.setEnabled(true)
        assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "same-value enable is idempotent")
    }

    @Test
    fun `two and a half second Native still rests five seconds and respects eight second cycle`() {
        val clock = FakeClock(0L)
        val policy = ManyueSevereHeadPolicy(clock::now)
        policy.setEnabled(true)
        clock.advance(MIN_ADMISSION_INTERVAL_NANOS)

        val ticket = requireNotNull(policy.onNativeStarted())
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "active Native excludes another entry")
        assertNull(policy.onNativeStarted(), "a second active ticket cannot be created")
        clock.advance(2_500_000_000L)
        policy.onNativeFinished(ticket)

        clock.advance(4_999_999_999L)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "rest is not shortened")
        clock.advance(1L)
        assertFalse(
            policy.permitsSevereHead(HEAD_PRIORITY, SEVERE),
            "five seconds after finish is still before the eight-second start interval",
        )
        clock.advance(500_000_000L)
        assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
    }

    @Test
    fun `longer Native runtime imposes twice its duration after finish`() {
        val clock = FakeClock(0L)
        val policy = ManyueSevereHeadPolicy(clock::now)
        policy.setEnabled(true)
        clock.advance(MIN_ADMISSION_INTERVAL_NANOS)

        val ticket = requireNotNull(policy.onNativeStarted())
        clock.advance(5_000_000_000L)
        policy.onNativeFinished(ticket)

        clock.advance(9_999_999_999L)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "the ten-second post-call rest remains")
        clock.advance(1L)
        assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
    }

    @Test
    fun `ordinary status two Native is accounted for future severe admission`() {
        val clock = FakeClock(0L)
        val policy = ManyueSevereHeadPolicy(clock::now)
        policy.setEnabled(true)

        // Status-2 work does not consult this SEVERE admission query, but its real Native wrapper
        // still receives a ticket and consumes the pacing interval for any later status-3 call.
        val ticket = requireNotNull(policy.onNativeStarted())
        clock.advance(2_500_000_000L)
        policy.onNativeFinished(ticket)

        clock.advance(5_499_999_999L)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
        clock.advance(1L)
        assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
    }

    @Test
    fun `cancelled Native call uses the same finish accounting`() {
        val clock = FakeClock(0L)
        val policy = ManyueSevereHeadPolicy(clock::now)
        policy.setEnabled(true)
        clock.advance(MIN_ADMISSION_INTERVAL_NANOS)

        val cancelledTicket = requireNotNull(policy.onNativeStarted())
        clock.advance(3_000_000_000L)
        // Cancellation still reaches the wrapper's finally path and bills elapsed runtime.
        policy.onNativeFinished(cancelledTicket)

        clock.advance(5_999_999_999L)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
        clock.advance(1L)
        assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
    }

    @Test
    fun `reset disables and stale ticket completion cannot open or extend new epoch`() {
        val clock = FakeClock(0L)
        val policy = ManyueSevereHeadPolicy(clock::now)
        policy.setEnabled(true)
        clock.advance(MIN_ADMISSION_INTERVAL_NANOS)
        val oldTicket = requireNotNull(policy.onNativeStarted())

        clock.advance(1_000_000_000L)
        policy.resetOwner()
        assertFalse(policy.isEnabled)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
        policy.setEnabled(true)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "replacement epoch starts its own cooldown")

        clock.advance(999_999_999L)
        policy.onNativeFinished(oldTicket)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "old finish cannot bypass new cooldown")
        policy.onNativeFinished(oldTicket)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "duplicate old finish is inert")

        clock.advance(MIN_ADMISSION_INTERVAL_NANOS - 999_999_999L)
        assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
        policy.onNativeFinished(oldTicket)
        assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "stale duplicate cannot defer the new epoch")
    }

    @Test
    fun `duplicate finish cannot release a newer active ticket or change its budget`() {
        val clock = FakeClock(0L)
        val policy = ManyueSevereHeadPolicy(clock::now)
        policy.setEnabled(true)
        clock.advance(MIN_ADMISSION_INTERVAL_NANOS)

        val first = requireNotNull(policy.onNativeStarted())
        clock.advance(2_500_000_000L)
        policy.onNativeFinished(first)
        clock.advance(5_500_000_000L)
        val second = requireNotNull(policy.onNativeStarted())

        policy.onNativeFinished(first)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "duplicate finish cannot release second ticket")
        clock.advance(2_000_000_000L)
        policy.onNativeFinished(second)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "second call receives its own cooldown")
        clock.advance(5_999_999_999L)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
        clock.advance(1L)
        assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
    }

    @Test
    fun `queries and cache-only absence of Native ticket do not consume admission budget`() {
        val clock = FakeClock(0L)
        val policy = ManyueSevereHeadPolicy(clock::now)
        policy.setEnabled(true)
        clock.advance(MIN_ADMISSION_INTERVAL_NANOS)

        repeat(5) { assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE)) }
        clock.advance(1_000_000_000L)
        repeat(5) { assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE)) }
    }

    @Test
    fun `disabled Native is tracked only for non overlap and does not charge next enabled epoch`() {
        val clock = FakeClock(0L)
        val policy = ManyueSevereHeadPolicy(clock::now)
        val oldTicket = requireNotNull(policy.onNativeStarted())
        policy.setEnabled(true)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "active work stays exclusive")

        clock.advance(1_000_000_000L)
        policy.onNativeFinished(oldTicket)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE), "enable cooldown remains intact")
        clock.advance(MIN_ADMISSION_INTERVAL_NANOS - 1_000_000_000L)
        assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
    }

    @Test
    fun `set enabled false clears trial state and cannot create an admission`() {
        val clock = FakeClock(0L)
        val policy = ManyueSevereHeadPolicy(clock::now)
        policy.setEnabled(true)
        clock.advance(MIN_ADMISSION_INTERVAL_NANOS)
        assertTrue(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))

        policy.setEnabled(false)
        assertFalse(policy.isEnabled)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
        policy.setEnabled(false)
        assertFalse(policy.permitsSevereHead(HEAD_PRIORITY, SEVERE))
    }

    private class FakeClock(private var currentNanos: Long) {
        fun now(): Long = currentNanos

        fun advance(nanos: Long) {
            currentNanos += nanos
        }
    }

    private companion object {
        const val HEAD_PRIORITY = 120
        const val SEVERE = 3
        const val MIN_ADMISSION_INTERVAL_NANOS = 8_000_000_000L
    }
}
