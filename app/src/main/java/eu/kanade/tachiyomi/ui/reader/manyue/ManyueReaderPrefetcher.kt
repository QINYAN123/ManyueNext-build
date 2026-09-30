package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.metadata.SourceImageBoundsDecoder
import java.io.InputStream
import java.util.concurrent.CancellationException
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
 * Drives one lightweight forward cursor for the active chapter. At most one page is loaded or
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
        if (mode != ManyueEnhancementMode.AI_2X.value &&
            mode != ManyueEnhancementMode.AI_2X_CLASSIC.value
        ) {
            coordinator.cancelAll()
            return
        }

        val pages = current.chapter.pages ?: return
        val generation = ManyueRuntimeState.generation
        coordinator.reconcile(chapterId, current.index, generation, pages.size) { pageIndex, taskGeneration ->
            val page = pages.getOrNull(pageIndex) ?: return@reconcile
            coordinator.ensureCurrent(chapterId, taskGeneration, mode)

            val currentMangaId = page.chapter.chapter.manga_id ?: mangaId

            coordinator.awaitReaderWork(chapterId, taskGeneration, mode)
            coordinator.awaitWhileCurrent(chapterId, taskGeneration, mode) {
                val reservation = ManyueEnhancementCache.awaitPrefetchBudget(
                    context = appContext,
                    mangaId = currentMangaId,
                    chapterId = chapterId,
                    pageIndex = pageIndex,
                )
                try {
                    coordinator.ensureCurrent(chapterId, taskGeneration, mode)
                    coordinator.awaitReaderWork(chapterId, taskGeneration, mode)
                    val token = loadAndEnqueue(
                        page = page,
                        pageIndex = pageIndex,
                        chapterId = chapterId,
                        mangaId = currentMangaId,
                        generation = taskGeneration,
                        mode = mode,
                    )
                    if (token != null) {
                        // The returned token may be shared with a visible holder. Cancelling this
                        // waiter must leave that request intact; the next page starts only on terminal.
                        coordinator.awaitWorkerCompletion(chapterId, taskGeneration, mode, token)
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
        val priority = ManyueAiSafetyPolicy.continuousPrefetchPriority(pageIndex, readingHead)
        logcat { "Manyue prefetch create page=$pageIndex priority=$priority" }
        return withContext(Dispatchers.IO) {
            coordinator.ensureCurrent(chapterId, generation, mode)
            ManyueAiRequestFactory.enqueue(
                appContext,
                identity,
                bytes,
                priority,
                mode,
                generation,
            )
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

/** A single cancellable chapter cursor; scrolling changes its head, never its active work job. */
internal class ManyuePrefetchJobCoordinator(private val scope: CoroutineScope) {

    private data class CursorKey(val chapterId: Long, val generation: Long, val mode: Int)

    @Volatile private var activeKey: CursorKey? = null
    @Volatile private var selectedPage: Int = -1
    @Volatile private var activeJob: Job? = null
    private var pageCount: Int = 0
    private var nextPageIndex: Int = -1

    internal val activeCursorCount: Int
        get() = if (activeJob?.isActive == true) 1 else 0

    internal suspend fun awaitCursorIdle() {
        activeJob?.join()
    }

    fun reconcile(
        chapterId: Long,
        currentPageIndex: Int,
        generation: Long,
        pageCount: Int,
        mode: Int = ManyueRuntimeState.modeInt,
        work: suspend (pageIndex: Int, generation: Long) -> Unit,
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
                nextPageIndex = currentPageIndex + 1
            } else {
                selectedPage = currentPageIndex
                this.pageCount = pageCount
            }

            if (activeJob?.isActive != true && nextPageIndex < this.pageCount) {
                val cursorJob = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        runCursor(key, work)
                    } finally {
                        val completedJob = currentCoroutineContext()[Job]
                        synchronized(this@ManyuePrefetchJobCoordinator) {
                            if (activeJob === completedJob) activeJob = null
                        }
                    }
                }
                activeJob = cursorJob
                start = cursorJob
            }
        }
        start?.start()
    }

    private suspend fun runCursor(
        key: CursorKey,
        work: suspend (pageIndex: Int, generation: Long) -> Unit,
    ) {
        // The holder for the page selected when this cursor starts gets one bounded opportunity
        // to create its high-priority request. Later pages do not each wait on a new 15s gate.
        val initialPage = selectedPageIndex(key.chapterId, key.generation) ?: return
        awaitCurrentDecision(key, initialPage)

        while (true) {
            ensureCurrent(key.chapterId, key.generation, key.mode)
            val pageIndex = claimNextPage(key) ?: return
            var advance = false
            try {
                work(pageIndex, key.generation)
                advance = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logcat { "Manyue prefetch failed page=$pageIndex: ${error.javaClass.simpleName}" }
                advance = true
            } finally {
                if (advance) completePage(key, pageIndex)
            }
        }
    }

    private suspend fun awaitCurrentDecision(key: CursorKey, pageIndex: Int) = coroutineScope {
        val waiter = async { ManyuePrefetchManager.awaitCurrentDecision(key.chapterId, pageIndex) }
        while (!waiter.isCompleted) {
            ensureCurrent(key.chapterId, key.generation, key.mode)
            delay(STATE_POLL_MS)
        }
        waiter.await()
    }

    private fun claimNextPage(key: CursorKey): Int? = synchronized(this) {
        if (activeKey != key) throw CancellationException("Stale Manyue prefetch cursor")
        while (nextPageIndex < pageCount) {
            val candidate = nextPageIndex
            val head = selectedPage
            if (candidate == head || candidate < head - ManyueAiSafetyPolicy.PREFETCH_LOOK_BACK_PAGES) {
                // A visible page belongs to its holder. Old pages outside the small revisit
                // margin are already behind the reader and must not delay the forward cursor.
                nextPageIndex++
                continue
            }
            return@synchronized candidate
        }
        null
    }

    private fun completePage(key: CursorKey, pageIndex: Int) = synchronized(this) {
        if (activeKey == key && nextPageIndex == pageIndex) nextPageIndex++
    }

    fun selectedPageIndex(chapterId: Long, generation: Long): Int? = synchronized(this) {
        activeKey?.takeIf { it.chapterId == chapterId && it.generation == generation }?.let { selectedPage }
    }

    fun isCurrent(chapterId: Long, generation: Long, mode: Int): Boolean {
        return activeKey == CursorKey(chapterId, generation, mode) &&
            ManyueRuntimeState.generation == generation &&
            ManyueRuntimeState.modeInt == mode &&
            (mode == ManyueEnhancementMode.AI_2X.value || mode == ManyueEnhancementMode.AI_2X_CLASSIC.value)
    }

    suspend fun ensureCurrent(chapterId: Long, generation: Long, mode: Int) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(chapterId, generation, mode)) {
            throw CancellationException("Stale Manyue prefetch generation")
        }
    }

    /** Wait without pinning a thread, while mode/generation changes remain promptly cancellable. */
    suspend fun awaitReaderWork(chapterId: Long, generation: Long, mode: Int) {
        while (ManyueReaderWorkGate.isBlocked()) {
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
        stage: suspend () -> T,
    ): T = coroutineScope {
        val waiter = async { stage() }
        while (!waiter.isCompleted) {
            ensureCurrent(chapterId, generation, mode)
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
