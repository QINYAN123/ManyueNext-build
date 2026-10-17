package eu.kanade.tachiyomi.ui.reader.manyue

/**
 * Isolated, opt-in pacing candidate for a single current visible head during thermal SEVERE.
 *
 * This policy does not change the existing thermal gate. A caller may consult
 * [permitsSevereHead] only for a prospective SEVERE head admission, then account every actual
 * Native entry with [onNativeStarted] and [onNativeFinished]. The returned ticket is a short-lived
 * accounting token; no monitor is held while Native work runs.
 */
internal class ManyueSevereHeadPolicy(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var enabled = false
    private var epoch = 0L
    private var nextTicketId = 0L
    private var nextAllowedAtNanos: Long? = null
    private var activeTicket: Ticket? = null

    /** Read-only state for trial reporting; this is not a second source of control state. */
    @get:Synchronized
    val isEnabled: Boolean
        get() = enabled

    /**
     * Enables or disables this candidate. Repeating the current value is intentionally a no-op.
     * Every actual toggle starts a new accounting epoch; enabling begins with an eight-second
     * cooldown. A currently executing Native ticket remains physically active across a toggle.
     */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return

        this.enabled = enabled
        epoch++
        nextAllowedAtNanos = if (enabled) {
            nanoTime() + MIN_ADMISSION_INTERVAL_NANOS
        } else {
            null
        }
    }

    /** Starts a fresh owner epoch and returns the trial to its conservative disabled default. */
    @Synchronized
    fun resetOwner() {
        enabled = false
        epoch++
        nextAllowedAtNanos = null
        // Do not clear an actual in-flight call. Its old ticket may release the busy marker, but
        // cannot write its old epoch's runtime budget into a replacement reader session.
    }

    /**
     * Pure admission query. Only the explicit bottom-visible priority and exactly SEVERE qualify;
     * higher thermal levels and remote or near prefetch never pass this candidate.
     */
    @Synchronized
    fun permitsSevereHead(priority: Int, thermalStatus: Int): Boolean {
        if (!enabled || thermalStatus != THERMAL_STATUS_SEVERE || priority != HEAD_PRIORITY) return false
        if (activeTicket != null) return false

        val nextAllowed = nextAllowedAtNanos ?: return false
        return nanoTime() - nextAllowed >= 0L
    }

    /**
     * Marks an actual Native wrapper entry, including ordinary status-2 work while the trial is
     * enabled. This lets such a call consume the next SEVERE admission window. Returns null only
     * if another ticket is still active. Calls made while disabled are tracked for physical
     * non-overlap, but their completion does not write trial budget.
     */
    @Synchronized
    fun onNativeStarted(): Ticket? {
        if (activeTicket != null) return null

        nextTicketId++
        return Ticket(
            epoch = epoch,
            id = nextTicketId,
            startedAtNanos = nanoTime(),
        ).also { activeTicket = it }
    }

    /**
     * Finishes accounting for a real Native call, whether it succeeded or was cancelled. Only the
     * currently active ticket can update state; duplicate and stale-epoch completions are ignored.
     */
    @Synchronized
    fun onNativeFinished(ticket: Ticket) {
        if (activeTicket !== ticket) return

        activeTicket = null
        if (!enabled || ticket.epoch != epoch) return

        val finishedAt = nanoTime()
        val runtimeNanos = (finishedAt - ticket.startedAtNanos).coerceAtLeast(0L)
        val minimumCycleEnd = ticket.startedAtNanos + MIN_ADMISSION_INTERVAL_NANOS
        val restNanos = doubledRuntime(runtimeNanos)
        val minimumRestEnd = finishedAt + restNanos
        val enableCooldownEnd = nextAllowedAtNanos ?: finishedAt

        // Compare against one monotonic sample so wrap-around remains safe for these short windows.
        nextAllowedAtNanos = laterDeadline(
            now = finishedAt,
            first = enableCooldownEnd,
            second = minimumCycleEnd,
            third = minimumRestEnd,
        )
    }

    /** Opaque wrapper token: identity and epoch both have to match before a completion can count. */
    internal class Ticket internal constructor(
        internal val epoch: Long,
        internal val id: Long,
        internal val startedAtNanos: Long,
    )

    private fun laterDeadline(now: Long, first: Long, second: Long, third: Long): Long {
        var latest = first
        var latestRemaining = first - now
        val secondRemaining = second - now
        if (secondRemaining > latestRemaining) {
            latest = second
            latestRemaining = secondRemaining
        }
        if (third - now > latestRemaining) latest = third
        return latest
    }

    private fun doubledRuntime(runtimeNanos: Long): Long =
        if (runtimeNanos > Long.MAX_VALUE / REST_MULTIPLIER) {
            Long.MAX_VALUE
        } else {
            runtimeNanos * REST_MULTIPLIER
        }

    private companion object {
        const val HEAD_PRIORITY = 120
        const val THERMAL_STATUS_SEVERE = 3
        const val MIN_ADMISSION_INTERVAL_NANOS = 8_000_000_000L
        const val REST_MULTIPLIER = 2L
    }
}
