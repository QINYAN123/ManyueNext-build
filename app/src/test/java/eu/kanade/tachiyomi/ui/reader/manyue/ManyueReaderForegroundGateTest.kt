package eu.kanade.tachiyomi.ui.reader.manyue

import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueReaderForegroundGateTest {

    @AfterEach
    fun resetGate() {
        ManyueReaderForegroundGate.resetForTest()
    }

    @Test
    fun `pause holds new Native entry while a claimed call finishes and resume admits once`() {
        val owner = Any()
        ManyueReaderForegroundGate.onResume(owner)
        val epoch = ManyueReaderForegroundGate.currentSessionEpoch
        val starts = mutableListOf<String>()

        val entered = ManyueReaderForegroundGate.tryBeginNativeEntry(epoch) {
            starts += "started"
        } as ManyueReaderForegroundGate.NativeEntryAttempt.Entered

        ManyueReaderForegroundGate.onPause(owner)
        assertEquals(1, ManyueReaderForegroundGate.activeNativeEntriesForTest)
        assertEquals(
            ManyueReaderForegroundGate.NativeEntryAttempt.Paused,
            ManyueReaderForegroundGate.tryBeginNativeEntry(epoch) { starts += "unexpected" },
        )
        assertEquals(listOf("started"), starts, "pause cannot begin a second runtime entry")

        entered.lease.close()
        assertEquals(0, ManyueReaderForegroundGate.activeNativeEntriesForTest)
        assertEquals(
            ManyueReaderForegroundGate.NativeEntryAttempt.Paused,
            ManyueReaderForegroundGate.tryBeginNativeEntry(epoch) { starts += "unexpected" },
        )
        assertEquals(listOf("started"), starts, "finishing the prior call does not reopen the gate")

        ManyueReaderForegroundGate.onResume(owner)
        val resumed = ManyueReaderForegroundGate.tryBeginNativeEntry(epoch) {
            starts += "resumed"
        } as ManyueReaderForegroundGate.NativeEntryAttempt.Entered
        resumed.lease.close()

        assertEquals(listOf("started", "resumed"), starts)
        assertEquals(0, ManyueReaderForegroundGate.activeNativeEntriesForTest)
    }

    @Test
    fun `stale owner pause and destroy cannot affect the replacement reader`() {
        val oldOwner = Any()
        ManyueReaderForegroundGate.onResume(oldOwner)
        val oldEpoch = ManyueReaderForegroundGate.currentSessionEpoch

        val currentOwner = Any()
        ManyueReaderForegroundGate.onResume(currentOwner)
        val currentEpoch = ManyueReaderForegroundGate.currentSessionEpoch
        assertTrue(currentEpoch != oldEpoch)

        ManyueReaderForegroundGate.onPause(oldOwner)
        ManyueReaderForegroundGate.onDestroy(oldOwner)

        assertFalse(ManyueReaderForegroundGate.isSessionCurrent(oldEpoch))
        assertTrue(ManyueReaderForegroundGate.isForeground(currentEpoch))
        assertEquals(
            ManyueReaderForegroundGate.NativeEntryAttempt.StaleSession,
            ManyueReaderForegroundGate.tryBeginNativeEntry(oldEpoch) {},
        )
        val current = ManyueReaderForegroundGate.tryBeginNativeEntry(currentEpoch) {}
        assertTrue(current is ManyueReaderForegroundGate.NativeEntryAttempt.Entered)
        (current as ManyueReaderForegroundGate.NativeEntryAttempt.Entered).lease.close()
    }

    @Test
    fun `first managed resume retains pre-resume standalone session`() {
        val epochBeforeResume = ManyueReaderForegroundGate.currentSessionEpoch
        ManyueReaderForegroundGate.onResume(Any())

        assertEquals(epochBeforeResume, ManyueReaderForegroundGate.currentSessionEpoch)
        assertTrue(ManyueReaderForegroundGate.isForeground(epochBeforeResume))
    }

    @Test
    fun `onCreate binds the new session before request registration and resume opens that epoch`() {
        val oldOwner = Any()
        ManyueReaderForegroundGate.onResume(oldOwner)
        ManyueReaderForegroundGate.onDestroy(oldOwner)

        val newOwner = Any()
        ManyueReaderForegroundGate.onCreate(newOwner)
        val requestEpoch = ManyueReaderForegroundGate.currentSessionEpoch

        assertTrue(ManyueReaderForegroundGate.isSessionCurrent(requestEpoch))
        assertFalse(ManyueReaderForegroundGate.isForeground(requestEpoch))
        assertEquals(
            ManyueReaderForegroundGate.NativeEntryAttempt.Paused,
            ManyueReaderForegroundGate.tryBeginNativeEntry(requestEpoch) {},
        )

        ManyueReaderForegroundGate.onResume(newOwner)

        assertEquals(requestEpoch, ManyueReaderForegroundGate.currentSessionEpoch)
        assertTrue(ManyueReaderForegroundGate.isForeground(requestEpoch))
        val entered = ManyueReaderForegroundGate.tryBeginNativeEntry(requestEpoch) {}
        assertTrue(entered is ManyueReaderForegroundGate.NativeEntryAttempt.Entered)
        (entered as ManyueReaderForegroundGate.NativeEntryAttempt.Entered).lease.close()
    }

    @Test
    fun `destroyed reader foreground waiter exits stale instead of reviving for replacement`() = runBlocking {
        val oldOwner = Any()
        ManyueReaderForegroundGate.onResume(oldOwner)
        val oldOwnerReference = WeakReference<Any>(oldOwner)
        ManyueReaderForegroundGate.onPause(oldOwner)

        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            ManyueReaderForegroundGate.awaitForeground(oldOwnerReference)
        }
        assertFalse(waiter.isCompleted, "the old reader waits while it is merely paused")

        ManyueReaderForegroundGate.onDestroy(oldOwner)
        assertFalse(waiter.await(), "destroy ends the old reader session")

        val replacement = Any()
        ManyueReaderForegroundGate.onCreate(replacement)
        ManyueReaderForegroundGate.onResume(replacement)
        assertTrue(ManyueReaderForegroundGate.isForeground(ManyueReaderForegroundGate.currentSessionEpoch))
    }
}
