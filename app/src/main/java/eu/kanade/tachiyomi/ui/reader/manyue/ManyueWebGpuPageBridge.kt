package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import eu.kanade.tachiyomi.ui.reader.metadata.SourceImageInfo
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Encoded AI sources only. GPU image lifetime and atomic page swaps belong to the viewer. */
internal class ManyueWebGpuPageBridge(
    context: Context,
    private val page: ReaderPage,
    private val alive: () -> Boolean,
    private val install: suspend (ManyueEnhancementCache.CachedImage, ManyueReadingCoverage.Result, () -> Boolean) -> Boolean,
) {
    companion object {
        // This cannot use WebGpuViewer's decode executor: its idle worker parks in lock.wait().
        internal val preparationDispatcher = Dispatchers.IO.limitedParallelism(1)
        private val preparationMutex = Mutex()
    }

    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val identity = ManyuePageBridge.Identity(
        page.chapter.chapter.manga_id ?: 0L, page.chapter.chapter.id ?: 0L, page.index,
    )
    private var generation = -1L
    private var mode = -1
    private var token: String? = null
    private var cacheKey: String? = null
    private var listener: ManyueAiUpscaler.OnAiCompleteListener? = null
    private var startJob: Job? = null
    private var prepareJob: Job? = null
    private var visible = false
    private var terminalFailure = false
    private var readyResult: ManyueReadingCoverage.Result? = null
    private var shownToken: String? = null
    private var inputFingerprint: String? = null

    val source: ManyueReadingCoverage.Source
        get() = readyResult?.source ?: run {
            val info = page.sourceImageInfo.value as? SourceImageInfo.Available
            ManyueReadingCoverage.Source(
                identity.mangaId, identity.chapterId, identity.pageIndex,
                sourceWidth = info?.width ?: 0, sourceHeight = info?.height ?: 0,
            )
        }

    fun current(result: ManyueReadingCoverage.Result): Boolean = alive() && ManyueWebGpuResultPolicy.matches(
        identity, ManyueRuntimeState.generation, ManyueRuntimeState.modeInt,
        ManyueRuntimeState.aiModel.ordinal, ManyueRuntimeState.aiScalePercent, inputFingerprint, result,
    )

    private fun current(): Boolean = alive() && generation == ManyueRuntimeState.generation &&
        mode == ManyueRuntimeState.modeInt && ManyueRuntimeState.aiModel == ManyueAiModel.QUALITY_REAL_ESRGAN

    private fun wantedForDisplay(): Boolean = visible ||
        ManyuePrefetchManager.isNextDisplayPage(identity.chapterId, identity.pageIndex)

    fun updateVisibility(isVisible: Boolean) {
        visible = isVisible
        if (!wantedForDisplay()) {
            prepareJob?.cancel()
            prepareJob = null
        }
        if (!ManyueEnhancementMode.fromInt(ManyueRuntimeState.modeInt).usesAi()) return
        if (generation != ManyueRuntimeState.generation || mode != ManyueRuntimeState.modeInt) reset()
        if (!visible || !alive() || page.status != eu.kanade.tachiyomi.source.model.Page.State.Ready) return
        if (readyResult != null) {
            prepareReady()
        } else if (token == null && startJob?.isActive != true && !terminalFailure) {
            start()
        }
    }

    private fun start() {
        generation = ManyueRuntimeState.generation
        mode = ManyueRuntimeState.modeInt
        val requestGeneration = generation
        val requestMode = mode
        page.resetEnhancementState(mode)
        startJob = scope.launch {
            val decision = PageDecisionReleaseGuard(identity.chapterId, identity.pageIndex, requestGeneration)
            try {
                val bytes = withContext(Dispatchers.IO) {
                    page.stream?.invoke()?.let(::readBoundedPrefetchBytes)
                } ?: error("original source unavailable or exceeds input budget")
                if (!current() || generation != requestGeneration || mode != requestMode || !visible) return@launch
                inputFingerprint = withContext(Dispatchers.IO) { ManyueAiUpscaler.fingerprint(bytes) }
                var rejection: ManyueAiRequestFactory.Rejection? = null
                val request = withContext(Dispatchers.IO) {
                    try {
                        ManyueAiRequestFactory.enqueue(
                            context, identity, bytes, 100, requestMode, requestGeneration,
                            onRejected = { rejection = it },
                        )
                    } finally {
                        decision.markDecisionReached()
                    }
                }
                if (!current()) return@launch
                if (request == null) {
                    terminalFailure = true
                    page.updateEnhancementState(
                        ManyueEnhancementState.SKIPPED,
                        rejection?.let(ManyuePageBridge::rejectionMessage) ?: "AI 请求未创建，已保留原图",
                    )
                    return@launch
                }
                token = request
                cacheKey = ManyueAiUpscaler.cacheKeyForToken(request)
                val callback = ManyueAiUpscaler.OnAiCompleteListener { received, state, detail ->
                    if (received != token || generation != requestGeneration || mode != requestMode || !current()) return@OnAiCompleteListener
                    when (state) {
                        ManyueAiUpscaler.STATE_READY -> {
                            readyResult = ManyueAiUpscaler.coverageResultForToken(received)
                            if (readyResult == null) {
                                terminalFailure = true
                                page.updateEnhancementState(ManyueEnhancementState.FAILED, "AI 结果身份已失效")
                            } else {
                                page.updateEnhancementState(ManyueEnhancementState.AI_WAITING_DISPLAY)
                                prepareReady()
                            }
                        }
                        ManyueAiUpscaler.STATE_PROCESSING -> page.updateEnhancementState(ManyueEnhancementState.AI_PROCESSING)
                        ManyueAiUpscaler.STATE_PREPARING -> page.updateEnhancementState(ManyueEnhancementState.AI_PREPARING)
                        ManyueAiUpscaler.STATE_WAITING_RESOURCES -> page.updateEnhancementState(ManyueEnhancementState.AI_WAITING_RESOURCES)
                        ManyueAiUpscaler.STATE_CANCELLED -> {
                            prepareJob?.cancel()
                            prepareJob = null
                            token = null
                            listener = null
                            // Re-entering a cancelled page may enqueue again; it is not a permanent skip.
                            page.updateEnhancementState(ManyueEnhancementState.AI_QUEUED, detail)
                        }
                        else -> {
                            terminalFailure = true
                            page.updateEnhancementState(ManyueEnhancementState.FAILED, detail ?: "AI 处理失败，已保留原图")
                        }
                    }
                }
                listener = callback
                ManyueAiUpscaler.addListener(request, callback)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (current()) {
                    terminalFailure = true
                    decision.markDecisionReached()
                    page.updateEnhancementState(ManyueEnhancementState.FAILED, "AI 输入读取失败，已保留原图")
                }
            } finally {
                decision.releaseIfReached()
            }
        }
    }

    private fun prepareReady() {
        val result = readyResult ?: return
        val key = cacheKey ?: return
        if (!current(result) || !wantedForDisplay() || shownToken == result.token || prepareJob?.isActive == true) return
        prepareJob = scope.launch {
            try {
                preparationMutex.withLock {
                    if (!current(result) || !wantedForDisplay()) return@withLock
                    val pinned = withContext(Dispatchers.IO) {
                        ManyueAiUpscaler.pinnedCachedImageForKey(context, key)
                    } ?: error("AI cache missing")
                    pinned.lease.use {
                        ManyuePerformanceDiagnostics.event(
                            ManyuePerformanceRecorder.Stage.DISPLAY_STARTED, page.index, result.token,
                        )
                        if (install(pinned.image, result) { current(result) && wantedForDisplay() }) {
                            // Actual AI_READY/DISPLAYED are reported only by the submitted image callback.
                            shownToken = result.token
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (current(result)) {
                    terminalFailure = true
                    shownToken = result.token // Do not repeat a failed GPU allocation every frame.
                    page.updateEnhancementState(ManyueEnhancementState.FAILED, "AI 图片显示准备失败，已保留原图")
                    ManyuePerformanceDiagnostics.event(ManyuePerformanceRecorder.Stage.DISPLAY_FAILED, page.index, result.token)
                }
            }
        }
    }

    fun reset() {
        startJob?.cancel()
        prepareJob?.cancel()
        token?.let { t -> listener?.let { ManyueAiUpscaler.removeListener(t, it) } }
        startJob = null
        prepareJob = null
        token = null
        cacheKey = null
        listener = null
        readyResult = null
        inputFingerprint = null
        shownToken = null
        terminalFailure = false
        generation = ManyueRuntimeState.generation
        mode = ManyueRuntimeState.modeInt
        page.resetEnhancementState(mode)
        ManyueReadingCoverage.hidden(this)
    }

    fun destroy() {
        reset()
        scope.cancel()
    }
}
