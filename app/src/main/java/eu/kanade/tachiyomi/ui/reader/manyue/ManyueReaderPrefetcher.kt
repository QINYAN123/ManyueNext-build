package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.metadata.SourceImageBoundsDecoder
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.buffer
import okio.source
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Drives one lightweight directional cursor for the active chapter. At most one page is loaded or
 * waiting on the AI worker at a time; the cursor moves only after that request reaches a terminal
 * state. Page selection updates the cursor's reading head and priorities without restarting it.
 */
class ManyueReaderPrefetcher(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator = ManyuePrefetchJobCoordinator(scope)
    @Volatile private var lastMangaId: Long? = null
    @Volatile private var lastChapterId: Long? = null

    /**
     * Update the reading head and continue the chapter cursor. Viewers may update the manager
     * first so a just-created visible request is not mistaken for stale background work.
     */
    fun onPageSelected(current: ReaderPage, managerAlreadyUpdated: Boolean = false) {
        val chapterId = current.chapter.chapter.id ?: 0L
        val mangaId = current.chapter.chapter.manga_id ?: 0L
        lastMangaId = mangaId
        lastChapterId = chapterId
        if (!managerAlreadyUpdated) {
            ManyuePrefetchManager.onPageChanged(chapterId, current.index)
        }
        ManyueEnhancementCache.updateReadingPosition(mangaId, chapterId, current.index)

        val mode = ManyueRuntimeState.modeInt
        if (!ManyueEnhancementMode.fromInt(mode).usesAi()) {
            coordinator.cancelAll()
            return
        }

        val pages = current.chapter.pages ?: return
        val generation = ManyueRuntimeState.generation
        coordinator.reconcile(chapterId, current.index, generation, pages.size) { pageIndex, taskGeneration ->
            val page = pages.getOrNull(pageIndex)
                ?: return@reconcile ManyuePrefetchWorkOutcome.FAILED
            coordinator.ensureCurrent(chapterId, taskGeneration, mode)

            val currentMangaId = page.chapter.chapter.manga_id ?: mangaId

            val phaseGuard = ManyuePrefetchPreparationPhaseGuard()
            coordinator.awaitWhileCurrent(
                chapterId,
                taskGeneration,
                mode,
                keepStageCurrent = {
                    phaseGuard.shouldKeepStageCurrent {
                        coordinator.isPreparationCurrent(chapterId, taskGeneration, mode, pageIndex)
                    }
                },
                cancelStageIfStale = {
                    phaseGuard.cancelPreparationIfPending()
                },
            ) {
                if (!coordinator.isPreparationCurrent(chapterId, taskGeneration, mode, pageIndex)) {
                    throw PrefetchWindowMovedCancellationException()
                }
                coordinator.awaitReaderWork(chapterId, taskGeneration, mode)
                val reservation = ManyueEnhancementCache.awaitPrefetchBudget(
                    context = appContext,
                    mangaId = currentMangaId,
                    chapterId = chapterId,
                    pageIndex = pageIndex,
                )
                try {
                    coordinator.ensureCurrent(chapterId, taskGeneration, mode)
                    if (!coordinator.isPreparationCurrent(chapterId, taskGeneration, mode, pageIndex)) {
                        throw PrefetchWindowMovedCancellationException()
                    }
                    coordinator.awaitReaderWork(chapterId, taskGeneration, mode)
                    val token = loadAndEnqueue(
                        page = page,
                        pageIndex = pageIndex,
                        chapterId = chapterId,
                        mangaId = currentMangaId,
                        generation = taskGeneration,
                        mode = mode,
                        phaseGuard = phaseGuard,
                    )
                    if (token != null) {
                        // The returned token may be shared with a visible holder. Cancelling this
                        // waiter must leave that request intact; the next page starts only on terminal.
                        when (coordinator.awaitWorkerCompletion(chapterId, taskGeneration, mode, token)) {
                            ManyueAiUpscaler.STATE_CANCELLED -> ManyuePrefetchWorkOutcome.CANCELLED
                            ManyueAiUpscaler.STATE_READY -> ManyuePrefetchWorkOutcome.COMPLETED
                            else -> ManyuePrefetchWorkOutcome.FAILED
                        }
                    } else {
                        // Missing/error pages are remembered as failures; cursor cancellation is distinct.
                        ManyuePrefetchWorkOutcome.FAILED
                    }
                } finally {
                    // Keep resource ownership inside the polled child so cancellation after budget
                    // acquisition cannot strand an unclosed reservation.
                    reservation.close()
                }
            }
        }
    }

    /** Keeps the full byte array local to this helper so it is out of scope before worker wait. */
    private suspend fun loadAndEnqueue(
        page: ReaderPage,
        pageIndex: Int,
        chapterId: Long,
        mangaId: Long,
        generation: Long,
        mode: Int,
        phaseGuard: ManyuePrefetchPreparationPhaseGuard,
    ): String? {
        val bytes = coordinator.awaitWhileCurrent(chapterId, generation, mode) { loadBytes(page) } ?: return null
        coordinator.ensureCurrent(chapterId, generation, mode)
        val info = withContext(Dispatchers.IO) {
            SourceImageBoundsDecoder.decode(Buffer().write(bytes))
        }
        page.updateSourceImageInfo(info)
        coordinator.ensureCurrent(chapterId, generation, mode)

        val identity = ManyuePageBridge.Identity(
            mangaId = mangaId,
            chapterId = chapterId,
            pageIndex = page.index,
        )
        val readingHead = coordinator.selectedPageIndex(chapterId, generation)
            ?: throw CancellationException("Stale Manyue prefetch cursor")
        val direction = coordinator.selectedDirection(chapterId, generation)
            ?: throw CancellationException("Stale Manyue prefetch direction")
        val priority = ManyueAiSafetyPolicy.continuousPrefetchPriority(pageIndex, readingHead, direction)
        logcat { "Manyue prefetch create page=$pageIndex priority=$priority" }
        return withContext(Dispatchers.IO) {
            coordinator.ensureCurrent(chapterId, generation, mode)
            if (!phaseGuard.beginEnqueue()) throw PrefetchWindowMovedCancellationException()
            try {
                if (!coordinator.isPreparationCurrent(chapterId, generation, mode, pageIndex)) {
                    throw PrefetchWindowMovedCancellationException()
                }
                // The phase CAS excludes a concurrent stale-window cancellation across this
                // synchronous enqueue. Once a token exists, keep the waiter through completion.
                ManyueAiRequestFactory.enqueue(
                    appContext,
                    identity,
                    bytes,
                    priority,
                    mode,
                    generation,
                )
            } finally {
                phaseGuard.resolveEnqueue()
            }
        }
    }

    private suspend fun loadBytes(page: ReaderPage): ByteArray? = withContext(Dispatchers.IO) {
        val loader = page.chapter.pageLoader ?: return@withContext null
        val ready = coroutineScope {
            val loadJob = launch { loader.loadPage(page) }
            try {
                kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                    page.statusFlow
                        .filter { it == Page.State.Ready || it is Page.State.Error }
                        .first()
                }
            } finally {
                loadJob.cancel()
            }
        }
        if (ready != Page.State.Ready) return@withContext null
        val stream = page.stream ?: return@withContext null
        readBoundedPrefetchBytes(stream())
    }

    fun reset() {
        coordinator.cancelAll()
    }

    fun destroy() {
        coordinator.cancelAll()
        scope.cancel()
        ManyueEnhancementCache.updateProtectedPages(emptySet())
        ManyueEnhancementCache.clearReadingPosition(lastMangaId, lastChapterId)
    }
}

internal enum class ManyuePrefetchWorkOutcome {
    COMPLETED,
    FAILED,
    CANCELLED,
}

internal enum class ManyuePrefetchPreparationPhase {
    PREPARING,
    ENQUEUING,
    RESULT_RESOLVED,
    CANCELLED,
}

/** Makes stale-window cancellation and request enqueue entry mutually exclusive. */
internal class ManyuePrefetchPreparationPhaseGuard {
    private val phase = AtomicReference(ManyuePrefetchPreparationPhase.PREPARING)

    val currentPhase: ManyuePrefetchPreparationPhase get() = phase.get()

    fun shouldKeepStageCurrent(isPreparationCurrent: () -> Boolean): Boolean = when (phase.get()) {
        ManyuePrefetchPreparationPhase.PREPARING -> isPreparationCurrent()
        ManyuePrefetchPreparationPhase.ENQUEUING,
        ManyuePrefetchPreparationPhase.RESULT_RESOLVED -> true
        ManyuePrefetchPreparationPhase.CANCELLED -> false
    }

    fun cancelPreparationIfPending(): Boolean = phase.compareAndSet(
        ManyuePrefetchPreparationPhase.PREPARING,
        ManyuePrefetchPreparationPhase.CANCELLED,
    )

    fun beginEnqueue(): Boolean = phase.compareAndSet(
        ManyuePrefetchPreparationPhase.PREPARING,
        ManyuePrefetchPreparationPhase.ENQUEUING,
    )

    fun resolveEnqueue() {
        phase.compareAndSet(
            ManyuePrefetchPreparationPhase.ENQUEUING,
            ManyuePrefetchPreparationPhase.RESULT_RESOLVED,
        )
    }
}

private class PrefetchWindowMovedCancellationException : CancellationException(
    "Manyue prefetch page left the preparation window",
)

/** One serialized cursor over a bounded directional window. */
internal class ManyuePrefetchJobCoordinator(private val scope: CoroutineScope) {

    private data class CursorKey(val chapterId: Long, val generation: Long, val mode: Int)

    @Volatile private var activeKey: CursorKey? = null
    @Volatile private var selectedPage: Int = -1
    @Volatile private var activeJob: Job? = null
    private var pageCount: Int = 0
    private var nextPageIndex: Int = -1
    private var direction = ManyueAiSafetyPolicy.PrefetchDirection.FORWARD
    private val failedPages = mutableSetOf<Int>()
    private var latestWork: (suspend (pageIndex: Int, generation: Long) -> ManyuePrefetchWorkOutcome)? = null

    internal val activeCursorCount: Int
        get() = if (activeJob?.isCompleted == false) 1 else 0

    internal suspend fun awaitCursorIdle() {
        while (true) {
            val job = synchronized(this) { activeJob } ?: return
            job.start()
            job.join()
            if (synchronized(this) { activeJob == null }) return
        }
    }

    fun reconcile(
        chapterId: Long,
        currentPageIndex: Int,
        generation: Long,
        pageCount: Int,
        mode: Int = ManyueRuntimeState.modeInt,
        work: suspend (pageIndex: Int, generation: Long) -> ManyuePrefetchWorkOutcome,
    ) {
        if (pageCount <= 0 || currentPageIndex !in 0 until pageCount) {
            cancelAll()
            return
        }

        val key = CursorKey(chapterId, generation, mode)
        var start: Job? = null
        synchronized(this) {
            if (activeKey != key) {
                activeJob?.cancel()
                activeJob = null
                activeKey = key
                selectedPage = currentPageIndex
                this.pageCount = pageCount
                direction = ManyueAiSafetyPolicy.PrefetchDirection.FORWARD
                nextPageIndex = currentPageIndex + direction.step
                failedPages.clear()
            } else {
                val nextDirection = ManyueAiSafetyPolicy.directionAfterSelection(
                    previousPage = selectedPage,
                    currentPage = currentPageIndex,
                    currentDirection = direction,
                )
                if (nextDirection != direction) {
                    direction = nextDirection
                    nextPageIndex = currentPageIndex + direction.step
                } else if (
                    ManyueAiSafetyPolicy.relativeDistance(nextPageIndex, currentPageIndex, direction) <= 0
                ) {
                    // A jump has moved past the pending target. Do not replay pages behind the head.
                    nextPageIndex = currentPageIndex + direction.step
                }
                selectedPage = currentPageIndex
                this.pageCount = pageCount
            }

            latestWork = work
            start = scheduleCursorLocked(key)
        }
        start?.start()
    }

    private suspend fun runCursor(
        key: CursorKey,
    ) {
        // The holder for the page selected when this cursor starts gets one bounded opportunity
        // to create its high-priority request. Later pages do not each wait on a new 15s gate.
        val initialPage = selectedPageIndex(key.chapterId, key.generation) ?: return
        awaitCurrentDecision(key, initialPage)

        while (true) {
            ensureCurrent(key.chapterId, key.generation, key.mode)
            val pageIndex = claimNextPage(key) ?: return
            val work = synchronized(this) {
                if (activeKey == key) latestWork else null
            } ?: throw CancellationException("Stale Manyue prefetch work")
            var outcome = ManyuePrefetchWorkOutcome.COMPLETED
            try {
                outcome = work(pageIndex, key.generation)
            } catch (error: CancellationException) {
                outcome = ManyuePrefetchWorkOutcome.CANCELLED
                throw error
            } catch (error: Throwable) {
                logcat { "Manyue prefetch failed page=$pageIndex: ${error.javaClass.simpleName}" }
                // A bad source/worker result is remembered for this key. Cancellation is not.
                outcome = ManyuePrefetchWorkOutcome.FAILED
            } finally {
                completePage(key, pageIndex, outcome)
            }
        }
    }

    private suspend fun awaitCurrentDecision(key: CursorKey, pageIndex: Int) = coroutineScope {
        val waiter = async {
            ManyuePrefetchManager.awaitCurrentDecision(key.chapterId, pageIndex, key.generation)
        }
        while (!waiter.isCompleted) {
            ensureCurrent(key.chapterId, key.generation, key.mode)
            delay(STATE_POLL_MS)
        }
        waiter.await()
    }

    private fun claimNextPage(key: CursorKey): Int? = synchronized(this) {
        nextCandidateLocked(key)
    }

    private fun nextCandidateLocked(key: CursorKey): Int? {
        if (activeKey != key) throw CancellationException("Stale Manyue prefetch cursor")
        while (nextPageIndex in 0 until pageCount) {
            val candidate = nextPageIndex
            val head = selectedPage
            val distance = ManyueAiSafetyPolicy.relativeDistance(candidate, head, direction)
            if (distance <= 0) {
                nextPageIndex += direction.step
                continue
            }
            if (distance > ManyueAiSafetyPolicy.PREFETCH_WINDOW_PAGES) return null
            if (candidate in failedPages) {
                nextPageIndex += direction.step
                continue
            }
            return candidate
        }
        return null
    }

    private fun completePage(key: CursorKey, pageIndex: Int, outcome: ManyuePrefetchWorkOutcome) = synchronized(this) {
        if (activeKey == key) {
            if (outcome == ManyuePrefetchWorkOutcome.FAILED) failedPages += pageIndex
            // Successes may be rechecked after a direction change, reusing a cached request when
            // present or rebuilding it after eviction. Cancellation is never remembered as failure.
            if (nextPageIndex == pageIndex) nextPageIndex += direction.step
        }
    }

    private fun scheduleCursorLocked(key: CursorKey): Job? {
        if (
            activeKey != key || !isCurrent(key.chapterId, key.generation, key.mode) ||
            activeJob != null || latestWork == null
        ) {
            return null
        }
        if (nextCandidateLocked(key) == null) return null
        val cursorJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runCursor(key)
            } finally {
                val completedJob = currentCoroutineContext()[Job]
                val followUp = synchronized(this@ManyuePrefetchJobCoordinator) {
                    if (activeJob === completedJob) {
                        activeJob = null
                        if (activeKey == key) scheduleCursorLocked(key) else null
                    } else {
                        null
                    }
                }
                // Reconcile may have updated the head after runCursor observed an empty window.
                // Assigning the successor under the same lock prevents that update being lost.
                followUp?.start()
            }
        }
        activeJob = cursorJob
        return cursorJob
    }

    fun selectedPageIndex(chapterId: Long, generation: Long): Int? = synchronized(this) {
        activeKey?.takeIf { it.chapterId == chapterId && it.generation == generation }?.let { selectedPage }
    }

    fun selectedDirection(chapterId: Long, generation: Long): ManyueAiSafetyPolicy.PrefetchDirection? =
        synchronized(this) {
            activeKey?.takeIf { it.chapterId == chapterId && it.generation == generation }?.let { direction }
        }

    /** A page may keep preparing only while it remains current, visible, or inside the read-ahead window. */
    fun isPreparationCurrent(chapterId: Long, generation: Long, mode: Int, pageIndex: Int): Boolean {
        val key = CursorKey(chapterId, generation, mode)
        if (!isCurrent(chapterId, generation, mode)) return false
        val isVisible = ManyuePrefetchManager.isVisiblePage(chapterId, pageIndex)
        return synchronized(this) {
            if (activeKey != key || !isCurrent(chapterId, generation, mode)) {
                false
            } else {
                pageIndex == selectedPage || isVisible ||
                    ManyueAiSafetyPolicy.relativeDistance(pageIndex, selectedPage, direction) in
                    1..ManyueAiSafetyPolicy.PREFETCH_WINDOW_PAGES
            }
        }
    }

    fun isCurrent(chapterId: Long, generation: Long, mode: Int): Boolean {
        return activeKey == CursorKey(chapterId, generation, mode) &&
            ManyueRuntimeState.generation == generation &&
            ManyueRuntimeState.modeInt == mode &&
            ManyueEnhancementMode.fromInt(mode).usesAi()
    }

    suspend fun ensureCurrent(chapterId: Long, generation: Long, mode: Int) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(chapterId, generation, mode)) {
            throw CancellationException("Stale Manyue prefetch generation")
        }
    }

    /** Wait without pinning a thread, while mode/generation changes remain promptly cancellable. */
    suspend fun awaitReaderWork(chapterId: Long, generation: Long, mode: Int) {
        while (ManyueReaderWorkGate.isBlocked() ||
            (mode == ManyueEnhancementMode.AUTO.value && ManyueRuntimeState.displayWidthPx <= 0)
        ) {
            ensureCurrent(chapterId, generation, mode)
            delay(READER_WORK_GATE_POLL_MS)
        }
        ensureCurrent(chapterId, generation, mode)
    }

    /** Polling races only the waiter; cancelling it never cancels a shared worker request. */
    suspend fun awaitWorkerCompletion(chapterId: Long, generation: Long, mode: Int, token: String): Int =
        awaitWhileCurrent(chapterId, generation, mode) {
            ManyueAiUpscaler.awaitCompletion(token)
        }

    suspend fun <T> awaitWhileCurrent(
        chapterId: Long,
        generation: Long,
        mode: Int,
        keepStageCurrent: () -> Boolean = { true },
        cancelStageIfStale: () -> Boolean = { false },
        stage: suspend () -> T,
    ): T = coroutineScope {
        val waiter = async { stage() }
        while (!waiter.isCompleted) {
            ensureCurrent(chapterId, generation, mode)
            if (!keepStageCurrent() && cancelStageIfStale()) {
                val moved = PrefetchWindowMovedCancellationException()
                waiter.cancel(moved)
                // Finish the child cleanup before runCursor advances to another page.
                waiter.join()
                throw moved
            }
            delay(STATE_POLL_MS)
        }
        waiter.await()
    }

    fun cancelAll() {
        synchronized(this) {
            activeKey = null
            selectedPage = -1
            pageCount = 0
            nextPageIndex = -1
            direction = ManyueAiSafetyPolicy.PrefetchDirection.FORWARD
            failedPages.clear()
            latestWork = null
            activeJob?.cancel()
            activeJob = null
        }
    }

    private companion object {
        const val READER_WORK_GATE_POLL_MS = 80L
        const val STATE_POLL_MS = 100L
    }
}

/** Checks the AI input limit before the request factory allocates another full byte-array copy. */
internal fun readBoundedPrefetchBytes(
    input: InputStream,
    maxBytes: Long = ManyueAiRuntime.MAX_INPUT_BYTES.toLong(),
): ByteArray? = input.source().buffer().use { source ->
    require(maxBytes >= 0)
    if (source.request(maxBytes + 1) && source.buffer.size > maxBytes) {
        ManyueAiRuntime.logcat(LogPriority.WARN) { "Manyue prefetch skipped source larger than $maxBytes bytes" }
        null
    } else {
        source.readByteArray()
    }
}
