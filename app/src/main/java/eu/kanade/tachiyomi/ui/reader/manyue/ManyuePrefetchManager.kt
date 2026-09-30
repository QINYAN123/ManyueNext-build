package eu.kanade.tachiyomi.ui.reader.manyue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks active page requests, protects visible results, and adjusts priorities as the reader
 * moves. The prefetch cursor may work through the whole chapter; completed request tokens are
 * pruned here while their disk cache entries remain reusable.
 */
object ManyuePrefetchManager {

    private const val CURRENT_DECISION_TIMEOUT_MS = 15_000L
    private const val MAX_REMEMBERED_CURRENT_DECISIONS = 32

    private data class PageKey(val chapterId: Long, val pageIndex: Int)

    private var visiblePages = emptySet<PageKey>()
    private val tokens = java.util.concurrent.ConcurrentHashMap<PageKey, MutableSet<String>>()
    private val currentDecisions = java.util.concurrent.ConcurrentHashMap<PageKey, CompletableDeferred<Unit>>()
    private val rememberedCurrentDecisions = linkedSetOf<PageKey>()
    @Volatile private var currentChapterId: Long = Long.MIN_VALUE
    @Volatile private var currentPage: Int = -1

    fun reset() {
        synchronized(this) {
            tokens.values.flatten().forEach(ManyueAiUpscaler::cancel)
            tokens.clear()
            visiblePages = emptySet()
            currentDecisions.values.forEach { it.complete(Unit) }
            currentDecisions.clear()
            rememberedCurrentDecisions.clear()
            currentChapterId = Long.MIN_VALUE
            currentPage = -1
        }
    }

    /** Compatibility entry for JVM tests and callers that do not yet have a chapter id. */
    fun register(pageIndex: Int, token: String) {
        register(currentChapterId, pageIndex, token)
    }

    fun register(chapterId: Long, pageIndex: Int, token: String) {
        synchronized(this) {
            val key = PageKey(chapterId, pageIndex)
            tokens.getOrPut(key) { linkedSetOf() }.add(token)
            if (chapterId == currentChapterId && pageIndex == currentPage) rememberCurrentDecision(key)
            applyPriorities()
            if (chapterId == currentChapterId && pageIndex == currentPage) completeCurrentDecision(key)
        }
    }

    /** Compatibility entry for tests. */
    fun onPageChanged(current: Int) {
        onPageChanged(currentChapterId, current)
    }

    fun onPageChanged(chapterId: Long, current: Int, visible: Set<Pair<Long, Int>> = emptySet()) {
        synchronized(this) {
            visiblePages = visible.mapTo(linkedSetOf()) { PageKey(it.first, it.second) }
                .apply { add(PageKey(chapterId, current)) }
            if (currentChapterId != Long.MIN_VALUE && currentChapterId != chapterId) {
                tokens.entries.filter { it.key !in visiblePages }.forEach { (key, pageTokens) ->
                    pageTokens.forEach(ManyueAiUpscaler::cancel)
                    tokens.remove(key, pageTokens)
                }
                currentDecisions.values.forEach { it.complete(Unit) }
                currentDecisions.clear()
            }
            currentChapterId = chapterId
            currentPage = current
            val selectedKey = PageKey(chapterId, current)
            currentDecisions.keys.filter { it != selectedKey }
                .forEach { key -> currentDecisions.remove(key)?.complete(Unit) }
            if (selectedKey !in rememberedCurrentDecisions) {
                currentDecisions.putIfAbsent(selectedKey, CompletableDeferred())
            }
            // A retained or already-bound holder can have registered before selection changed.
            if (tokens[selectedKey]?.isNotEmpty() == true) {
                rememberCurrentDecision(selectedKey)
                completeCurrentDecision(selectedKey)
            } else if (selectedKey in rememberedCurrentDecisions) {
                completeCurrentDecision(selectedKey)
            }
            applyPriorities()
            updateCacheProtection()
        }
    }

    /**
     * Reconcile after ViewerChapters replaces the adapter's chapter window. Requests for pages
     * that remain visible (including a retained next-chapter page) stay active until visibility
     * moves away from them.
     */
    fun onChapterChanged(chapterId: Long, current: Int, visible: Set<Pair<Long, Int>>) {
        // RecyclerView can briefly report no attached/visible positions while applying a chapter
        // diff. The selected page is still part of the new window and must survive that handoff.
        onPageChanged(chapterId, current, visible + (chapterId to current))
        // A retained holder may already have registered its current request and released the
        // old decision gate before the adapter diff. Do not make the next-page cursor wait again.
        synchronized(this) {
            val key = PageKey(chapterId, current)
            if (tokens[key]?.isNotEmpty() == true || key in rememberedCurrentDecisions) {
                rememberCurrentDecision(key)
                completeCurrentDecision(key)
            }
        }
    }

    /** The selected strip page can be the bottom item; earlier visible strips still need AI. */
    fun updateVisiblePages(visible: Set<Pair<Long, Int>>) {
        synchronized(this) {
            val next = visible.map { PageKey(it.first, it.second) }.toSet()
            if (next == visiblePages) return
            visiblePages = next
            applyPriorities()
            updateCacheProtection()
        }
    }

    /** Release the selected-page gate after its visible holder has decided its AI request. */
    fun releaseCurrent(chapterId: Long, pageIndex: Int) {
        synchronized(this) {
            val key = PageKey(chapterId, pageIndex)
            rememberCurrentDecision(key)
            completeCurrentDecision(key)
        }
    }

    /** Wait briefly for the visible request to be created before read-ahead enqueues work. */
    suspend fun awaitCurrentDecision(chapterId: Long, pageIndex: Int) {
        val gate = currentDecisions[PageKey(chapterId, pageIndex)] ?: return
        withTimeoutOrNull(CURRENT_DECISION_TIMEOUT_MS) { gate.await() }
    }

    private fun applyPriorities() {
        if (currentPage < 0) return
        val iterator = tokens.entries.iterator()
        while (iterator.hasNext()) {
            val (key, pageTokens) = iterator.next()
            // This removes only manager bookkeeping. Ready, failed, and cancelled disk results
            // stay in the upscaler/cache for reuse and are never cancelled by this prune.
            pageTokens.removeAll(ManyueAiUpscaler::isFinished)
            if (pageTokens.isEmpty()) {
                tokens.remove(key, pageTokens)
                continue
            }

            val distance = key.pageIndex - currentPage
            when {
                key in visiblePages -> pageTokens.forEach { ManyueAiUpscaler.updatePriority(it, 100) }
                key.chapterId != currentChapterId -> {
                    pageTokens.forEach(ManyueAiUpscaler::cancel)
                    tokens.remove(key, pageTokens)
                }
                distance >= 0 -> {
                    val priority = ManyueAiSafetyPolicy.continuousPrefetchPriority(key.pageIndex, currentPage)
                    pageTokens.forEach { ManyueAiUpscaler.updatePriority(it, priority) }
                }
                distance >= -ManyueAiSafetyPolicy.PREFETCH_LOOK_BACK_PAGES -> {
                    val priority = ManyueAiSafetyPolicy.continuousPrefetchPriority(key.pageIndex, currentPage)
                    pageTokens.forEach { ManyueAiUpscaler.updatePriority(it, priority) }
                }
                distance >= -ManyueAiSafetyPolicy.ACTIVE_COMPLETION_LOOK_BACK_PAGES &&
                    pageTokens.any(ManyueAiUpscaler::isProcessing) -> {
                    // Let a request that has already reached the worker finish. This preserves a
                    // nearby result through fast scrolling without keeping queued stale work.
                    pageTokens.forEach { ManyueAiUpscaler.updatePriority(it, 1) }
                }
                else -> {
                    pageTokens.forEach(ManyueAiUpscaler::cancel)
                    tokens.remove(key, pageTokens)
                }
            }
        }
    }

    private fun updateCacheProtection() {
        ManyueEnhancementCache.updateProtectedPages(
            visiblePages.mapTo(linkedSetOf()) { it.chapterId to it.pageIndex },
        )
    }

    private fun rememberCurrentDecision(key: PageKey) {
        rememberedCurrentDecisions.remove(key)
        rememberedCurrentDecisions.add(key)
        while (rememberedCurrentDecisions.size > MAX_REMEMBERED_CURRENT_DECISIONS) {
            val oldest = rememberedCurrentDecisions.first()
            rememberedCurrentDecisions.remove(oldest)
        }
    }

    private fun completeCurrentDecision(key: PageKey) {
        currentDecisions.remove(key)?.complete(Unit)
    }
}
