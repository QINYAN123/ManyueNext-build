package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Per-page bridge between the async AI scheduler and a ReaderPageImageView.
 *
 * - Captures the identity (mangaId/chapterId/pageIndex) at start.
 * - On AI completion, verifies identity still matches before refreshing.
 * - Keeps AI output encoded on disk and installs it through a staged tiled view.
 * - Optionally caches a classic-enhanced encoded variant, never handing a large Bitmap to the view.
 * - All callbacks arrive on the main thread (ManyueAiUpscaler posts to main looper).
 */
class ManyuePageBridge(
    private val view: ReaderPageImageView,
    private val config: ReaderPageImageView.Config,
) {
    companion object {
        private val renderMutex = Mutex()
        private const val SCROLL_SETTLE_MS = 300L
        internal fun mergeDisplayDetails(vararg details: String?): String? =
            details.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
                .joinToString("；")
                .takeIf(String::isNotEmpty)

        private val classicDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread({
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                task.run()
            }, "manyue-ai-classic").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    data class Identity(val mangaId: Long, val chapterId: Long, val pageIndex: Int)

    @Volatile
    private var identity: Identity? = null
    private var token: String? = null
    private var listener: ManyueAiUpscaler.OnAiCompleteListener? = null
    private var startJob: Job? = null
    private var renderJob: Job? = null
    private var retryJob: Job? = null
    private var stagedImageId: Long? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Kick off an AI upscale for [identity] using [originalBytes] (the un-classic source).
     * No-op when mode is OFF/CLASSIC, device unsupported, animated, or the fixed 2x safety budget
     * rejects the page.
     */
    fun tryStartAi(
        context: Context,
        identity: Identity,
        originalBytes: ByteArray,
        onEnhancedDimensions: (Int, Int) -> Unit,
        onState: (ManyueEnhancementState, String?) -> Unit,
        reloadOriginal: (suspend () -> ByteArray?)? = null,
    ) {
        cancel()
        val mode = ManyueRuntimeState.modeInt
        if (mode != ManyueEnhancementMode.AI_2X.value &&
            mode != ManyueEnhancementMode.AI_2X_CLASSIC.value
        ) return
        val expectedGeneration = ManyueRuntimeState.generation
        this.identity = identity
        onState(ManyueEnhancementState.AI_QUEUED, null)
        startJob = scope.launch {
            try {
                val t = withContext(Dispatchers.IO) {
                    ManyueAiRequestFactory.enqueue(
                        context = context,
                        identity = identity,
                        originalBytes = originalBytes,
                        priority = 100,
                        expectedMode = mode,
                        generation = expectedGeneration,
                    )
                } ?: run {
                    val capability = withContext(Dispatchers.IO) { ManyueAiRuntime.probe(context) }
                    val detail = if (capability == ManyueAiRuntime.Capability.READY) {
                        "图片格式、尺寸或固定 2× 安全预算不适用"
                    } else {
                        capability.userMessage
                    }
                    if (isCurrentBeforeToken(identity, mode, expectedGeneration)) {
                        onState(ManyueEnhancementState.SKIPPED, detail)
                    }
                    return@launch
                }
                if (!isCurrentBeforeToken(identity, mode, expectedGeneration)) {
                    // The request may be shared with prefetch; this view only drops its listener.
                    return@launch
                }
                token = t

                val strength = ManyueRuntimeState.classicStrength
                val wantClassic = (mode == ManyueEnhancementMode.AI_2X_CLASSIC.value)
                val completionListener = ManyueAiUpscaler.OnAiCompleteListener { tok, state, detail ->
                    if (!isCurrent(t, identity, mode, expectedGeneration)) return@OnAiCompleteListener
                    if (state == ManyueAiUpscaler.STATE_PROCESSING) {
                        onState(ManyueEnhancementState.AI_PROCESSING, null)
                        return@OnAiCompleteListener
                    }
                    if (state == ManyueAiUpscaler.STATE_CANCELLED) {
                        renderJob?.cancel()
                        renderJob = null
                        stagedImageId?.let(view::cancelStagedImage)
                        stagedImageId = null
                        onState(ManyueEnhancementState.SKIPPED, detail)
                        if (reloadOriginal != null) {
                            retryJob?.cancel()
                            retryJob = scope.launch {
                                // Cached RecyclerView holders can reappear without bind(). A
                                // cancelled strip must be able to recreate its request then.
                                try {
                                    if (!awaitDisplayPause(t, identity, mode, expectedGeneration)) return@launch
                                    val bytes = withContext(Dispatchers.IO) { reloadOriginal() }
                                    if (bytes != null && isCurrent(t, identity, mode, expectedGeneration)) {
                                        tryStartAi(context, identity, bytes, onEnhancedDimensions, onState, reloadOriginal)
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    logcat(LogPriority.WARN, error) { "Manyue strip retry failed; original retained" }
                                    if (isCurrent(t, identity, mode, expectedGeneration)) {
                                        onState(ManyueEnhancementState.FAILED, "AI 任务重试失败，已保留原图")
                                    }
                                }
                            }
                        }
                        return@OnAiCompleteListener
                    }
                    if (state != ManyueAiUpscaler.STATE_READY) {
                        onState(
                            ManyueEnhancementState.FAILED,
                            detail ?: "AI 推理失败，已保留原图",
                        )
                        return@OnAiCompleteListener
                    }
                    onState(ManyueEnhancementState.AI_WAITING_DISPLAY, null)
                    renderJob?.cancel()
                    renderJob = scope.launch {
                        var baseCacheLease: ManyueEnhancementCache.CacheLease? = null
                        var imageCacheLease: ManyueEnhancementCache.CacheLease? = null
                        try {
                            if (!awaitDisplayPause(t, identity, mode, expectedGeneration)) return@launch
                            // Never hold the global decode lock while waiting for visibility:
                            // an attached offscreen Pager page may remain hidden indefinitely.
                            val display = renderMutex.withLock {
                                if (!isCurrent(t, identity, mode, expectedGeneration)) return@withLock null
                                val baseImage = withContext(Dispatchers.IO) {
                                    val pinned = ManyueAiUpscaler.pinnedCachedImage(context, tok)
                                    if (pinned != null) baseCacheLease = pinned.lease
                                    pinned?.image
                                } ?: run {
                                    if (isCurrent(t, identity, mode, expectedGeneration)) {
                                        onState(ManyueEnhancementState.FAILED, "AI 结果读取失败")
                                    }
                                    return@withLock null
                                }
                                var image = baseImage
                                var state = ManyueEnhancementState.AI_READY
                                val classicSafe = ManyueAiSafetyPolicy.isPixelBudgetSafe(
                                    baseImage.width, baseImage.height, ManyueAiSafetyPolicy.MAX_CLASSIC_PIXELS,
                                )
                                var displayDetail = mergeDisplayDetails(detail, baseImage.detail)
                                displayDetail = mergeDisplayDetails(displayDetail, when {
                                    wantClassic && strength <= 0 -> "经典增强强度为 0"
                                    wantClassic && !classicSafe -> "经典增强超过内存限制，已保留 AI 超分"
                                    else -> null
                                })
                                if (wantClassic && strength > 0 && classicSafe) {
                                    var classic = withContext(Dispatchers.IO) {
                                        val pinned = ManyueAiUpscaler.pinnedCachedImage(context, tok, strength)
                                        if (pinned != null) imageCacheLease = pinned.lease
                                        pinned?.image
                                    }
                                    if (classic == null) {
                                        val created = withContext(classicDispatcher) {
                                            buildClassicVariant(context, tok, strength)
                                        }
                                        if (created != null) {
                                            classic = withContext(Dispatchers.IO) {
                                                val pinned = ManyueAiUpscaler.pinnedCachedImage(context, tok, strength)
                                                if (pinned != null) imageCacheLease = pinned.lease
                                                pinned?.image
                                            }
                                        }
                                    }
                                    if (classic != null) {
                                        image = classic
                                        state = ManyueEnhancementState.AI_CLASSIC_READY
                                    } else {
                                        displayDetail = mergeDisplayDetails(
                                            displayDetail,
                                            "经典增强失败，已保留 AI 超分",
                                        )
                                    }
                                }
                                DisplayResult(image, baseImage, state, displayDetail)
                            } ?: return@launch
                            if (!awaitDisplayPause(t, identity, mode, expectedGeneration)) return@launch
                            stageFile(
                                image = display.image,
                                baseImage = display.baseImage,
                                classicVariant = display.image.file != display.baseImage.file,
                                state = display.state,
                                detail = display.detail,
                                token = t,
                                expectedIdentity = identity,
                                expectedMode = mode,
                                expectedGeneration = expectedGeneration,
                                context = context,
                                strength = strength,
                                onEnhancedDimensions = onEnhancedDimensions,
                                onState = onState,
                            )
                        } catch (_: CancellationException) {
                            // Holder was rebound or detached.
                        } catch (error: Throwable) {
                            logcat(LogPriority.WARN, error) { "Manyue AI render failed; original retained" }
                            if (isCurrent(t, identity, mode, expectedGeneration)) {
                                onState(ManyueEnhancementState.FAILED, "AI 结果显示失败")
                            }
                        } finally {
                            imageCacheLease?.close()
                            baseCacheLease?.close()
                        }
                    }
                }
                listener = completionListener
                ManyueAiUpscaler.addListener(t, completionListener)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logcat(LogPriority.WARN, error) { "Manyue AI request failed; original retained" }
                if (isCurrentBeforeToken(identity, mode, expectedGeneration)) {
                    onState(ManyueEnhancementState.FAILED, "AI 任务创建失败，已保留原图")
                }
            } finally {
                ManyuePrefetchManager.releaseCurrent(identity.chapterId, identity.pageIndex)
            }
        }
    }

    private data class DisplayResult(
        val image: ManyueEnhancementCache.CachedImage,
        val baseImage: ManyueEnhancementCache.CachedImage,
        val state: ManyueEnhancementState,
        val detail: String?,
    )

    private suspend fun awaitDisplayPause(
        expectedToken: String,
        expectedIdentity: Identity,
        expectedMode: Int,
        expectedGeneration: Long,
    ): Boolean {
        val recycler = findRecyclerView()
        var quietSince = 0L
        val visibleRect = Rect()
        while (isCurrent(expectedToken, expectedIdentity, expectedMode, expectedGeneration)) {
            if (!view.isAttachedToWindow) {
                awaitAttachment()
                quietSince = 0L
                continue
            }
            val pageVisible = view.isShown && view.getGlobalVisibleRect(visibleRect)
            val settled = pageVisible && view.isManyueImageReady() && !view.isManyueInteractionActive() &&
                (recycler == null || (recycler.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                    !recycler.isComputingLayout))
            val now = SystemClock.uptimeMillis()
            if (settled) {
                // The strip RecyclerView already owns a 300 ms quiet window. Do not add
                // another window for every preparation and commit phase.
                if (recycler is WebtoonRecyclerView) return true
                if (quietSince == 0L) quietSince = now
                if (now - quietSince >= SCROLL_SETTLE_MS) return true
            } else {
                quietSince = 0L
            }
            delay(if (pageVisible) 80L else 250L)
        }
        return false
    }

    private suspend fun awaitAttachment() = suspendCancellableCoroutine<Unit> { continuation ->
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                view.removeOnAttachStateChangeListener(this)
                if (continuation.isActive) continuation.resume(Unit)
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        }
        view.addOnAttachStateChangeListener(attachListener)
        continuation.invokeOnCancellation { view.removeOnAttachStateChangeListener(attachListener) }
        if (view.isAttachedToWindow) {
            view.removeOnAttachStateChangeListener(attachListener)
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    private fun findRecyclerView(): RecyclerView? {
        var ancestor = view.parent
        while (ancestor != null) {
            if (ancestor is RecyclerView) return ancestor
            ancestor = (ancestor as? View)?.parent
        }
        return null
    }

    private fun isCurrentBeforeToken(
        expectedIdentity: Identity,
        expectedMode: Int,
        expectedGeneration: Long,
    ): Boolean = identity == expectedIdentity &&
        ManyueRuntimeState.modeInt == expectedMode &&
        ManyueRuntimeState.generation == expectedGeneration

    private fun buildClassicVariant(
        context: Context,
        token: String,
        strength: Int,
    ): ManyueEnhancementCache.CachedImage? {
        var input: Bitmap? = null
        var enhanced: Bitmap? = null
        try {
            val source = ManyueAiUpscaler.loadCachedBitmap(context, token) ?: return null
            input = source
            val output = ManyueClassicEnhancer.enhance(source, strength, isAiCombined = true)
            enhanced = output
            if (output === input) return null
            return ManyueAiUpscaler.cacheClassicImage(context, token, strength, output)
        } catch (error: Throwable) {
            logcat(LogPriority.WARN, error) { "Manyue classic AI variant failed; keeping AI output" }
            return null
        } finally {
            enhanced?.takeIf { it !== input && !it.isRecycled }?.recycle()
            input?.takeIf { !it.isRecycled }?.recycle()
        }
    }

    private fun stageFile(
        image: ManyueEnhancementCache.CachedImage,
        baseImage: ManyueEnhancementCache.CachedImage,
        classicVariant: Boolean,
        state: ManyueEnhancementState,
        detail: String?,
        token: String,
        expectedIdentity: Identity,
        expectedMode: Int,
        expectedGeneration: Long,
        context: Context,
        strength: Int,
        onEnhancedDimensions: (Int, Int) -> Unit,
        onState: (ManyueEnhancementState, String?) -> Unit,
    ) {
        var readyState = state
        var readyDetail = mergeDisplayDetails(detail, baseImage.detail)
        val recycler = findRecyclerView()
        var quietSince = 0L
        lateinit var beginStage: (ManyueEnhancementCache.CachedImage, Boolean) -> Unit
        beginStage = { target, mayFallback ->
            var callbackRanSynchronously = false
            var displayFailureReported = false
            val requestId = view.setTiledImagePreservingCurrent(
                file = target.file,
                config = config,
                shouldCommit = {
                    isCurrent(token, expectedIdentity, expectedMode, expectedGeneration)
                },
                maySwap = {
                    if (view.isManyueInteractionActive()) {
                        quietSince = 0L
                        false
                    } else if (recycler == null) {
                        true
                    } else {
                        val settled = view.isAttachedToWindow && view.isShown &&
                            view.getGlobalVisibleRect(Rect()) &&
                            recycler.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
                            !recycler.isComputingLayout
                        if (!settled) {
                            quietSince = 0L
                            false
                        } else if (recycler is WebtoonRecyclerView) {
                            true
                        } else {
                            val now = SystemClock.uptimeMillis()
                            if (quietSince == 0L) quietSince = now
                            now - quietSince >= SCROLL_SETTLE_MS
                        }
                    }
                },
                onReady = { _, _ ->
                    callbackRanSynchronously = true
                    stagedImageId = null
                    onEnhancedDimensions(target.width, target.height)
                    onState(readyState, readyDetail)
                    logcat {
                        "Manyue reader replace chapter=${expectedIdentity.chapterId} " +
                            "page=${expectedIdentity.pageIndex} token=$token"
                    }
                },
                onError = { error ->
                    callbackRanSynchronously = true
                    if (isCurrent(token, expectedIdentity, expectedMode, expectedGeneration) &&
                        mayFallback && classicVariant
                    ) {
                        logcat(LogPriority.WARN, error) {
                            "Manyue classic cache image failed; falling back to AI output"
                        }
                        readyState = ManyueEnhancementState.AI_READY
                        readyDetail = mergeDisplayDetails(
                            readyDetail,
                            "经典增强结果读取失败，已保留 AI 超分",
                        )
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            ManyueAiUpscaler.removeClassicImage(context, token, strength)
                        }
                        beginStage(baseImage, false)
                    } else if (isCurrent(token, expectedIdentity, expectedMode, expectedGeneration)) {
                        stagedImageId = null
                        logcat(LogPriority.WARN, error) {
                            "Manyue AI tiled image load failed; original retained"
                        }
                        onState(ManyueEnhancementState.FAILED, "AI 结果显示失败")
                    }
                },
                onDisplayError = { error ->
                    if (!displayFailureReported &&
                        isCurrent(token, expectedIdentity, expectedMode, expectedGeneration)
                    ) {
                        displayFailureReported = true
                        readyDetail = mergeDisplayDetails(
                            readyDetail,
                            "部分高清分块读取失败，当前保留基础画面",
                        )
                        logcat(LogPriority.WARN, error) {
                            "Manyue active AI tile failed; keeping the visible base image"
                        }
                        onState(readyState, readyDetail)
                    }
                },
            )
            if (!callbackRanSynchronously) stagedImageId = requestId
        }
        beginStage(image, classicVariant)
    }

    private fun isCurrent(
        expectedToken: String,
        expectedIdentity: Identity,
        expectedMode: Int,
        expectedGeneration: Long,
    ): Boolean = ManyueAiSafetyPolicy.isCompletionCurrent(
        expectedToken = expectedToken,
        currentToken = token,
        expectedIdentity = expectedIdentity,
        currentIdentity = identity,
        expectedMode = expectedMode,
        currentMode = ManyueRuntimeState.modeInt,
        expectedGeneration = expectedGeneration,
        currentGeneration = ManyueRuntimeState.generation,
    )

    /** Cancel pending AI and drop any identity binding. Safe to call repeatedly. */
    fun cancel() {
        retryJob?.cancel()
        retryJob = null
        startJob?.cancel()
        startJob = null
        val currentToken = token
        val currentListener = listener
        if (currentToken != null && currentListener != null) {
            ManyueAiUpscaler.removeListener(currentToken, currentListener)
        }
        stagedImageId?.let(view::cancelStagedImage)
        stagedImageId = null
        renderJob?.cancel()
        renderJob = null
        listener = null
        token = null
        identity = null
    }
}
