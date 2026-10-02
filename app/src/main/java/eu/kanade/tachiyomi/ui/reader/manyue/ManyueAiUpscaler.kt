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
    )

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
    ) : Comparable<Request> {
        internal val terminal = CompletableDeferred<Int>()

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
                    val req = queue.poll(1, TimeUnit.SECONDS)
                    if (req == null) {
                        ManyueLiteRuntime.releaseWhenIdleOnWorker(
                            ManyueRuntimeState.aiModel.continuousScale &&
                                ManyueEnhancementMode.fromInt(ManyueRuntimeState.modeInt).usesAi(),
                        )
                        continue
                    }
                    if (req.cancelled || req.completed) continue
                    if (!requestIsCurrent(req)) {
                        cancel(req.token)
                        continue
                    }
                    if (ManyueReaderWorkGate.isAiWorkBlocked(req.model, req.priority) || workPacer.isBlocked()) {
                        synchronized(this@ManyueAiUpscaler) {
                            if (!req.cancelled && !req.completed) queue.offer(req)
                        }
                        Thread.sleep(80L)
                        continue
                    }
                    val claimed = synchronized(this@ManyueAiUpscaler) {
                        if (queue.peek()?.let { shouldYieldToVisible(req, it) } == true) {
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
                    req.processingStartedAt = System.nanoTime() / 1_000_000L
                    ManyuePerformanceDiagnostics.event(
                        Stage.STARTED,
                        req.pageIndex,
                        req.token,
                        Metric.QUEUE_MS to (req.processingStartedAt - req.enqueuedAt).coerceAtLeast(0L),
                        Metric.PRIORITY to req.priority.toLong(),
                        Metric.QUEUE_DEPTH to queue.size.toLong(),
                    )
                    notifyProgress(req, STATE_PREPARING)
                    try {
                        process(req)
                    } finally {
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
        anime4kOverlay: Boolean = ManyueRuntimeState.anime4kOverlay && !model.continuousScale,
        aiDetailStrength: Int = if (model.continuousScale) ManyueRuntimeState.aiDetailStrength else 0,
    ): String {
        val requestKey = listOf(
            mangaId, chapterId, pageIndex, targetMode, targetWidth, targetScaleTenths,
            expectedMode, generation, sourceFingerprint, model.cacheIdentity, anime4kOverlay, aiDetailStrength,
        ).joinToString("|")
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
            model = model, anime4kOverlay = anime4kOverlay, aiDetailStrength = aiDetailStrength,
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
            yieldPrefetchToVisible(req)
            return
        }
        ensureStarted()
        req.enqueuedAt = System.nanoTime() / 1_000_000L
        req.queued = true
        ManyuePerformanceDiagnostics.event(
            Stage.QUEUED, req.pageIndex, token,
            Metric.PRIORITY to req.priority.toLong(), Metric.QUEUE_DEPTH to (queue.size + 1).toLong(),
            Metric.SOURCE_WIDTH to req.sourceWidth.toLong(), Metric.SOURCE_HEIGHT to req.sourceHeight.toLong(),
            Metric.WIDTH to req.targetWidth.toLong(), Metric.MODEL to req.model.ordinal.toLong(),
            Metric.STRENGTH to req.aiDetailStrength.toLong(),
        )
        queue.offer(req)
        yieldPrefetchToVisible(req)
    }

    private fun yieldPrefetchToVisible(visible: Request) {
        val active = activeRequest ?: return
        if (shouldYieldToVisible(active, visible)) {
            cancelRequest(active.token, "预读让出资源，优先处理当前可见页")
        }
    }

    internal fun shouldYieldToVisible(active: Request, visible: Request): Boolean =
        active.model.continuousScale && visible.model.continuousScale &&
            active.token != visible.token && active.priority < 100 && visible.priority >= 100 &&
            !active.cancelled && !active.completed && !visible.cancelled && !visible.completed

    /** Cancel queued or running native work. Completion still has a second stale-result guard. */
    fun cancel(token: String) {
        cancelRequest(token, "已移出预读范围，保留原图")
    }

    private fun cancelRequest(token: String, reason: String) {
        val req = requests[token] ?: return
        // A finished cache entry is still reusable on a backward scroll.
        if (req.completed) return
        if (!req.cancelled) ManyuePerformanceDiagnostics.event(Stage.CANCELLED, req.pageIndex, token)
        req.cancelled = true
        req.queued = false
        req.completionState = STATE_CANCELLED
        req.failureDetail = reason
        queue.remove(req)
        req.requestKey.takeIf(String::isNotEmpty)?.let { tokensByRequestKey.remove(it, token) }
        notify(req, STATE_CANCELLED, req.failureDetail)
        // Page selection happens on Main. Process termination and file cleanup do not.
        val cancelNative = req.nativeCancellation
        cancellationExecutor.execute {
            runCatching { cancelNative?.invoke() }
            runCatching { req.runningProcess?.destroy() }
            runCatching { req.runningProcess?.destroyForcibly() }
            req.inputFile.delete()
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
        anime4kOverlay: Boolean = ManyueRuntimeState.anime4kOverlay && !model.continuousScale,
        aiDetailStrength: Int = if (model.continuousScale) ManyueRuntimeState.aiDetailStrength else 0,
    ): String? {
        val key = listOf(
            mangaId, chapterId, pageIndex, targetMode, targetWidth, targetScaleTenths,
            expectedMode, generation, sourceFingerprint, model.cacheIdentity, anime4kOverlay, aiDetailStrength,
        ).joinToString("|")
        val token = tokensByRequestKey[key] ?: return null
        val req = requests[token]
        if (req == null || req.cancelled || req.failedAt > 0L) {
            tokensByRequestKey.remove(key, token)
            return null
        }
        if (req.completed && cachedBundle(context, token) == null) {
            tokensByRequestKey.remove(key, token)
            return null
        }
        return token
    }

    /** Reorder queued work; active priorities also follow visibility so prefetch can yield. */
    @Synchronized
    fun updatePriority(token: String, newPriority: Int) {
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
            queue.peek()?.let(::yieldPrefetchToVisible)
            return
        }
        val wasQueued = queue.remove(req)
        req.priority = newPriority
        if (wasQueued) queue.offer(req)
        if (wasQueued) yieldPrefetchToVisible(req)
        // Visibility priorities arrive in map iteration order. If a newly visible page was
        // promoted first, lowering the old active page must still release its native work.
        if (req === activeRequest) queue.peek()?.let(::yieldPrefetchToVisible)
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
            if (finalState == STATE_READY) ManyuePerformanceDiagnostics.event(Stage.REUSED_RESULT, req.pageIndex, token)
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

    private fun cleanupFinished() {
        if (requests.size <= MAX_REQUESTS) return
        val sorted = requests.values.filter { it.completed || it.cancelled }.sortedBy { it.enqueuedAt }
        val toRemove = sorted.take(requests.size - MAX_REQUESTS)
        toRemove.forEach {
            requests.remove(it.token)
            it.requestKey.takeIf(String::isNotEmpty)?.let { key -> tokensByRequestKey.remove(key, it.token) }
        }
    }

    fun state(context: Context, token: String): Int {
        val req = requests[token] ?: return STATE_FAILED
        val key = cacheKeyFor(req)
        if (ManyueEnhancementCache.getBundle(context, key) != null) return STATE_READY
        if (req.failedAt > 0L) return STATE_FAILED
        return STATE_PROCESSING
    }

    fun cachedBundle(context: Context, token: String): File? {
        val req = requests[token] ?: return null
        return ManyueEnhancementCache.getBundle(context, cacheKeyFor(req))
    }

    fun cachedImage(context: Context, token: String): ManyueEnhancementCache.CachedImage? {
        val req = requests[token] ?: return null
        return ManyueEnhancementCache.getImage(context, cacheKeyFor(req))
    }

    fun pinnedCachedImage(
        context: Context,
        token: String,
        strength: Int? = null,
    ): ManyueEnhancementCache.PinnedCachedImage? {
        val req = requests[token] ?: return null
        return ManyueEnhancementCache.pinCachedImage(context, cacheKeyFor(req), strength)
    }

    fun cachedClassicImage(
        context: Context,
        token: String,
        strength: Int,
    ): ManyueEnhancementCache.CachedImage? {
        val req = requests[token] ?: return null
        return ManyueEnhancementCache.getClassicImage(context, cacheKeyFor(req), strength)
    }

    fun cacheClassicImage(
        context: Context,
        token: String,
        strength: Int,
        bitmap: Bitmap,
    ): ManyueEnhancementCache.CachedImage? {
        val req = requests[token] ?: return null
        return ManyueEnhancementCache.putClassicImage(context, cacheKeyFor(req), strength, bitmap)
    }

    fun removeClassicImage(context: Context, token: String, strength: Int) {
        val req = requests[token] ?: return
        ManyueEnhancementCache.removeClassicImage(context, cacheKeyFor(req), strength)
    }

    /** Decode the cached full image for the optional classic pass. Call only off the main thread. */
    fun loadCachedBitmap(context: Context, token: String): Bitmap? {
        return try {
            val pinned = pinnedCachedImage(context, token) ?: return null
            pinned.lease.use { BitmapFactory.decodeFile(pinned.image.file.absolutePath) }
        } catch (t: Throwable) {
            null
        }
    }

    private fun cacheKeyFor(req: Request): String =
        ManyueEnhancementCache.cacheKey(
            req.mangaId, req.chapterId, req.pageIndex,
            ManyueEnhancementMode.AI_2X.value, req.targetMode, req.targetWidth, 0,
            req.sourceFingerprint,
            req.targetScaleTenths,
            modelId = req.model.cacheIdentity,
            anime4kOverlay = req.anime4kOverlay,
            aiDetailStrength = req.aiDetailStrength,
        )

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
            val key = cacheKeyFor(req)
            val cachedImage = ManyueEnhancementCache.getImage(context, key)
            if (cachedImage != null) {
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

            val modelDir = ensureModel(context, req.model)
            if (ManyueAiRuntime.probe(context, req.model) != ManyueAiRuntime.Capability.READY) {
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
            if (!ManyueAiSafetyPolicy.isNativeWorkSafe(req.model, req.sourceWidth, req.sourceHeight, req.targetWidth)) {
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
            // Anime4KCPP's stb-based CLI reads PNG/JPEG but not WebP, so emit PNG when that
            // optional post-filter is enabled. The default fast path stays WebP.
            val outFile = File(outDir, if (req.anime4kOverlay) "upscaled.png" else "upscaled.webp")
            val logFile = File(outDir, "native.log")
            if (!awaitReaderIdle(req)) return
            val nativeStartedAt = SystemClock.elapsedRealtime()
            val preparationElapsedMs = nativeStartedAt - preparationStartedAt
            expensiveWorkStartedAt = System.nanoTime()
            var inferenceDetail: String? = null
            if (req.model.continuousScale) {
                val telemetry = ManyueLiteRuntime.upscale(
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
                logFile.writeText(telemetry)
                ManyuePerformanceDiagnostics.nativeDetail(req.pageIndex, req.token, telemetry)
                inferenceDetail =
                    ManyueLiteRuntime.displayDetail(telemetry) +
                    if (req.aiDetailStrength <= 0) "；AI 修复强度为 0，仅普通插值" else ""
            } else {
                ManyueLiteRuntime.releaseOnWorker()
                ManyueAiRuntime.upscale(
                    context = context,
                    inputFile = req.inputFile,
                    outputFile = outFile,
                    modelDir = modelDir,
                    logFile = logFile,
                    model = req.model,
                    outputFormat = if (req.anime4kOverlay) "png" else "webp",
                    targetWidth = resolved,
                    isCancelled = { req.cancelled || !requestIsCurrent(req) },
                    onProcessStarted = {
                        req.runningProcess = it
                        notifyProgress(req, STATE_PROCESSING)
                    },
                )
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
                        ManyueAiRuntime.applyAnime4kOverlay(
                            context = context,
                            inputFile = outFile,
                            outputFile = overlayFile,
                            logFile = logFile,
                            isCancelled = { req.cancelled || !requestIsCurrent(req) },
                        )
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
            )
            check(cacheCommitted) { "AI 缓存预算不足，暂时保留原图" }
            req.completionDetail = overlayDetail
            ManyueEnhancementCache.trimCache(context)
            val waitMs = if (req.enqueuedAt > 0L) {
                (req.processingStartedAt - req.enqueuedAt).coerceAtLeast(0L)
            } else {
                0L
            }
            val postElapsedMs = SystemClock.elapsedRealtime() - postStartedAt
            ManyuePerformanceDiagnostics.event(
                Stage.AI_READY, req.pageIndex, req.token,
                Metric.QUEUE_MS to waitMs, Metric.PREPARATION_MS to preparationElapsedMs,
                Metric.NATIVE_MS to nativeElapsedMs, Metric.POST_MS to postElapsedMs,
                Metric.WIDTH to outW.toLong(), Metric.HEIGHT to outH.toLong(),
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
        } catch (_: ManyueAiRuntime.CancelledException) {
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
                workPacer.afterWork(it, pressureActive = ManyueReaderWorkGate.isAiWorkBlocked(req.model, req.priority))
            }
            req.runningProcess = null
            req.nativeCancellation = null
            req.queued = false
            if (req.cancelled) state = STATE_CANCELLED
            req.completionState = state
            req.completed = true
            if (state == STATE_FAILED) ManyuePerformanceDiagnostics.event(Stage.AI_FAILED, req.pageIndex, req.token)
            req.inputFile.delete()
            outDir?.deleteRecursively()
            notify(req, state)
            cleanupFinished()
        }
    }

    private fun awaitReaderIdle(req: Request): Boolean {
        val previousState = req.progressState
        var waited = false
        while (ManyueReaderWorkGate.isAiWorkBlocked(req.model, req.priority)) {
            if (!requestIsCurrent(req)) return false
            if (!waited) notifyProgress(req, STATE_WAITING_RESOURCES)
            waited = true
            Thread.sleep(80L)
        }
        if (waited && requestIsCurrent(req)) notifyProgress(req, previousState)
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
        val mode = ManyueRuntimeState.modeInt
        val aiMode = ManyueEnhancementMode.fromInt(mode).usesAi()
        return !req.cancelled && aiMode && mode == req.expectedMode &&
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
