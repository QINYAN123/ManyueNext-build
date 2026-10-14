package eu.kanade.tachiyomi.ui.reader.manyue

/** Listener lifetime independent of Android; old queued callbacks cannot affect a new reader. */
internal class ManyueReaderThermalSession(
    private val acquireOwner: () -> Unit,
    private val releaseOwner: () -> Unit,
    private val reportStatus: (Int) -> Unit,
) {
    private var active = false
    private var generation = 0L
    private var unsubscribe: (() -> Unit)? = null

    @Synchronized
    fun start(currentStatus: () -> Int, subscribe: ((Int) -> Unit) -> (() -> Unit)) {
        if (active) return
        active = true
        val epoch = ++generation
        acquireOwner()
        unsubscribe = runCatching {
            subscribe { status ->
                synchronized(this) {
                    if (active && generation == epoch) reportStatus(status)
                }
            }
        }.getOrNull()
        // Registration may be unsupported on a vendor device; still use a valid initial reading.
        runCatching(currentStatus).getOrNull()?.let(reportStatus)
    }

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        ++generation
        val remove = unsubscribe
        unsubscribe = null
        try {
            runCatching { remove?.invoke() }
        } finally {
            releaseOwner()
        }
    }
}
