package eu.kanade.tachiyomi.ui.reader.manyue

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tracks active page requests, protects visible results, and adjusts priorities as the reader
 * moves. New prefetch requests stay inside the current directional window; completed request
 * tokens are pruned here while their disk cache entries remain reusable.
 */
object ManyuePrefetchManager {

    private const val CURRENT_DECISION_TIMEOUT_MS = 15_000L
    private const val MAX_REMEMBERED_CURRENT_DECISIONS = 32

    private data class PageKey(val chapterId: Long, val pageIndex: Int)
    // Worker requests are keyed by page, but current-page decisions belong to a settings/session
    // generation so a late holder callback cannot release a newer request for the same page.
    private data class DecisionKey(val chapterId: Long, val pageIndex: Int, val generation: Long)
    private data class DirectionKey(val chapterId: Long, val generation: Long, val mode: Int)

    private var visiblePages = emptySet<PageKey>()
    private val tokens = java.util.concurrent.ConcurrentHashMap<PageKey, MutableSet<String>>()
    private val currentDecisions = java.util.concurrent.ConcurrentHashMap<DecisionKey, CompletableDeferred<Unit>>()
    private val rememberedCurrentDecisions = linkedSetOf<DecisionKey>()

    @Volatile private var currentChapterId: Long = Long.MIN_VALUE

    @Volatile private var currentPage: Int = -1

    private var directionKey: DirectionKey? = null

    private var prefetchDirection = ManyueAiSafetyPolicy.PrefetchDirection.FORWARD
    private val displayPreparationSlot = ManyueDisplayPreparationSlot()

    internal fun isNextDisplayPage(chapterId: Long, pageIndex: Int): Boolean = synchronized(this) {
        if (chapterId != currentChapterId || currentPage < 0) return@synchronized false
        // Short strips can put several pages on screen at once. Prepare the first page
        // beyond that visible frontier, rather than treating an already visible N+1 as prewarm.
        val frontier = visiblePages.asSequence().filter { it.chapterId == chapterId }
            .map { ManyueAiSafetyPolicy.relativeDistance(it.pageIndex, currentPage, prefetchDirection) }
            .maxOrNull()?.coerceAtLeast(0) ?: 0
        val distance = ManyueAiSafetyPolicy.relativeDistance(pageIndex, currentPage, prefetchDirection)
        distance in 1..ManyueAiSafetyPolicy.PREFETCH_WINDOW_PAGES && distance == frontier + 1
    }

    internal fun acquireDisplayPreparation(chapterId: Long, pageIndex: Int, owner: Any): Boolean =
        synchronized(this) {
            displayPreparationSlot.acquire(owner, isNextDisplayPage(chapterId, pageIndex))
        }

    internal fun ownsDisplayPreparation(chapterId: Long, pageIndex: Int, owner: Any): Boolean =
        synchronized(this) {
            displayPreparationSlot.owns(owner) && isNextDisplayPage(chapterId, pageIndex)
        }

    internal fun releaseDisplayPreparation(owner: Any) = synchronized(this) {
        displayPreparationSlot.release(owner)
    }

    internal fun isVisiblePage(chapterId: Long, pageIndex: Int): Boolean = synchronized(this) {
        PageKey(chapterId, pageIndex) in visiblePages ||
            (chapterId == currentChapterId && pageIndex == currentPage)
    }

    internal fun isSelectedPage(chapterId: Long, pageIndex: Int): Boolean = synchronized(this) {
        chapterId == currentChapterId && pageIndex == currentPage
    }

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
            directionKey = null
            prefetchDirection = ManyueAiSafetyPolicy.PrefetchDirection.FORWARD
            displayPreparationSlot.reset()
            ManyueReadAheadScheduler.policy.reset()
        }
    }

    /** Compatibility entry for JVM tests and callers that do not yet have a chapter id. */
    fun register(pageIndex: Int, token: String) {
        register(currentChapterId, pageIndex, token)
    }

    fun register(
        chapterId: Long,
        pageIndex: Int,
        token: String,
        decisionReady: Boolean = true,
        generation: Long = ManyueRuntimeState.generation,
    ) {
        synchronized(this) {
            if (generation != ManyueRuntimeState.generation) return
            val key = PageKey(chapterId, pageIndex)
            val decisionKey = DecisionKey(chapterId, pageIndex, generation)
            tokens.getOrPut(key) { linkedSetOf() }.add(token)
            if (decisionReady) rememberCurrentDecision(decisionKey)
            applyPriorities()
            if (decisionReady && chapterId == currentChapterId && pageIndex == currentPage) {
                completeCurrentDecision(decisionKey)
            }
        }
    }

    /** Compatibility entry for tests. */
    fun onPageChanged(current: Int) {
        onPageChanged(currentChapterId, current)
    }

    fun onPageChanged(chapterId: Long, current: Int, visible: Set<Pair<Long, Int>> = emptySet()) {
        synchronized(this) {
            val nextDirectionKey = DirectionKey(
                chapterId = chapterId,
                generation = ManyueRuntimeState.generation,
                mode = ManyueRuntimeState.modeInt,
            )
            if (directionKey == nextDirectionKey) {
                prefetchDirection = ManyueAiSafetyPolicy.directionAfterSelection(
                    previousPage = currentPage,
                    currentPage = current,
                    currentDirection = prefetchDirection,
                )
            } else {
                directionKey = nextDirectionKey
                prefetchDirection = ManyueAiSafetyPolicy.PrefetchDirection.FORWARD
            }

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
            ManyueReadAheadScheduler.policy.selected(chapterId, current, nextDirectionKey.generation)
            ManyueReadAheadScheduler.policy.visible(
                chapterId, visiblePages.filter { it.chapterId == chapterId }.mapTo(linkedSetOf()) { it.pageIndex },
                nextDirectionKey.generation,
            )
            val selectedKey = DecisionKey(chapterId, current, nextDirectionKey.generation)
            currentDecisions.keys.filter { it != selectedKey }
                .forEach { key -> currentDecisions.remove(key)?.complete(Unit) }
            if (selectedKey !in rememberedCurrentDecisions) {
                currentDecisions.putIfAbsent(selectedKey, CompletableDeferred())
            }
            // A retained or already-bound holder can have registered before selection changed.
            if (tokens[PageKey(chapterId, current)]?.any(ManyueAiUpscaler::hasQueuedCurrentDecision) == true) {
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
            val key = DecisionKey(chapterId, current, ManyueRuntimeState.generation)
            if (tokens[PageKey(chapterId, current)]?.any(ManyueAiUpscaler::hasQueuedCurrentDecision) == true ||
                key in rememberedCurrentDecisions
            ) {
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
            ManyueReadAheadScheduler.policy.visible(
                currentChapterId, next.filter { it.chapterId == currentChapterId }.mapTo(linkedSetOf()) { it.pageIndex },
                ManyueRuntimeState.generation,
            )
            applyPriorities()
            updateCacheProtection()
        }
    }

    /** Release the selected-page gate after its visible holder has decided its AI request. */
    fun releaseCurrent(
        chapterId: Long,
        pageIndex: Int,
        generation: Long = ManyueRuntimeState.generation,
    ) {
        synchronized(this) {
            if (generation != ManyueRuntimeState.generation) return
            val key = DecisionKey(chapterId, pageIndex, generation)
            rememberCurrentDecision(key)
            completeCurrentDecision(key)
        }
    }

    /** Wait briefly for the visible request to be created before read-ahead enqueues work. */
    suspend fun awaitCurrentDecision(
        chapterId: Long,
        pageIndex: Int,
        generation: Long = ManyueRuntimeState.generation,
    ) {
        val gate = synchronized(this) {
            if (generation != ManyueRuntimeState.generation) return
            val key = DecisionKey(chapterId, pageIndex, generation)
            if (key in rememberedCurrentDecisions) return
            // Reset/reconcile can race the cursor start. Install only this page's gate; selection
            // and visibility remain owned by onPageChanged/updateVisiblePages.
            currentDecisions.getOrPut(key) { CompletableDeferred() }
        }
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

            val candidatePriority = ManyueAiSafetyPolicy.prefetchPriority(
                pageIndex = key.pageIndex,
                currentIndex = currentPage,
                direction = prefetchDirection,
            )
            val managedPriority = ManyueAiSafetyPolicy.managedRequestPriority(
                isCurrent = key.chapterId == currentChapterId && key.pageIndex == currentPage,
                isVisible = key in visiblePages,
                directionalPriority = candidatePriority.takeIf { key.chapterId == currentChapterId },
                // Quality now cancels at safe GPU checkpoints without reloading the model.
                // Fully passed pages do not consume another five-page look-back allowance.
                keepActive = false,
            )
            when {
                managedPriority != null -> pageTokens.forEach { ManyueAiUpscaler.updatePriority(it, managedPriority) }
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

    private fun rememberCurrentDecision(key: DecisionKey) {
        rememberedCurrentDecisions.remove(key)
        rememberedCurrentDecisions.add(key)
        while (rememberedCurrentDecisions.size > MAX_REMEMBERED_CURRENT_DECISIONS) {
            val oldest = rememberedCurrentDecisions.first()
            rememberedCurrentDecisions.remove(oldest)
        }
    }

    private fun completeCurrentDecision(key: DecisionKey) {
        currentDecisions.remove(key)?.complete(Unit)
    }
}
