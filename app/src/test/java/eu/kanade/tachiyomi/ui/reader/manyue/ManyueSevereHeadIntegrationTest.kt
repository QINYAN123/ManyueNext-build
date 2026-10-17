package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueSevereHeadIntegrationTest {
    @Test
    fun `trial leaves baseline severe behavior unchanged until explicitly enabled`() {
        var now = 1_000_000_000L
        val policy = ManyueReaderPressurePolicy { now }
        val reader = Any()
        policy.update(reader, active = false)
        policy.reportThermalStatus(reader, 3)
        now += 60_000_000_000L
        assertFalse(policy.severeHeadTrialEnabled)
        listOf(1, 50, 100, 120).forEach { assertTrue(policy.isAiWorkBlocked(it)) }
        assertFalse(policy.isDisplayBlocked())

        policy.setSevereHeadTrialEnabled(true)
        assertTrue(policy.isAiWorkBlocked(120))
        now += 8_000_000_000L
        assertFalse(policy.isAiWorkBlocked(120))
        listOf(1, 10, 50, 100).forEach { assertTrue(policy.isAiWorkBlocked(it)) }
        assertTrue(policy.aiWorkBlockReasonMask(100) and ManyueReaderPressurePolicy.BLOCK_REASON_THERMAL != 0)
    }

    @Test
    fun `actual native work at moderate heat budgets a later severe admission including cancellation`() {
        var now = 1_000_000_000L
        val policy = ManyueReaderPressurePolicy { now }
        val reader = Any()
        policy.update(reader, active = false)
        policy.setSevereHeadTrialEnabled(true)
        policy.reportThermalStatus(reader, 2)
        now += 8_000_000_000L
        val ticket = policy.onNativeStarted()
        assertNotNull(ticket)
        // Moderate current work remains governed by the existing moderate path.
        assertFalse(policy.isAiWorkBlocked(120))
        policy.reportThermalStatus(reader, 3)
        assertTrue(policy.isAiWorkBlocked(120), "an active Native call is never a second permit")
        now += 2_500_000_000L
        // The same completion hook is used for successful and cancelled runtime calls.
        policy.onNativeFinished(ticket!!)
        now += 5_499_999_999L
        assertTrue(policy.isAiWorkBlocked(120))
        now += 1L
        assertFalse(policy.isAiWorkBlocked(120))
    }

    @Test
    fun `trial never bypasses critical heat or measured bad frames`() {
        var now = 1_000_000_000L
        val policy = ManyueReaderPressurePolicy { now }
        val reader = Any()
        policy.update(reader, active = false)
        policy.setSevereHeadTrialEnabled(true)
        now += 8_000_000_000L
        listOf(4, 5, 6).forEach { status ->
            policy.reportThermalStatus(reader, status)
            assertTrue(policy.isAiWorkBlocked(120))
        }
        policy.reportThermalStatus(reader, 3)
        assertFalse(policy.isAiWorkBlocked(120))
        repeat(3) { policy.reportFrame(reader, 30_000_000L, 8_333_333L) }
        assertTrue(policy.isAiWorkBlocked(120))
        assertTrue(policy.aiWorkBlockReasonMask(120) and ManyueReaderPressurePolicy.BLOCK_REASON_BAD_FRAME != 0)
        now += ManyueReaderPressurePolicy.FRAME_PRESSURE_HOLD_NANOS + 1L
        assertFalse(policy.isAiWorkBlocked(120))
    }

    @Test
    fun `leaving reader resets trial without letting stale native completion reopen it`() {
        var now = 1_000_000_000L
        val policy = ManyueReaderPressurePolicy { now }
        val oldReader = Any()
        policy.update(oldReader, active = false)
        policy.setSevereHeadTrialEnabled(true)
        val ticket = policy.onNativeStarted()!!
        policy.release(oldReader)
        val replacement = Any()
        policy.update(replacement, active = false)
        policy.reportThermalStatus(replacement, 3)
        now += 30_000_000_000L
        policy.onNativeFinished(ticket)
        assertFalse(policy.severeHeadTrialEnabled)
        assertTrue(policy.isAiWorkBlocked(120))
    }
}
