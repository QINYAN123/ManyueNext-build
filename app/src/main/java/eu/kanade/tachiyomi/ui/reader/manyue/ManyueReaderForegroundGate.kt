package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Separates reader lifecycle from scroll pressure. A pause blocks new Native entry while allowing
 * a call that already claimed entry to finish and commit its cache result.
 */
internal object ManyueReaderForegroundGate {

    const val POLL_DELAY_MS = 80L

    internal sealed class NativeEntryAttempt {
        class Entered internal constructor(val lease: NativeEntryLease) : NativeEntryAttempt()
        object Paused : NativeEntryAttempt()
        object StaleSession : NativeEntryAttempt()
    }

    internal class NativeEntryLease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }

    private val lock = Any()
    private val lifecycleVersion = MutableStateFlow(0L)
    private var lifecycleManaged = false
    private var resumed = true
    private var currentOwner: WeakReference<Any>? = null
    private var sessionEpoch = 0L
    private var activeNativeEntries = 0

    /** Epoch attached to a request at registration so a later reader cannot revive stale work. */
    val currentSessionEpoch: Long
        get() = synchronized(lock) { sessionEpoch }

    /** Registers the Activity before viewer creation so requests made in onCreate use its epoch. */
    fun onCreate(owner: Any) {
        synchronized(lock) {
            val previousOwner = currentOwner?.get()
            if (!lifecycleManaged) {
                lifecycleManaged = true
                currentOwner = WeakReference(owner)
                resumed = false
            } else if (previousOwner !== owner) {
                sessionEpoch++
                currentOwner = WeakReference(owner)
                resumed = false
            }
            signalLifecycleChangeLocked()
        }
    }

    fun onResume(owner: Any) {
        synchronized(lock) {
            val previousOwner = currentOwner?.get()
            if (!lifecycleManaged) {
                // Requests admitted just before the first Activity resume belong to this reader.
                lifecycleManaged = true
                currentOwner = WeakReference(owner)
            } else if (previousOwner !== owner) {
                sessionEpoch++
                currentOwner = WeakReference(owner)
            }
            resumed = true
            signalLifecycleChangeLocked()
        }
    }

    fun onPause(owner: Any) {
        synchronized(lock) {
            if (currentOwner?.get() === owner && lifecycleManaged) {
                resumed = false
                signalLifecycleChangeLocked()
            }
        }
    }

    fun onDestroy(owner: Any) {
        synchronized(lock) {
            if (currentOwner?.get() === owner && lifecycleManaged) {
                sessionEpoch++
                currentOwner = null
                resumed = false
                signalLifecycleChangeLocked()
            }
        }
    }

    fun isSessionCurrent(epoch: Long): Boolean = synchronized(lock) {
        isSessionCurrentLocked(epoch)
    }

    fun isForeground(epoch: Long): Boolean = synchronized(lock) {
        isForegroundLocked(epoch)
    }

    /**
     * The marker callback runs under the lifecycle lock. Thus a Native start either records before
     * pause linearizes, or is denied; the expensive runtime call runs after this lock is released.
     */
    fun tryBeginNativeEntry(epoch: Long, onEntry: () -> Unit): NativeEntryAttempt = synchronized(lock) {
        if (!isSessionCurrentLocked(epoch)) {
            NativeEntryAttempt.StaleSession
        } else if (!isForegroundLocked(epoch)) {
            NativeEntryAttempt.Paused
        } else {
            activeNativeEntries++
            try {
                onEntry()
            } catch (error: Throwable) {
                activeNativeEntries--
                throw error
            }
            NativeEntryAttempt.Entered(
                NativeEntryLease {
                    synchronized(lock) {
                        check(activeNativeEntries > 0)
                        activeNativeEntries--
                    }
                },
            )
        }
    }

    /** Parks a cursor without cancelling its active stage or resetting its checkpoint. */
    suspend fun awaitForeground(owner: WeakReference<Any>? = null): Boolean {
        while (true) {
            val version = synchronized(lock) {
                if (owner != null) {
                    val expectedOwner = owner.get() ?: return false
                    if (!lifecycleManaged || currentOwner?.get() !== expectedOwner) return false
                    if (resumed) return true
                } else if (isOwnerForegroundLocked(owner)) {
                    return true
                }
                lifecycleVersion.value
            }
            lifecycleVersion.first { it != version }
        }
    }

    /** Captures an Activity through common ContextWrapper chains without retaining it strongly. */
    fun ownerReference(context: Context): WeakReference<Any>? {
        var current = context
        while (true) {
            if (current is Activity) return WeakReference(current)
            if (current !is ContextWrapper) return null
            val base = current.baseContext
            if (base === current) return null
            current = base
        }
    }

    private fun isSessionCurrentLocked(epoch: Long): Boolean =
        epoch == sessionEpoch && (!lifecycleManaged || currentOwner?.get() != null)

    private fun isForegroundLocked(epoch: Long): Boolean =
        isSessionCurrentLocked(epoch) && (!lifecycleManaged || resumed)

    private fun isOwnerForegroundLocked(owner: WeakReference<Any>?): Boolean {
        if (owner == null) {
            return !lifecycleManaged || (resumed && currentOwner?.get() != null)
        }
        val expectedOwner = owner.get() ?: return false
        return lifecycleManaged && resumed && currentOwner?.get() === expectedOwner
    }

    private fun signalLifecycleChangeLocked() {
        lifecycleVersion.value = lifecycleVersion.value + 1L
    }

    internal val activeNativeEntriesForTest: Int
        get() = synchronized(lock) { activeNativeEntries }

    /** Prevents singleton lifecycle state from leaking between host tests. */
    internal fun resetForTest() {
        synchronized(lock) {
            check(activeNativeEntries == 0) { "Native entry lease leaked from test" }
            lifecycleManaged = false
            resumed = true
            currentOwner = null
            sessionEpoch = 0L
            signalLifecycleChangeLocked()
        }
    }
}
