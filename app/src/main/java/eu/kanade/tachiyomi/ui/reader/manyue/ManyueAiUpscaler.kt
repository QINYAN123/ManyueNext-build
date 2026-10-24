package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceRecorder.Metric
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceRecorder.Stage
import kotlinx.coroutines.CompletableDeferred
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal sealed class ManyueAiCacheProbe<out T> {
    object NotAttempted : ManyueAiCacheProbe<Nothing>()
    object Miss : ManyueAiCacheProbe<Nothing>()
    data class Hit<T>(val value: T) : ManyueAiCacheProbe<T>()
    data class Failed(val error: Throwable) : ManyueAiCacheProbe<Nothing>()
}

/** Memoizes cache lookup results across the worker's pressure-gate polling. */
internal class ManyueAiCachePreflight<T> {
    @Volatile
    var outcome: ManyueAiCacheProbe<T> = ManyueAiCacheProbe.NotAttempted
        private set

    val bypassesPreInferenceGates: Boolean
        get() = outcome is ManyueAiCacheProbe.Hit<*>

    // The request has one serialized worker. Keep filesystem work outside synchronized blocks.
    fun probeOnce(probe: () -> T?): ManyueAiCacheProbe<T> {
        if (outcome !== ManyueAiCacheProbe.NotAttempted) return outcome
        outcome = try {
            probe()?.let { ManyueAiCacheProbe.Hit(it) } ?: ManyueAiCacheProbe.Miss
        } catch (error: Throwable) {
            ManyueAiCacheProbe.Failed(error)
        }
        return outcome
    }

    /** Rechecks a hit after claim, so any returned cache lease can protect its file through READY. */
    fun revalidateHit(probe: () -> T?): ManyueAiCacheProbe<T> {
        if (outcome !is ManyueAiCacheProbe.Hit<*>) return outcome
        outcome = try {
            probe()?.let { ManyueAiCacheProbe.Hit(it) } ?: ManyueAiCacheProbe.Miss
        } catch (error: Throwable) {
            ManyueAiCacheProbe.Failed(error)
        }
        return outcome
    }
}

/**
 * AI upscaling scheduler. Single-thread priority queue.
 * Process() is asynchronous: register -> queue -> listeners notified on main thread.
 */
object ManyueAiUpscaler {

    /** state codes */
    const val STATE_READY = 1
    const val STATE_PROCESSING = 0
    const val STATE_FAILED = -1
    const val STATE_CANCELLED = -2
    const val STATE_PREPARING = 2
    const val STATE_WAITING_RESOURCES = 3

    internal fun isProgressState(state: Int): Boolean =
        state == STATE_PROCESSING || state == STATE_PREPARING || state == STATE_WAITING_RESOURCES

    private const val FAILURE_COOLDOWN_MS = 30_000L
    private const val FAILURE_LOG_DIR = "manyue_ai_failures"
    private const val MAX_FAILURE_LOGS = 8
    private const val MAX_REQUESTS = 64
    private val MODEL_SHA256 = mapOf(
        ManyueAiModel.FAST_REAL_CUGAN to mapOf(
            "up2x-no-denoise.bin" to "7f135a712830b16678cb8247b9517308c5f3858fca9b10e1c3ad5ef6e261de0c",
            "up2x-no-denoise.param" to "91efac7489bf249f092faa3764e3a3d1d31ef290051e39f2afd2138c98ccce30",
        ),
        ManyueAiModel.QUALITY_REAL_ESRGAN to mapOf(
            "x2.bin" to "548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d",
            "x2.param" to "b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04",
        ),
        ManyueAiModel.QUALITY_NATIVE2 to mapOf(
            "x2.bin" to "a3a99b7bcc6a8dd094821af2cf8df8d0c1b5ae262571eb465bb9359ce28f8015",
            "x2.param" to "44f0e77ab0c793d42a2b2858b20969959d8ab985c575a8578b15e857bb518ac1",
        ),
    )
    private val hybridPolicy = ManyueHybridPolicy()

    fun interface OnAiCompleteListener {
        /** Main-thread lifecycle update: processing, ready, failed, or cancelled. */
        fun onAiComplete(token: String, state: Int, detail: String?)
    }

    data class Request(
        val token: String,
        val mangaId: Long,
        val chapterId: Long,
        val pageIndex: Int,
        val inputFile: File,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val targetMode: Int,
        val targetWidth: Int,
        val targetScaleTenths: Int = 20,
        @Volatile var priority: Int = 10,
        val expectedMode: Int = ManyueEnhancementMode.AI_2X.value,
        val generation: Long = 0L,
        val sourceFingerprint: String = "",
        var enqueuedAt: Long = 0L,
        var failedAt: Long = 0L,
        @Volatile var cancelled: Boolean = false,
        @Volatile var queued: Boolean = false,
        @Volatile var processing: Boolean = false,
        @Volatile var progressState: Int = STATE_PREPARING,
        @Volatile var runningProcess: Process? = null,
        @Volatile var completed: Boolean = false,
        @Volatile var completionState: Int = STATE_FAILED,
        var processingStartedAt: Long = 0L,
        @Volatile var failureDetail: String? = null,
        @Volatile var completionDetail: String? = null,
        val requestKey: String = "",
        val model: ManyueAiModel = ManyueAiModel.DEFAULT,
        val anime4kOverlay: Boolean = false,
        val aiDetailStrength: Int = 0,
        val enqueueSequence: Long = sequence.incrementAndGet(),
        @Volatile var nativeCancellation: (() -> Unit)? = null,
        val configuredScalePercent: Int = ManyueRuntimeState.aiScalePercent,
        val createdAsPrefetch: Boolean = priority < 100,
        @Volatile var outputWidth: Int = 0,
        @Volatile var outputHeight: Int = 0,
        @Volatile var predictiveStart: Boolean = false,
        val benchmarkNamespace: Int = ManyueBenchmarkControl.cacheNamespace,
        val readerSessionEpoch: Long = ManyueReaderForegroundGate.currentSessionEpoch,
    ) : Comparable<Request> {
        /** Selected mode stays fixed; only the serial worker may bind a physical hybrid model. */
        @Volatile internal var physicalModel: ManyueAiModel? =
            model.takeUnless { it == ManyueAiModel.QUALITY_HYBRID }
            private set
        internal val workModel: ManyueAiModel get() = physicalModel ?: model
        internal fun bindPhysicalModel(value: ManyueAiModel) {
            require(value == ManyueAiModel.QUALITY_REAL_ESRGAN || value == ManyueAiModel.QUALITY_NATIVE2)
            check(physicalModel == null || physicalModel == value) { "Result model is already pinned" }
            physicalModel = value
        }
        val cacheProfile: String get() = ManyueEnhancementCache.profileFor(
            // Retain both hybrid variants in the active chapter. Exact keys below stay physical.
            model, expectedMode, configuredScalePercent, aiDetailStrength, benchmarkNamespace,
            targetMode, targetScaleTenths,
        )
        internal val terminal = CompletableDeferred<Int>()
        internal val blockedWorkSampler = ManyueBlockedWorkSampler()
        internal var lastBlockedReasonMask = 0
        internal var lastReaderGateBlocked = false
        internal var lastWorkPacerBlocked = false
        internal val cachePreflight = ManyueAiCachePreflight<ManyueEnhancementCache.CachedImage>()
        internal var cachedImageLease: ManyueEnhancementCache.CacheLease? = null
        @Volatile internal var cacheOnlyClaim = false

        override fun compareTo(other: Request): Int =
            other.priority.compareTo(this.priority).takeIf { it != 0 }
                ?: enqueueSequence.compareTo(other.enqueueSequence)

        override fun equals(other: Any?): Boolean =
            other is Request && other.token == token

        override fun hashCode(): Int = token.hashCode()
    }

    private val sequence = java.util.concurrent.atomic.AtomicLong()

    private val cancellationExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "manyue-ai-cancel").apply { isDaemon = true }
    }
    private val requests = ConcurrentHashMap<String, Request>()
    private val tokensByRequestKey = ConcurrentHashMap<String, String>()
    private val queue = PriorityBlockingQueue<Request>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<OnAiCompleteListener>>()
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private var started = false

    @Volatile private var activeRequest: Request? = null
    private val workPacer = ManyueWorkPacer()

    @Synchronized
    private fun ensureStarted() {
        if (started) return
        started = true
        thread(name = "manyue-ai-worker", isDaemon = true) {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val polled = queue.poll(1, TimeUnit.SECONDS)
                    if (polled == null) {
                        ManyueLiteRuntime.releaseWhenIdleOnWorker(
                            ManyueRuntimeState.aiModel.continuousScale &&
                                ManyueEnhancementMode.fromInt(ManyueRuntimeState.modeInt).usesAi(),
                        )
                        ManyueAiRuntime.releaseResidentWhenIdleOnWorker(
                            !ManyueRuntimeState.aiModel.continuousScale &&
                                ManyueEnhancementMode.fromInt(ManyueRuntimeState.modeInt).usesAi(),
                        )
                        continue
                    }
                    val predicted = predictiveCandidate(polled)
                    val req = if (predicted != null && predicted !== polled && queue.remove(predicted)) {
                        queue.offer(polled)
                        predicted
                    } else polled
                    req.predictiveStart = predicted === req
                    if (req.cancelled || req.completed) {
                        finishBlockedWork(req, cancelled = req.cancelled)
                        continue
                    }
                    if (!requestIsCurrent(req)) {
                        finishBlockedWork(req, cancelled = true)
                        cancel(req.token)
                        continue
                    }
                    preflightCacheBeforeGate(req)
                    if (!requestIsCurrent(req)) {
                        finishBlockedWork(req, cancelled = true)
                        cancel(req.token)
                        continue
                    }
                    val diagnosticsEnabled = ManyuePerformanceDiagnostics.enabled
                    val applyPreInferenceGates = shouldApplyPreInferenceGates(req)
                    val foregroundBlocked = applyPreInferenceGates &&
                        !ManyueReaderForegroundGate.isForeground(req.readerSessionEpoch)
                    val readerGateBlocked = applyPreInferenceGates &&
                        ManyueReaderWorkGate.isAiWorkBlocked(effectiveWorkPriority(req))
                    // Preserve the original short-circuit when diagnostics are off. When enabled,
                    // read the pacer even if the reader gate already blocks so both reasons export.
                    val workPacerBlocked = if (
                        !applyPreInferenceGates || (readerGateBlocked && !diagnosticsEnabled)
                    ) {
                        false
                    } else {
                        workPacer.isBlocked()
                    }
                    if (foregroundBlocked || readerGateBlocked || workPacerBlocked) {
                        if (diagnosticsEnabled && (readerGateBlocked || workPacerBlocked)) {
                            recordBlockedWork(req, readerGateBlocked, workPacerBlocked)
                        }
                        synchronized(this@ManyueAiUpscaler) {
                            if (!req.cancelled && !req.completed) queue.offer(req)
                        }
                        Thread.sleep(
                            if (foregroundBlocked) ManyueReaderForegroundGate.POLL_DELAY_MS
                            else workPacer.pollDelayMillis(readerGateBlocked),
                        )
                        continue
                    }
                    finishBlockedWork(req, cancelled = false)
                    val claimed = synchronized(this@ManyueAiUpscaler) {
                        if (!req.predictiveStart && queue.peek()?.let { shouldYieldToVisible(req, it) } == true) {
                            queue.offer(req)
                            false
                        } else if (req.cancelled || req.completed || req.processing) {
                            req.queued = false
                            false
                        } else {
                            req.queued = false
                            req.processing = true
                            activeRequest = req
                            true
                        }
                    }
                    if (!claimed) continue
                    if (!requestIsCurrent(req)) {
                        cancel(req.token)
                        releaseClaim(req, requeue = false)
                        continue
                    }
                    if (req.cachePreflight.outcome is ManyueAiCacheProbe.Hit<*>) {
                        var pinnedImage: ManyueEnhancementCache.PinnedCachedImage? = null
                        val context = appContext
                        req.cachePreflight.revalidateHit {
                            pinnedImage = context?.let {
                                ManyueEnhancementCache.pinCachedImage(it, cacheKeyFor(req))
                            }
                            pinnedImage?.image
                        }
                        req.cachedImageLease = pinnedImage?.lease
                        if (!requestIsCurrent(req)) {
                            cancel(req.token)
                            releaseClaim(req, requeue = false)
                            continue
                        }
                        if (!req.cachePreflight.bypassesPreInferenceGates) {
                            releaseClaim(req, requeue = true)
                            continue
                        }
                    }
                    try {
                        req.processingStartedAt = System.nanoTime() / 1_000_000L
                        if (req.predictiveStart) ManyuePerformanceDiagnostics.event(
                            Stage.READ_AHEAD_SELECTED, req.pageIndex, req.token,
                            Metric.AHEAD_PAGES to (ManyueReadAheadScheduler.policy.distance(
                                req.chapterId, req.pageIndex, req.generation,
                            ) ?: 0).toLong(),
                        )
                        ManyuePerformanceDiagnostics.event(
                            Stage.STARTED,
                            req.pageIndex,
                            req.token,
                            Metric.QUEUE_MS to (req.processingStartedAt - req.enqueuedAt).coerceAtLeast(0L),
                            Metric.PRIORITY to req.priority.toLong(),
                            Metric.QUEUE_DEPTH to queue.size.toLong(),
                        )
                        notifyProgress(req, STATE_PREPARING)
                        process(req)
                    } finally {
                        closeCachedImageLease(req)
                        req.processing = false
                        activeRequest = null
                    }
                } catch (t: Throwable) {
                    logcat(LogPriority.ERROR, t) { "Manyue AI worker survived an unexpected error" }
                    Thread.sleep(500)
                }
            }
        }
    }

    private fun preflightCacheBeforeGate(req: Request) {
        val context = appContext ?: return
        if (!shouldPreflightCacheBeforeGate(req, hasContext = true)) return
        req.cachePreflight.probeOnce {
            ManyueEnhancementCache.getImage(context, cacheKeyFor(req))
        }
    }

    internal fun shouldPreflightCacheBeforeGate(req: Request, hasContext: Boolean): Boolean =
        hasContext && req.physicalModel != null &&
            req.cachePreflight.outcome === ManyueAiCacheProbe.NotAttempted

    internal fun shouldApplyPreInferenceGates(req: Request): Boolean =
        !req.cachePreflight.bypassesPreInferenceGates

    private fun releaseClaim(req: Request, requeue: Boolean) {
        closeCachedImageLease(req)
        val cancelled = synchronized(req) {
            req.processing = false
            if (req.cancelled) {
                req.queued = false
                if (!req.completed) {
                    req.completionState = STATE_CANCELLED
                    req.completed = true
                }
                true
            } else {
                if (requeue && !req.completed) {
                    req.queued = true
                    queue.offer(req)
                } else {
                    req.queued = false
                }
                false
            }
        }
        synchronized(this) {
            if (activeRequest === req) activeRequest = null
        }
        if (cancelled) {
            // A cooperative cancellation can hand cleanup to the worker before Native starts.
            // This request has no process to finish it, so close its input here without re-notifying.
            req.terminal.complete(STATE_CANCELLED)
            req.inputFile.delete()
            cleanupFinished()
        }
    }

    private fun closeCachedImageLease(req: Request) {
        req.cachedImageLease?.close()
        req.cachedImageLease = null
    }

    /** Choose the nearest prepared future input that has enough lead for this device's cost.
     * No queue expansion or parallel inference; when motion expires normal visible priority wins.
     */
    private fun predictiveCandidate(first: Request): Request? {
        val policy = ManyueReadAheadScheduler.policy
        val candidates = (queue.toList() + first).asSequence()
            .filter { !it.cancelled && !it.completed && requestIsCurrent(it) && it.model.usesQualityRuntime }
            .mapNotNull { req ->
                val distance = policy.distance(req.chapterId, req.pageIndex, req.generation) ?: return@mapNotNull null
                val lead = policy.preferredLead(req.chapterId, req.generation, req.sourceWidth, req.sourceHeight)
                if (lead > 0 && distance in 1..ManyueAiSafetyPolicy.PREFETCH_WINDOW_PAGES) Triple(req, distance, lead) else null
            }
            .toList()
        val selectedDistance = ManyueReadAheadPolicy.choosePreparedAhead(candidates.map { it.second to it.third })
        return candidates.firstOrNull { it.second == selectedDistance }?.first
    }

    private fun effectiveWorkPriority(req: Request): Int =
        if (req.predictiveStart) maxOf(50, req.priority) else req.priority

    private fun buildRequestKey(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        targetMode: Int,
        targetWidth: Int,
        targetScaleTenths: Int,
        expectedMode: Int,
        generation: Long,
        sourceFingerprint: String,
        model: ManyueAiModel,
        aiDetailStrength: Int,
        readerSessionEpoch: Long,
    ): String {
        // The retired legacy Anime overlay is deliberately normalized out of request identity.
        return listOf(
            mangaId, chapterId, pageIndex, targetMode, targetWidth, targetScaleTenths,
            expectedMode, generation, sourceFingerprint, model.cacheIdentity, false, aiDetailStrength,
            readerSessionEpoch,
        ).joinToString("|")
    }

    @Synchronized
    fun register(
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        inputFile: File,
        sourceWidth: Int,
        sourceHeight: Int,
        targetMode: Int,
        targetWidth: Int,
        priority: Int,
        expectedMode: Int = ManyueRuntimeState.modeInt,
        generation: Long = ManyueRuntimeState.generation,
        sourceFingerprint: String = "",
        targetScaleTenths: Int = 20,
        model: ManyueAiModel = ManyueRuntimeState.aiModel,
        @Suppress("UNUSED_PARAMETER") anime4kOverlay: Boolean = false,
        aiDetailStrength: Int = if (model.continuousScale) ManyueRuntimeState.aiDetailStrength else 0,
    ): String {
        val readerSessionEpoch = ManyueReaderForegroundGate.currentSessionEpoch
        val requestKey = buildRequestKey(
            mangaId, chapterId, pageIndex, targetMode, targetWidth, targetScaleTenths,
            expectedMode, generation, sourceFingerprint, model, aiDetailStrength, readerSessionEpoch,
        )
        tokensByRequestKey[requestKey]?.let { existingToken ->
            val existing = requests[existingToken]
            if (existing != null && !existing.cancelled && !existing.completed && existing.failedAt == 0L) {
                inputFile.delete()
                updatePriority(existingToken, maxOf(existing.priority, priority))
                logcat { "Manyue AI reuse chapter=$chapterId page=$pageIndex token=$existingToken" }
                return existingToken
            }
            tokensByRequestKey.remove(requestKey, existingToken)
        }
        val token = UUID.randomUUID().toString()
        val req = Request(
            token, mangaId, chapterId, pageIndex, inputFile,
            sourceWidth, sourceHeight, targetMode, targetWidth, targetScaleTenths, priority,
            expectedMode, generation, sourceFingerprint, requestKey = requestKey,
            model = model, anime4kOverlay = false, aiDetailStrength = aiDetailStrength,
            readerSessionEpoch = readerSessionEpoch,
        )
        requests[token] = req
        tokensByRequestKey[requestKey] = token
        return token
    }

    @Synchronized
    fun queue(token: String) {
        val req = requests[token] ?: return
        if (req.failedAt > 0L && System.currentTimeMillis() - req.failedAt < FAILURE_COOLDOWN_MS) {
            notify(req, STATE_FAILED, req.failureDetail)
            return
        }
        if (req.cancelled || req.completed) return
        if (req.queued || req.processing) {
            if (!req.cacheOnlyClaim) yieldPrefetchToVisible(req)
            return
        }
        ensureStarted()
        req.enqueuedAt = System.nanoTime() / 1_000_000L
        req.queued = true
        recordQueued(req)
        queue.offer(req)
        yieldPrefetchToVisible(req)
    }

    /** Called on the existing request-factory IO path, never from Main. */
    fun queueWithCacheProbe(token: String) {
        if (!tryCompleteUnqueuedFromCache(token) { req ->
                appContext?.let { ManyueEnhancementCache.pinCachedImage(it, cacheKeyFor(req)) }
            }
        ) queue(token)
    }

    /**
     * Own only an unqueued token: the Native worker can never poll this request while we read
     * disk. Existing queued/processing requests keep their owner; hybrid stays worker-bound.
     * A miss returns to queue() and its normal pressure/foreground gates. No filesystem work
     * runs under the scheduler monitor, and this path never changes activeRequest.
     */
    internal fun tryCompleteUnqueuedFromCache(
        token: String,
        lookup: (Request) -> ManyueEnhancementCache.PinnedCachedImage?,
    ): Boolean {
        val req = synchronized(this) {
            requests[token]?.takeIf {
                !it.queued && !it.processing && !it.completed && !it.cancelled &&
                    it.failedAt == 0L && it.physicalModel != null && requestIsCurrent(it) &&
                    it.cachePreflight.outcome === ManyueAiCacheProbe.NotAttempted
            }?.also {
                it.cacheOnlyClaim = true
                it.processing = true
            }
        } ?: return false
        val startedAt = System.nanoTime() / 1_000_000L
        try {
            req.cachePreflight.probeOnce {
                val pinned = lookup(req)
                req.cachedImageLease = pinned?.lease
                pinned?.image
            }
            ManyuePerformanceDiagnostics.event(
                Stage.CACHE_LOOKUP_FINISHED, req.pageIndex, token,
                Metric.CACHE_LOOKUP_MS to ((System.nanoTime() / 1_000_000L) - startedAt).coerceAtLeast(0L),
                Metric.STATUS to when (req.cachePreflight.outcome) {
                    is ManyueAiCacheProbe.Hit<*> -> 1L
                    is ManyueAiCacheProbe.Failed -> 2L
                    else -> 0L
                },
            )
            if (!req.cachePreflight.bypassesPreInferenceGates && requestIsCurrent(req)) return false
            // Only a pinned hit or an invalidated request reaches process(). The latter takes
            // the existing cancellation cleanup; neither may enter the Native miss branch.
            req.enqueuedAt = System.nanoTime() / 1_000_000L
            req.processingStartedAt = req.enqueuedAt
            if (ManyuePerformanceDiagnostics.enabled) ManyueReadingCoverage.requested(coverageStamp(req))
            process(req)
            return true
        } finally {
            closeCachedImageLease(req)
            synchronized(this) {
                req.processing = false
                req.cacheOnlyClaim = false
            }
        }
    }

    private fun recordQueued(req: Request) {
        if (ManyuePerformanceDiagnostics.enabled) ManyueReadingCoverage.requested(coverageStamp(req))
        ManyuePerformanceDiagnostics.event(
            Stage.QUEUED, req.pageIndex, req.token,
            Metric.PRIORITY to req.priority.toLong(), Metric.QUEUE_DEPTH to (queue.size + 1).toLong(),
            Metric.SOURCE_WIDTH to req.sourceWidth.toLong(), Metric.SOURCE_HEIGHT to req.sourceHeight.toLong(),
            Metric.WIDTH to req.targetWidth.toLong(), Metric.MODEL to req.model.ordinal.toLong(),
            Metric.STRENGTH to req.aiDetailStrength.toLong(),
        )
    }

    private fun yieldPrefetchToVisible(visible: Request) {
        val active = activeRequest ?: return
        if (shouldYieldToVisible(active, visible)) {
            cancelRequest(active.token, "预读让出资源，优先处理当前可见页")
        }
    }

    internal fun shouldYieldToVisible(active: Request, visible: Request): Boolean {
        if (active.token == visible.token || active.priority >= 100 || visible.priority < 100 ||
            active.cancelled || active.completed || visible.cancelled || visible.completed
        ) {
            return false
        }
        // A predictive page is still ahead of the reader. Cancelling it whenever another
        // visible strip binds would recreate the start/cancel starvation this solves.
        // Passed pages, chapter/direction/settings changes are still cancelled by the manager.
        if (active.predictiveStart && active.processing && active.model.usesQualityRuntime &&
            active.mangaId == visible.mangaId && active.chapterId == visible.chapterId && active.generation == visible.generation &&
            ManyueReadAheadScheduler.policy.distance(active.chapterId, active.pageIndex, active.generation) in
            1..ManyueAiSafetyPolicy.PREFETCH_WINDOW_PAGES
        ) return false
        // Lite and Quality cancel at safe computation boundaries while retaining their models.
        // Only the unselectable legacy CUGAN identity still needs the former bounded finish rule.
        val sameChapter = active.mangaId == visible.mangaId && active.chapterId == visible.chapterId
        val nearby = kotlin.math.abs(active.pageIndex.toLong() - visible.pageIndex.toLong()) <=
            ManyueAiSafetyPolicy.ACTIVE_COMPLETION_LOOK_BACK_PAGES
        return active.model.continuousScale || active.model.usesQualityRuntime ||
            !active.processing || !sameChapter || !nearby
    }

    /** Cancel queued or running native work. Completion still has a second stale-result guard. */
    fun cancel(token: String) {
        cancelRequest(token, "已移出预读范围，保留原图")
    }

    private fun cancelRequest(token: String, reason: String) {
        val req = requests[token] ?: return
        // A finished cache entry is still reusable on a backward scroll.
        if (req.completed) return
        val cooperative = synchronized(req) {
            if (req.cancelled) return
            val cooperate = !req.cacheOnlyClaim && req.model.usesQualityRuntime && req.processing &&
                requestConfigurationCurrent(req)
            req.cancelled = true
            cooperate
        }
        ManyuePerformanceDiagnostics.event(Stage.CANCELLED, req.pageIndex, token)
        req.queued = false
        finishBlockedWork(req, cancelled = true)
        req.completionState = STATE_CANCELLED
        req.failureDetail = reason
        queue.remove(req)
        req.requestKey.takeIf(String::isNotEmpty)?.let { tokensByRequestKey.remove(it, token) }
        notify(req, STATE_CANCELLED, req.failureDetail)
        // The resident response reader writes the marker and waits for safe GPU acknowledgement.
        // Only the worker owns cleanup until that acknowledgement (or the hard timeout).
        if (cooperative) {
            ManyuePerformanceDiagnostics.event(Stage.NATIVE_CANCEL_REQUESTED, req.pageIndex, token)
            return
        }
        // Page selection happens on Main. Process termination and file cleanup do not.
        val cancelNative = req.nativeCancellation
        cancellationExecutor.execute {
            runCatching { cancelNative?.invoke() }
            runCatching { req.runningProcess?.destroy() }
            runCatching { req.runningProcess?.destroyForcibly() }
            req.inputFile.delete()
        }
    }

    private fun recordBlockedWork(req: Request, readerGateBlocked: Boolean, workPacerBlocked: Boolean) {
        if (!ManyuePerformanceDiagnostics.enabled) return
        // This snapshot is explanatory telemetry only. The scheduling decision continues to use
        // the existing gate boolean and pacer boolean unchanged.
        val reasonMask = if (readerGateBlocked) {
            ManyueReaderWorkGate.aiWorkBlockReasonMask(effectiveWorkPriority(req))
        } else {
            0
        }
        synchronized(req) {
            if (req.cancelled || req.completed) return
            val sample = req.blockedWorkSampler.observe() ?: return
            req.lastBlockedReasonMask = reasonMask
            req.lastReaderGateBlocked = readerGateBlocked
            req.lastWorkPacerBlocked = workPacerBlocked
            ManyuePerformanceDiagnostics.event(
                Stage.WORK_BLOCKED,
                req.pageIndex,
                req.token,
                Metric.BLOCK_REASON_MASK to reasonMask.toLong(),
                Metric.READER_GATE_BLOCKED to if (readerGateBlocked) 1L else 0L,
                Metric.WORK_PACER_BLOCKED to if (workPacerBlocked) 1L else 0L,
                Metric.BLOCKED_ELAPSED_MS to sample.elapsedNanos / 1_000_000L,
                Metric.PRIORITY to req.priority.toLong(),
                Metric.QUEUE_MS to ((System.nanoTime() / 1_000_000L) - req.enqueuedAt).coerceAtLeast(0L),
            )
        }
    }

    private fun finishBlockedWork(req: Request, cancelled: Boolean) {
        synchronized(req) {
            val elapsedNanos = req.blockedWorkSampler.finish() ?: return
            if (ManyuePerformanceDiagnostics.enabled) {
                ManyuePerformanceDiagnostics.event(
                    if (cancelled) Stage.WORK_BLOCK_CANCELLED else Stage.WORK_UNBLOCKED,
                    req.pageIndex,
                    req.token,
                    Metric.BLOCK_REASON_MASK to req.lastBlockedReasonMask.toLong(),
                    Metric.READER_GATE_BLOCKED to if (req.lastReaderGateBlocked) 1L else 0L,
                    Metric.WORK_PACER_BLOCKED to if (req.lastWorkPacerBlocked) 1L else 0L,
                    Metric.BLOCKED_ELAPSED_MS to elapsedNanos / 1_000_000L,
                    Metric.PRIORITY to req.priority.toLong(),
                    Metric.QUEUE_MS to ((System.nanoTime() / 1_000_000L) - req.enqueuedAt).coerceAtLeast(0L),
                )
            }
            req.lastBlockedReasonMask = 0
            req.lastReaderGateBlocked = false
            req.lastWorkPacerBlocked = false
        }
    }

    /** Find an existing request/cache entry for the same page, source, model and overlay. */
    fun findReusableToken(
        context: Context,
        mangaId: Long,
        chapterId: Long,
        pageIndex: Int,
        targetMode: Int,
        targetWidth: Int,
        expectedMode: Int,
        generation: Long,
        sourceFingerprint: String,
        targetScaleTenths: Int = 20,
        model: ManyueAiModel = ManyueRuntimeState.aiModel,
        @Suppress("UNUSED_PARAMETER") anime4kOverlay: Boolean = false,
        aiDetailStrength: Int = if (model.continuousScale) ManyueRuntimeState.aiDetailStrength else 0,
    ): String? {
        val readerSessionEpoch = ManyueReaderForegroundGate.currentSessionEpoch
        val key = buildRequestKey(
            mangaId, chapterId, pageIndex, targetMode, targetWidth, targetScaleTenths,
            expectedMode, generation, sourceFingerprint, model, aiDetailStrength, readerSessionEpoch,
        )
        val token = tokensByRequestKey[key] ?: return null
        val req = requests[token]
        if (req == null || req.requestKey != key || req.readerSessionEpoch != readerSessionEpoch ||
            req.cancelled || req.failedAt > 0L
        ) {
            tokensByRequestKey.remove(key, token)
            return null
        }
        if (req.completed && cachedBundle(context, token) == null) {
            tokensByRequestKey.remove(key, token)
            return null
        }
        if (ManyueReaderForegroundGate.currentSessionEpoch != readerSessionEpoch) return null
        return token
    }

    /** Reorder queued work; active priorities also follow visibility so prefetch can yield. */
    @Synchronized
    fun updatePriority(token: String, newPriority: Int) {
        updatePriority(token, newPriority, allowPreemption = true)
    }

    /** Apply one viewport snapshot before deciding whether the active work is still prefetch. */
    @Synchronized
    internal fun updatePriorities(priorities: Map<String, Int>) {
        priorities.forEach { (token, priority) -> updatePriority(token, priority, allowPreemption = false) }
        queue.peek()?.let(::yieldPrefetchToVisible)
    }

    private fun updatePriority(token: String, newPriority: Int, allowPreemption: Boolean) {
        val req = requests[token] ?: return
        if (req.cancelled || req.completed) return
        if (newPriority != req.priority) {
            ManyuePerformanceDiagnostics.event(
                Stage.PRIORITY_CHANGED,
                req.pageIndex,
                token,
                Metric.PRIORITY to newPriority.toLong(),
            )
        }
        if (req.processing) {
            req.priority = newPriority
            if (allowPreemption && !req.cacheOnlyClaim) queue.peek()?.let(::yieldPrefetchToVisible)
            return
        }
        val wasQueued = queue.remove(req)
        req.priority = newPriority
        if (wasQueued) queue.offer(req)
        if (allowPreemption && wasQueued) yieldPrefetchToVisible(req)
        // Visibility priorities arrive in map iteration order. If a newly visible page was
        // promoted first, lowering the old active page must still release its native work.
        if (allowPreemption && req === activeRequest) queue.peek()?.let(::yieldPrefetchToVisible)
    }

    @Synchronized
    fun promotePriority(token: String, priority: Int) {
        val request = requests[token] ?: return
        updatePriority(token, maxOf(request.priority, priority))
    }

    @Synchronized
    fun addListener(token: String, listener: OnAiCompleteListener) {
        val req = requests[token] ?: run {
            mainHandler.post { listener.onAiComplete(token, STATE_FAILED, "AI 任务已失效，已保留原图") }
            return
        }
        if (req.completed || req.cancelled) {
            val finalState = if (req.cancelled) STATE_CANCELLED else req.completionState
            if (finalState == STATE_READY) {
                ManyuePerformanceDiagnostics.event(Stage.REUSED_RESULT, req.pageIndex, token)
                coverageResultForToken(token)?.let { ManyueReadingCoverage.ready(it, req.createdAsPrefetch) }
            }
            mainHandler.post { listener.onAiComplete(token, finalState, req.completionDetail ?: req.failureDetail) }
            return
        }
        listeners.getOrPut(token) { CopyOnWriteArrayList() }.add(listener)
        if (req.processing) {
            mainHandler.post {
                if (!req.completed && !req.cancelled) listener.onAiComplete(token, req.progressState, null)
            }
        }
        // Close the tiny race where the worker finishes between the first check and add().
        if (req.completed && listeners[token]?.remove(listener) == true) {
            val finalState = req.completionState
            mainHandler.post { listener.onAiComplete(token, finalState, req.completionDetail ?: req.failureDetail) }
        }
    }

    fun removeListener(token: String) {
        listeners.remove(token)
    }

    /** Await worker completion independently of View listeners and the main looper. */
    suspend fun awaitCompletion(token: String): Int {
        val req = requests[token] ?: return STATE_FAILED
        if (req.cancelled) return STATE_CANCELLED
        if (req.completed) return req.completionState
        return req.terminal.await()
    }

    fun isProcessing(token: String): Boolean = requests[token]?.let {
        it.processing && !it.cancelled && !it.completed
    } == true

    fun isFinished(token: String): Boolean = requests[token]?.let {
        it.completed || it.cancelled
    } ?: true

    internal fun hasQueuedCurrentDecision(token: String): Boolean = requests[token]?.let {
        it.generation == ManyueRuntimeState.generation && !it.cancelled &&
            (it.queued || it.processing || it.completed)
    } == true

    fun removeListener(token: String, listener: OnAiCompleteListener) {
        listeners[token]?.let { current ->
            current.remove(listener)
            if (current.isEmpty()) listeners.remove(token, current)
        }
    }

    @Synchronized
    private fun notifyProgress(req: Request, state: Int) {
        require(isProgressState(state))
        req.progressState = state
        val current = listeners[req.token]?.toList() ?: return
        mainHandler.post {
            if (!req.completed && !req.cancelled) {
                current.forEach { it.onAiComplete(req.token, state, null) }
            }
        }
    }

    @Synchronized
    private fun notify(req: Request, state: Int, detail: String? = req.completionDetail ?: req.failureDetail) {
        if (!isProgressState(state)) req.terminal.complete(state)
        val ls = listeners.remove(req.token)?.toList() ?: return
        mainHandler.post {
            ls.forEach { it.onAiComplete(req.token, state, detail) }
        }
    }

    @Synchronized
    private fun cleanupFinished() {
        if (requests.size <= MAX_REQUESTS) return
        val sorted = requests.values.filter { it.completed || it.cancelled }.sortedBy { it.enqueuedAt }
        val toRemove = sorted.take(requests.size - MAX_REQUESTS)
        toRemove.forEach {
            requests.remove(it.token)
            it.requestKey.takeIf(String::isNotEmpty)?.let { key -> tokensByRequestKey.remove(key, it.token) }
        }
    }

    /** Snapshot the cache identity while the request is still registered; holders retain the key. */
    @Synchronized
    internal fun cacheKeyForToken(token: String): String? = requests[token]?.let(::cacheKeyFor)

    fun state(context: Context, token: String): Int {
        val req = requests[token] ?: return STATE_FAILED
        val key = cacheKeyFor(req)
        if (req.physicalModel != null && ManyueEnhancementCache.getBundle(context, key) != null) return STATE_READY
        if (req.failedAt > 0L) return STATE_FAILED
        return STATE_PROCESSING
    }

    fun cachedBundle(context: Context, token: String): File? {
        val key = cacheKeyForToken(token) ?: return null
        return ManyueEnhancementCache.getBundle(context, key)
    }

    fun cachedImage(context: Context, token: String): ManyueEnhancementCache.CachedImage? {
        val key = cacheKeyForToken(token) ?: return null
        return ManyueEnhancementCache.getImage(context, key)
    }

    fun pinnedCachedImage(
        context: Context,
        token: String,
        @Suppress("UNUSED_PARAMETER") strength: Int? = null,
    ): ManyueEnhancementCache.PinnedCachedImage? {
        val key = cacheKeyForToken(token) ?: return null
        return pinnedCachedImageForKey(context, key)
    }

    internal fun pinnedCachedImageForKey(
        context: Context,
        cacheKey: String,
        @Suppress("UNUSED_PARAMETER") strength: Int? = null,
    ): ManyueEnhancementCache.PinnedCachedImage? =
        ManyueEnhancementCache.pinCachedImage(context, cacheKey)

    @Suppress("UNUSED_PARAMETER")
    fun cachedClassicImage(
        context: Context,
        token: String,
        strength: Int,
    ): ManyueEnhancementCache.CachedImage? = null

    @Suppress("UNUSED_PARAMETER")
    fun cacheClassicImage(
        context: Context,
        token: String,
        strength: Int,
        bitmap: Bitmap,
    ): ManyueEnhancementCache.CachedImage? = null

    @Suppress("UNUSED_PARAMETER")
    internal fun cacheClassicImageForKey(
        context: Context,
        cacheKey: String,
        strength: Int,
        bitmap: Bitmap,
    ): ManyueEnhancementCache.CachedImage? = null

    fun removeClassicImage(context: Context, token: String, strength: Int) {
        val key = cacheKeyForToken(token) ?: return
        ManyueEnhancementCache.removeClassicImage(context, key, strength)
    }

    fun invalidateClassicImage(context: Context, token: String, strength: Int) {
        val key = cacheKeyForToken(token) ?: return
        invalidateClassicImageForKey(context, key, strength)
    }

    internal fun invalidateClassicImageForKey(context: Context, cacheKey: String, strength: Int) {
        ManyueEnhancementCache.invalidateClassicImage(context, cacheKey, strength)
    }

    /** Decode the cached full image for the optional classic pass. Call only off the main thread. */
    fun loadCachedBitmap(context: Context, token: String): Bitmap? {
        val key = cacheKeyForToken(token) ?: return null
        return loadCachedBitmapForKey(context, key)
    }

    internal fun loadCachedBitmapForKey(context: Context, cacheKey: String): Bitmap? {
        return try {
            val pinned = pinnedCachedImageForKey(context, cacheKey) ?: return null
            pinned.lease.use { BitmapFactory.decodeFile(pinned.image.file.absolutePath) }
        } catch (t: Throwable) {
            null
        }
    }

    internal fun cacheKeyFor(req: Request): String =
        ManyueEnhancementCache.cacheKey(
            req.mangaId, req.chapterId, req.pageIndex,
            ManyueEnhancementMode.AI_2X.value, req.targetMode, req.targetWidth, 0,
            req.sourceFingerprint,
            req.targetScaleTenths,
            modelId = req.workModel.cacheIdentity,
            anime4kOverlay = req.anime4kOverlay,
            aiDetailStrength = req.aiDetailStrength,
            benchmarkNamespace = req.benchmarkNamespace,
        )

    /** Immutable provenance of a completed result; no disk reads or paths on the draw thread. */
    fun coverageResultForToken(token: String): ManyueReadingCoverage.Result? {
        val req = requests[token] ?: return null
        if (!req.completed || req.cancelled || req.completionState != STATE_READY ||
            req.outputWidth <= 0 || req.outputHeight <= 0
        ) return null
        return coverageStamp(req)
    }

    private fun coverageStamp(req: Request) = ManyueReadingCoverage.Result(
            ManyueReadingCoverage.Source(
                req.mangaId, req.chapterId, req.pageIndex, req.sourceFingerprint,
                req.sourceWidth, req.sourceHeight,
            ),
            req.generation, req.model.ordinal, req.expectedMode, req.configuredScalePercent,
            req.outputWidth, req.outputHeight, req.token,
            physicalModel = req.physicalModel?.ordinal ?: req.model.ordinal,
            modelIdentity = req.workModel.cacheIdentity,
        )

    /** Runtime-call boundaries include resident startup/waits, not just GPU execution. */
    private inline fun <T> recordNativeCall(req: Request, block: () -> T): T? {
        val entry = awaitNativeEntry(req) ?: return null
        var thermalTicket: ManyueSevereHeadPolicy.Ticket? = null
        return try {
            thermalTicket = ManyueReaderWorkGate.onNativeStarted()
            block()
        } finally {
            try {
                ManyuePerformanceDiagnostics.event(Stage.NATIVE_CALL_FINISHED, req.pageIndex, req.token)
            } finally {
                try {
                    // Charge real runtime even when the request was cancelled or its owner changed.
                    thermalTicket?.let(ManyueReaderWorkGate::onNativeFinished)
                } finally {
                    entry.close()
                }
            }
        }
    }

    /**
     * Pause is a wait condition, not request cancellation. The final claim is repeated here after
     * awaitReaderIdle so pause racing with the runtime boundary cannot admit a later Native call.
     */
    private fun awaitNativeEntry(req: Request): ManyueReaderForegroundGate.NativeEntryLease? {
        val previousState = req.progressState
        var waiting = false
        while (requestIsCurrent(req)) {
            // A pause/resume can change both thermal ownership and pressure after the prior
            // awaitReaderIdle check. Recheck before claiming the final Native entry.
            if (ManyueReaderWorkGate.isAiWorkBlocked(effectiveWorkPriority(req))) {
                if (!waiting) notifyProgress(req, STATE_WAITING_RESOURCES)
                waiting = true
                Thread.sleep(ManyueReaderForegroundGate.POLL_DELAY_MS)
                continue
            }
            when (val attempt = ManyueReaderForegroundGate.tryBeginNativeEntry(req.readerSessionEpoch) {
                // Emitting this under the gate lock orders it before a racing READER_PAUSED marker.
                ManyuePerformanceDiagnostics.event(Stage.NATIVE_CALL_STARTED, req.pageIndex, req.token)
            }) {
                is ManyueReaderForegroundGate.NativeEntryAttempt.Entered -> {
                    if (waiting && requestIsCurrent(req)) notifyProgress(req, previousState)
                    return attempt.lease
                }
                ManyueReaderForegroundGate.NativeEntryAttempt.Paused -> {
                    if (!waiting) notifyProgress(req, STATE_WAITING_RESOURCES)
                    waiting = true
                    Thread.sleep(ManyueReaderForegroundGate.POLL_DELAY_MS)
                }
                ManyueReaderForegroundGate.NativeEntryAttempt.StaleSession -> return null
            }
        }
        return null
    }

    private fun process(req: Request) {
        val context = appContext ?: run {
            req.failedAt = System.currentTimeMillis()
            req.failureDetail = "AI 应用上下文未初始化"
            req.completionState = STATE_FAILED
            req.completed = true
            req.inputFile.delete()
            notify(req, STATE_FAILED)
            return
        }
        var state = STATE_FAILED
        var outDir: File? = null
        var expensiveWorkStartedAt: Long? = null
        val preparationStartedAt = SystemClock.elapsedRealtime()
        try {
            if (!requestIsCurrent(req)) return
            if (req.model == ManyueAiModel.QUALITY_HYBRID && req.physicalModel == null) {
                // Resolve after resource waiting: queued jobs must not freeze an old thermal choice.
                if (!awaitReaderIdle(req)) return
                bindHybridModel(context, req)
            }
            val key = cacheKeyFor(req)
            val cachedImage = when (val preflight = req.cachePreflight.outcome) {
                is ManyueAiCacheProbe.Hit<*> -> preflight.value as? ManyueEnhancementCache.CachedImage
                    ?: throw IllegalStateException("Invalid cached image preflight result")
                ManyueAiCacheProbe.Miss -> null
                is ManyueAiCacheProbe.Failed -> throw preflight.error
                ManyueAiCacheProbe.NotAttempted -> ManyueEnhancementCache.getImage(context, key)
            }
            if (cachedImage != null) {
                req.outputWidth = cachedImage.width
                req.outputHeight = cachedImage.height
                ManyuePerformanceDiagnostics.event(
                    Stage.CACHE_HIT,
                    req.pageIndex,
                    req.token,
                    Metric.WIDTH to cachedImage.width.toLong(),
                    Metric.HEIGHT to cachedImage.height.toLong(),
                )
                req.completionDetail = cachedImage.detail
                state = STATE_READY
                return
            }

            val modelDir = ensureModel(context, req.workModel)
            // A cache hit above may finish while paused. A cache miss waits before probing or
            // entering the model runtime, both of which can trigger Native startup work.
            if (!awaitReaderIdle(req)) {
                state = STATE_CANCELLED
                return
            }
            if (ManyueAiRuntime.probe(context, req.workModel) != ManyueAiRuntime.Capability.READY) {
                req.failedAt = System.currentTimeMillis()
                return
            }
            if (req.inputFile.length() > ManyueAiRuntime.MAX_INPUT_BYTES) {
                req.failureDetail = "原图文件超过 AI 输入限制，已保留原图"
                req.failedAt = System.currentTimeMillis()
                return
            }
            // Refuse long pages before launch when the predicted 2x image exceeds the display
            // budget. The encoded result stays on disk and the reader decodes it off the main
            // thread through its tiled image path.
            if (!ManyueAiSafetyPolicy.isNativeWorkSafe(req.workModel, req.sourceWidth, req.sourceHeight, req.targetWidth)) {
                req.failureDetail = "所选模型的图像处理结果超过像素限制，已保留原图"
                req.failedAt = System.currentTimeMillis()
                return
            }

            val resolved = req.targetWidth
            if (resolved < req.sourceWidth) return
            val resolvedHeight = targetHeight(req.sourceWidth, req.sourceHeight, resolved)
            if (!ManyueAiSafetyPolicy.isPixelBudgetSafe(
                    resolved,
                    resolvedHeight,
                    ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS,
                )
            ) {
                req.failureDetail = "自定义倍率结果超过显示像素限制，已保留原图"
                req.failedAt = System.currentTimeMillis()
                return
            }

            outDir = File(context.cacheDir, "manyue_work_${req.token}").apply { mkdirs() }
            // Anime4KCPP reads PNG/JPEG, and WebP cannot encode either axis above 16383.
            val outputFormat = ManyueAiSafetyPolicy.nativeOutputFormat(
                resolved,
                resolvedHeight,
                req.anime4kOverlay,
            )
            val outFile = File(outDir, "upscaled.$outputFormat")
            val logFile = File(outDir, "native.log")
            if (!awaitReaderIdle(req)) return
            val nativeStartedAt = SystemClock.elapsedRealtime()
            val preparationElapsedMs = nativeStartedAt - preparationStartedAt
            expensiveWorkStartedAt = System.nanoTime()
            var inferenceDetail: String? = null
            if (req.model.continuousScale) {
                ManyueAiRuntime.releaseResidentOnWorker()
                val telemetry = recordNativeCall(req) {
                    ManyueLiteRuntime.upscale(
                        modelDir,
                        req.inputFile,
                        outFile,
                        resolved,
                        req.aiDetailStrength,
                        isCancelled = { req.cancelled || !requestIsCurrent(req) },
                        onCancellationReady = { cancel ->
                            req.nativeCancellation = cancel
                            if (req.cancelled || !requestIsCurrent(req)) cancel()
                            notifyProgress(req, STATE_PROCESSING)
                        },
                    )
                } ?: run {
                    state = STATE_CANCELLED
                    return
                }
                logFile.writeText(telemetry)
                ManyuePerformanceDiagnostics.nativeDetail(req.pageIndex, req.token, telemetry)
                inferenceDetail =
                    ManyueLiteRuntime.displayDetail(telemetry) +
                    if (req.aiDetailStrength <= 0) "；AI 修复强度为 0，仅普通插值" else ""
            } else {
                ManyueLiteRuntime.releaseOnWorker()
                val telemetry = recordNativeCall(req) {
                    ManyueAiRuntime.upscaleResident(
                        context = context,
                        inputFile = req.inputFile,
                        outputFile = outFile,
                        modelDir = modelDir,
                        logFile = logFile,
                        model = req.workModel,
                        targetWidth = resolved,
                        isCancelled = { nativeRequestCancelled(req) },
                        isConfigurationCurrent = { requestConfigurationCurrent(req) },
                        onProcessStarted = {
                            req.runningProcess = it
                            notifyProgress(req, STATE_PROCESSING)
                        },
                        onPhase = if (ManyuePerformanceDiagnostics.enabled) {
                            { phase, elapsedNs, outcome ->
                                ManyuePerformanceDiagnostics.event(
                                    Stage.NATIVE_SESSION_PHASE, req.pageIndex, req.token,
                                    Metric.SESSION_PHASE to phase.ordinal.toLong(),
                                    Metric.PHASE_ELAPSED_NS to elapsedNs,
                                    Metric.PHASE_OUTCOME to outcome.ordinal.toLong(),
                                )
                            }
                        } else null,
                    )
                } ?: run {
                    state = STATE_CANCELLED
                    return
                }
                logFile.writeText(telemetry)
                ManyuePerformanceDiagnostics.nativeDetail(req.pageIndex, req.token, telemetry)
                val backend = org.json.JSONObject(telemetry).getString("backend")
                val backendLabel = when (backend) {
                    "vulkan" -> "GPU"
                    "cpu" -> "CPU"
                    "mixed" -> "GPU+CPU"
                    else -> "未知设备"
                }
                inferenceDetail = "${req.workModel.label} · $backendLabel" +
                    if (req.model == ManyueAiModel.QUALITY_HYBRID) " · 智能混轴" else ""
            }
            val nativeElapsedMs = SystemClock.elapsedRealtime() - nativeStartedAt
            req.nativeCancellation = null
            req.runningProcess = null
            if (!requestIsCurrent(req)) return

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(outFile.absolutePath, opts)
            val outW = opts.outWidth
            val outH = opts.outHeight
            if (!hasExpectedTargetOutput(req.sourceWidth, req.sourceHeight, resolved, outW, outH)) {
                req.failureDetail = "AI 原生输出尺寸不符（需要 $resolved×$resolvedHeight，实际 $outW×$outH），已保留原图"
                req.failedAt = System.currentTimeMillis()
                return
            }
            if (!ManyueAiSafetyPolicy.isPixelBudgetSafe(
                    outW,
                    outH,
                    minOf(ManyueAiRuntime.MAX_MODEL_OUTPUT_PIXELS, ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS),
                )
            ) {
                req.failedAt = System.currentTimeMillis()
                return
            }
            var selectedOutputFile = outFile
            var overlayApplied = false
            var overlayDetail: String? = inferenceDetail
            if (req.anime4kOverlay) {
                // ACNet factor=1 still runs its CNN at 2x internally, then scales back down.
                // Cap that intermediate to the same 12 MP display budget (input <= 3 MP).
                if (!ManyueAiSafetyPolicy.isPredictedNativeOutputSafe(
                        outW,
                        outH,
                        ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS,
                    )
                ) {
                    overlayDetail = "Anime4KCPP 叠加输入超过 300 万像素上限，已保留 AI 超分"
                } else if (!awaitReaderIdle(req)) {
                    return
                } else {
                    // Anime4KCPP's upstream stb writer supports PNG but not WebP. Keep the AI
                    // file untouched and select the verified overlay file only on full success.
                    val overlayFile = File(outDir, "anime4k.png")
                    val applied = try {
                        recordNativeCall(req) {
                            ManyueAiRuntime.applyAnime4kOverlay(
                                context = context,
                                inputFile = outFile,
                                outputFile = overlayFile,
                                logFile = logFile,
                                isCancelled = { req.cancelled || !requestIsCurrent(req) },
                            )
                        } ?: run {
                            state = STATE_CANCELLED
                            return
                        }
                    } catch (cancelled: ManyueAiRuntime.CancelledException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        logcat(LogPriority.WARN, error) { "Manyue Anime4K overlay failed; keeping AI output" }
                        false
                    }
                    if (!requestIsCurrent(req)) return
                    if (applied && overlayFile.isFile && overlayFile.length() > 0L) {
                        val overlayBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(overlayFile.absolutePath, overlayBounds)
                        if (overlayBounds.outWidth == outW && overlayBounds.outHeight == outH) {
                            selectedOutputFile = overlayFile
                            overlayApplied = true
                        } else {
                            overlayDetail = "Anime4KCPP 输出尺寸不匹配，已保留 AI 超分"
                            logcat {
                                "Manyue Anime4K dimension mismatch " +
                                    "ai=${outW}x$outH overlay=${overlayBounds.outWidth}x${overlayBounds.outHeight}; keeping AI output"
                            }
                        }
                    } else {
                        overlayDetail = "Anime4KCPP 处理失败，已保留 AI 超分"
                        logcat { "Manyue Anime4K overlay failed; keeping AI output" }
                    }
                }
            }
            // Lite renders the target directly; legacy workers resize their unencoded x2
            // result before encoding. Android commits the encoded file and only reads bounds.
            val postStartedAt = SystemClock.elapsedRealtime()
            if (!requestIsCurrent(req)) return
            val cacheCommitted = ManyueEnhancementCache.putImage(
                context,
                key,
                selectedOutputFile,
                outW,
                outH,
                overlayRequested = req.anime4kOverlay,
                overlayApplied = overlayApplied,
                detail = overlayDetail,
                readingIdentity = ManyueEnhancementCache.ReadingIdentity(req.mangaId, req.chapterId, req.pageIndex),
                consumeSource = true,
                profile = req.cacheProfile,
            )
            check(cacheCommitted) { "AI 缓存预算不足，暂时保留原图" }
            req.completionDetail = overlayDetail
            req.outputWidth = outW
            req.outputHeight = outH
            ManyueEnhancementCache.trimCache(context)
            val waitMs = if (req.enqueuedAt > 0L) {
                (req.processingStartedAt - req.enqueuedAt).coerceAtLeast(0L)
            } else {
                0L
            }
            val postElapsedMs = SystemClock.elapsedRealtime() - postStartedAt
            if (req.model.usesQualityRuntime) {
                ManyueReadAheadScheduler.policy.completed(req.sourceWidth, req.sourceHeight, nativeElapsedMs + postElapsedMs)
            }
            ManyuePerformanceDiagnostics.event(
                Stage.AI_READY, req.pageIndex, req.token,
                Metric.QUEUE_MS to waitMs, Metric.PREPARATION_MS to preparationElapsedMs,
                Metric.NATIVE_MS to nativeElapsedMs, Metric.POST_MS to postElapsedMs,
                Metric.WIDTH to outW.toLong(), Metric.HEIGHT to outH.toLong(),
                Metric.MODEL to req.workModel.ordinal.toLong(),
            )
            logcat {
                "Manyue AI timings chapter=${req.chapterId} page=${req.pageIndex} " +
                    "source=${req.sourceWidth}x${req.sourceHeight} output=${outW}x$outH " +
                    "queueWaitMs=$waitMs preparationMs=$preparationElapsedMs nativeMs=$nativeElapsedMs postMs=$postElapsedMs"
            }
            ManyueDiagnostics.record(
                "第 ${req.pageIndex + 1} 页 AI 完成 $outW×$outH（排队 ${waitMs}ms，" +
                    "准备/资源等待 ${preparationElapsedMs}ms，原生 ${nativeElapsedMs}ms，后处理/缓存 ${postElapsedMs}ms）" +
                    (overlayDetail?.let { "；$it" } ?: ""),
            )
            state = STATE_READY
        } catch (cancelled: ManyueAiRuntime.CancelledException) {
            ManyuePerformanceDiagnostics.event(
                if (cancelled.residentRetained) Stage.NATIVE_CANCEL_ACK else Stage.NATIVE_CANCEL_HARD,
                req.pageIndex,
                req.token,
                Metric.CANCEL_ACK_MS to (cancelled.acknowledgementMillis ?: -1L),
            )
            // Cancellation is expected during rapid paging, mode changes, and viewer teardown.
            logcat { "Manyue AI cancelled chapter=${req.chapterId} page=${req.pageIndex}" }
        } catch (t: Throwable) {
            req.failedAt = System.currentTimeMillis()
            state = STATE_FAILED
            val nativeLog = outDir?.let { File(it, "native.log") }
            val nativeDetail = nativeLog?.let { ManyueAiRuntime.readLogTail(it) }
            val fullFailure = listOfNotNull(t.message, nativeDetail?.takeIf { it.isNotBlank() })
                .joinToString("；")
            val compactFailure = fullFailure.takeLast(320).ifBlank { "未知原生错误" }
            req.failureDetail = "第 ${req.pageIndex + 1} 页 AI 失败：$compactFailure"
            preserveFailureLog(context, req, nativeLog)
            logcat(LogPriority.WARN, t) {
                "Manyue AI failed chapter=${req.chapterId} page=${req.pageIndex}; original retained"
            }
            ManyueDiagnostics.record("${req.failureDetail}；已保留原图")
        } finally {
            expensiveWorkStartedAt?.let {
                workPacer.afterWork(it, pressureActive = ManyueReaderWorkGate.isAiWorkBlocked(effectiveWorkPriority(req)))
                if (requestIsCurrent(req)) ManyueReaderWorkGate.afterAiWork(System.nanoTime() - it)
            }
            req.runningProcess = null
            req.nativeCancellation = null
            req.queued = false
            if (req.cancelled || !requestConfigurationCurrent(req) ||
                !ManyueReaderForegroundGate.isSessionCurrent(req.readerSessionEpoch)
            ) state = STATE_CANCELLED
            req.completionState = state
            req.completed = true
            if (state == STATE_READY && ManyuePerformanceDiagnostics.enabled) {
                coverageResultForToken(req.token)?.let { ManyueReadingCoverage.ready(it, req.createdAsPrefetch) }
            }
            if (state == STATE_FAILED) ManyuePerformanceDiagnostics.event(Stage.AI_FAILED, req.pageIndex, req.token)
            req.inputFile.delete()
            outDir?.deleteRecursively()
            try {
                notify(req, state)
            } finally {
                closeCachedImageLease(req)
            }
            cleanupFinished()
        }
    }

    private fun awaitReaderIdle(req: Request): Boolean {
        val previousState = req.progressState
        var waited = false
        while (ManyueReaderWorkGate.isAiWorkBlocked(effectiveWorkPriority(req)) ||
            !ManyueReaderForegroundGate.isForeground(req.readerSessionEpoch)
        ) {
            if (!requestIsCurrent(req)) return false
            if (!waited) notifyProgress(req, STATE_WAITING_RESOURCES)
            waited = true
            Thread.sleep(ManyueReaderForegroundGate.POLL_DELAY_MS)
        }
        if (waited && requestIsCurrent(req)) notifyProgress(req, previousState)
        // The final atomic Native-entry claim handles a pause that races with this return.
        return requestIsCurrent(req)
    }

    /** Positive half-up rounding, shared with the native worker without floating-point drift. */
    internal fun targetHeight(sourceWidth: Int, sourceHeight: Int, targetWidth: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth < sourceWidth ||
            targetWidth.toLong() > sourceWidth.toLong() * 2L
        ) {
            return 0
        }
        val height = (sourceHeight.toLong() * targetWidth + sourceWidth / 2L) / sourceWidth
        return if (height in 1L..Int.MAX_VALUE.toLong()) height.toInt() else 0
    }

    internal fun hasExpectedTargetOutput(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): Boolean {
        val expectedHeight = targetHeight(sourceWidth, sourceHeight, targetWidth)
        return expectedHeight > 0 && outputWidth == targetWidth && outputHeight == expectedHeight
    }

    private fun requestIsCurrent(req: Request): Boolean {
        return !req.cancelled && requestConfigurationCurrent(req) &&
            ManyueReaderForegroundGate.isSessionCurrent(req.readerSessionEpoch)
    }

    private fun bindHybridModel(context: Context, req: Request) {
        val thermalStatus = if (android.os.Build.VERSION.SDK_INT >= 29) {
            runCatching {
                context.getSystemService(android.os.PowerManager::class.java)?.currentThermalStatus ?: -1
            }.getOrDefault(-1)
        } else -1
        val nowMs = System.nanoTime() / 1_000_000L
        val visible = requests.values.filter {
            !it.cancelled && !it.completed && it.generation == req.generation &&
                it.mangaId == req.mangaId && it.chapterId == req.chapterId &&
                it.priority >= 100 && (it.queued || it.processing)
        }
        val deadlineMissed = visible.size >= 2 && visible.any {
            nowMs - it.enqueuedAt >= ManyueHybridPolicy.VISIBLE_DEADLINE_MS
        }
        val physical = hybridPolicy.choose(req.generation, thermalStatus, deadlineMissed)
        req.bindPhysicalModel(physical)
        ManyueDiagnostics.record("智能混轴：${physical.label}（系统温控 $thermalStatus，可见页积压 $deadlineMissed）")
    }

    internal fun nativeRequestCancelled(req: Request): Boolean {
        val configurationCurrent = requestConfigurationCurrent(req)
        return !configurationCurrent || !ManyueReaderForegroundGate.isSessionCurrent(req.readerSessionEpoch) || req.cancelled
    }

    private fun requestConfigurationCurrent(req: Request): Boolean {
        val mode = ManyueRuntimeState.modeInt
        val aiMode = ManyueEnhancementMode.fromInt(mode).usesAi()
        return aiMode && mode == req.expectedMode && ManyueRuntimeState.aiModel == req.model &&
            ManyueRuntimeState.generation == req.generation
    }

    private fun preserveFailureLog(context: Context, req: Request, nativeLog: File?) {
        if (nativeLog == null || !nativeLog.isFile || nativeLog.length() == 0L) return
        runCatching {
            val dir = File(context.cacheDir, FAILURE_LOG_DIR).apply { mkdirs() }
            nativeLog.copyTo(File(dir, "${req.token}.log"), overwrite = true)
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_FAILURE_LOGS)
                ?.forEach(File::delete)
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Manyue AI failure log could not be preserved" }
        }
    }

    private val verifiedModels = mutableMapOf<ManyueAiModel, File>()

    // Only the single inference worker accesses this cache. Do not hold the scheduler's
    // monitor during asset copies/hashing: Main registers listeners on that same monitor.
    private fun ensureModel(context: Context, model: ManyueAiModel): File {
        verifiedModels[model]?.let { if (it.isDirectory) return it }
        val dir = File(context.filesDir, model.runtimeDir).apply { mkdirs() }
        val hashes = if (model.continuousScale) ManyueLiteAssets.MODEL_SHA256 else MODEL_SHA256.getValue(model)
        check(hashes.keys == model.modelFiles.toSet()) { "模型完整性清单不全" }
        hashes.forEach { (name, expectedHash) ->
            val dst = File(dir, name)
            if (!dst.isFile || sha256(dst) != expectedHash) {
                val pending = File(dir, "$name.pending")
                pending.delete()
                context.assets.open("${model.assetDir}/$name").use { input ->
                    FileOutputStream(pending).use { input.copyTo(it) }
                }
                check(sha256(pending) == expectedHash) { "model checksum mismatch: $name" }
                if (dst.exists()) dst.delete()
                check(pending.renameTo(dst)) { "failed to commit model asset: $name" }
            }
        }
        verifiedModels[model] = dir
        return dir
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun customTargetWidth(sourceWidth: Int, scalePercent: Int): Int {
        if (sourceWidth <= 0) return 0
        return (sourceWidth.toLong() * scalePercent.coerceIn(100, 200) + 50L)
            .div(100L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** Legacy model-output helper; the display target is resolved by [customTargetWidth]. */
    fun fixedTargetWidth(sourceWidth: Int): Int =
        sourceWidth.toLong().times(ManyueAiModel.DEFAULT.scale)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Kept as a source-compatible helper for older integrations. */
    fun resolveTargetWidth(
        sourceWidth: Int,
        @Suppress("UNUSED_PARAMETER") targetMode: Int,
        @Suppress("UNUSED_PARAMETER") targetWidth: Int,
        @Suppress("UNUSED_PARAMETER") targetScaleTenths: Int = 20,
    ): Int = fixedTargetWidth(sourceWidth)

    /**
     * Application context must be set once at startup (e.g. in Application.onCreate).
     */
    @Volatile
    var appContext: Context? = null
}
