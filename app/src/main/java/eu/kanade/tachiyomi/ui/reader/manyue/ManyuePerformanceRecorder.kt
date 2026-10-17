package eu.kanade.tachiyomi.ui.reader.manyue

/** Numeric-only, bounded storage. No files, image bytes, source names, URLs or exception text. */
internal class ManyuePerformanceRecorder(
    private val maxEvents: Int = 2048,
    private val maxFrames: Int = 12000,
    private val clock: () -> Long = System::nanoTime,
) {
    enum class Stage {
        ENABLED,
        READER_RESUMED,
        READER_PAUSED,
        CONFIGURATION,
        PAGE_SELECTED,
        QUEUED,
        STARTED,
        READ_AHEAD_SELECTED,
        PRIORITY_CHANGED,
        CANCELLED,
        CACHE_HIT,
        REUSED_RESULT,
        NATIVE_DETAIL,
        AI_READY,
        AI_FAILED,
        DISPLAY_STARTED,
        DISPLAY_PREWARM_STARTED,
        NATIVE_CANCEL_REQUESTED,
        NATIVE_CANCEL_ACK,
        NATIVE_CANCEL_HARD,
        DISPLAY_PREPARED,
        CLASSIC_DETAIL,
        SOURCE_READY,
        PAGE_VISIBLE,
        BASE_READY,
        DISPLAYED,
        DISPLAY_FAILED,
        THERMAL_CHANGED,
        VISIBILITY_WAIT_STARTED,
        VISIBILITY_WAIT_FINISHED,
        WORK_BLOCKED,
        WORK_UNBLOCKED,
        WORK_BLOCK_CANCELLED,
        PREFETCH_ENTER,
        PREFETCH_EXIT,
        PREFETCH_CACHE_PRESSURE,
        AI_VIEWPORT_HEAD_CHANGED,
        NATIVE_CALL_STARTED,
        NATIVE_CALL_FINISHED,
        NATIVE_SESSION_PHASE,
        CACHE_LOOKUP_FINISHED,
    }

    enum class Metric {
        CANCEL_ACK_MS,
        AHEAD_PAGES,
        PRIORITY,
        QUEUE_DEPTH,
        QUEUE_MS,
        PREPARATION_MS,
        NATIVE_MS,
        POST_MS,
        SOURCE_WIDTH,
        SOURCE_HEIGHT,
        WIDTH,
        HEIGHT,
        SCALE_PERCENT,
        STRENGTH,
        MODEL,
        MODE,
        CLASSIC_PREP_MS,
        CLASSIC_DECODE_MS,
        CLASSIC_FILTER_MS,
        CLASSIC_ENCODE_MS,
        CLASSIC_STRENGTH,
        DISPLAY_PREP_MS,
        SOURCE_WAIT_MS,
        VISIBILITY_WAIT_MS,
        BASE_READY_MS,
        BASE_DECODE_MS,
        SWAP_WAIT_NS,
        SWAP_CPU_NS,
        DEFERRED_ATTEMPTS,
        DECODE_MS,
        INFER_MS,
        ENCODE_MS,
        TILES,
        MODEL_LOADS,
        MODEL_LOAD_MS,
        RESIZE_MS,
        TILE_SIZE,
        WORKING_BYTES,
        BACKEND,
        GPU_FALLBACK,
        OUTPUT_TILE,
        GPU_READBACK_BYTES,
        STATUS,
        WAIT_ELAPSED_MS,
        WAIT_OUTCOME,
        VIEW_ATTACHED,
        VIEW_SHOWN,
        RECT_VISIBLE,
        MANAGER_SELECTED,
        CAPTURED_GENERATION,
        CURRENT_GENERATION,
        BLOCK_REASON_MASK,
        READER_GATE_BLOCKED,
        WORK_PACER_BLOCKED,
        BLOCKED_ELAPSED_MS,
        VISIBLE_PAGE_COUNT,
        SESSION_PHASE,
        PHASE_ELAPSED_NS,
        PHASE_OUTCOME,
        CACHE_LOOKUP_MS,
    }

    data class Event(val timeNs: Long, val stage: Stage, val page: Int, val job: Int, val metrics: Map<Metric, Long>)
    data class Frame(
        val timeNs: Long,
        val totalNs: Long,
        val deadlineNs: Long,
        val gpuNs: Long,
        val firstDraw: Boolean,
        val reading: Boolean,
    )
    data class Snapshot(
        val session: Long,
        val enabled: Boolean,
        val durationNs: Long,
        val events: List<Event>,
        val frames: List<Frame>,
        val overwrittenEvents: Long,
        val overwrittenFrames: Long,
        val callbackDrops: Long,
        val startedAtMonotonicNs: Long,
    )

    init {
        require(maxEvents > 0 && maxFrames > 0)
    }

    @Volatile var enabled = false
        private set
    private var session = 0L
    private var startedAt = clock()
    private var stoppedAt = startedAt
    private val events = ArrayDeque<Event>()
    private val frames = ArrayDeque<Frame>()
    private var overwrittenEvents = 0L
    private var overwrittenFrames = 0L
    private var callbackDrops = 0L

    @Synchronized fun setEnabled(value: Boolean) {
        if (enabled == value) return
        if (value) clear()
        stoppedAt = clock()
        enabled = value
        if (value) event(Stage.ENABLED)
    }

    @Synchronized fun clear() {
        events.clear()
        frames.clear()
        overwrittenEvents = 0L
        overwrittenFrames = 0L
        callbackDrops = 0L
        session++
        startedAt = clock()
        stoppedAt = startedAt
    }

    /** The same monotonic session origin as events and frame callbacks, including after stop. */
    @Synchronized fun elapsedNs(): Long =
        ((if (enabled) clock() else stoppedAt) - startedAt).coerceAtLeast(0)

    fun event(stage: Stage, page: Int = -1, job: Int = 0, metrics: Map<Metric, Long> = emptyMap()) {
        if (!enabled) return
        synchronized(this) {
            if (!enabled) return
            if (events.size == maxEvents) {
                events.removeFirst()
                overwrittenEvents++
            }
            events.addLast(Event((clock() - startedAt).coerceAtLeast(0), stage, page, job, metrics.toMap()))
        }
    }

    fun frame(
        expectedSession: Long,
        totalNs: Long,
        deadlineNs: Long,
        gpuNs: Long,
        firstDraw: Boolean,
        reading: Boolean,
        drops: Int,
    ) {
        if (!enabled) return
        synchronized(this) {
            if (!enabled || session != expectedSession) return
            callbackDrops += drops.coerceAtLeast(0)
            if (totalNs < 0) return
            if (frames.size == maxFrames) {
                frames.removeFirst()
                overwrittenFrames++
            }
            frames.addLast(
                Frame((clock() - startedAt).coerceAtLeast(0), totalNs, deadlineNs, gpuNs, firstDraw, reading),
            )
        }
    }

    @Synchronized fun snapshot() = Snapshot(
        session,
        enabled,
        ((if (enabled) clock() else stoppedAt) - startedAt).coerceAtLeast(0),
        events.toList(),
        frames.toList(),
        overwrittenEvents,
        overwrittenFrames,
        callbackDrops,
        startedAt,
    )
}

/** Rate-limits repeated observations while preserving the full duration of one blocked interval. */
internal class ManyueBlockedWorkSampler(
    private val intervalNanos: Long = 1_000_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    data class Sample(val elapsedNanos: Long, val first: Boolean)

    private var blockedSinceNanos: Long? = null
    private var lastSampleNanos: Long? = null

    init {
        require(intervalNanos > 0L)
    }

    @Synchronized
    fun observe(nowNanos: Long = nanoTime()): Sample? {
        val startedAt = blockedSinceNanos
        if (startedAt == null) {
            blockedSinceNanos = nowNanos
            lastSampleNanos = nowNanos
            return Sample(elapsedNanos = 0L, first = true)
        }
        val lastSample = lastSampleNanos ?: startedAt
        if (nowNanos - lastSample < intervalNanos) return null
        lastSampleNanos = nowNanos
        return Sample((nowNanos - startedAt).coerceAtLeast(0L), first = false)
    }

    @Synchronized
    fun finish(nowNanos: Long = nanoTime()): Long? {
        val startedAt = blockedSinceNanos ?: return null
        blockedSinceNanos = null
        lastSampleNanos = null
        return (nowNanos - startedAt).coerceAtLeast(0L)
    }
}
