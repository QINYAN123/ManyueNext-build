package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueReaderPrefetcher
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePrefetchManager
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueRuntimeState
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueReadingCoverage
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceDiagnostics
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePerformanceRecorder
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementState
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueGpuDisplayStatus
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueWebGpuDetailFilter
import androidx.lifecycle.Lifecycle
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.launch
import kotlin.math.max

class WebGpuViewerContinuous(activity: ReaderActivity, val useGap: Boolean = false) :
    WebGpuViewer(activity, isReversed = false, isVertical = true, pager = ImageViewContinuous(activity)) {

    override val isContinuous: Boolean = true

    // How many pages the viewport shows depends on the zoom, and a page on screen has to be
    // decoded rather than merely reserved - so the window follows what the last frame reached.
    override val preloadAhead get() = max(3, state.pagesBelow + 2)
    override val preloadBehind get() = max(2, state.pagesAbove + 1)

    override val cacheSize get() = 2 + 2 * max(4, state.pagesAbove + state.pagesBelow)

    private val state get() = (pager as ImageViewContinuous).state
    private val manyuePrefetcher = ManyueReaderPrefetcher(activity)
    private var manyueHead: Pair<Long, Int>? = null
    private var manyueGeneration = -1L
    @Volatile private var visibleManyueOwners = emptySet<ViewerReaderPage>()
    private val decodeDemand = WebGpuDecodeDemand<ViewerPage>(
        preloadWindow = ::preloadPages,
        prioritizeVisible = { preloadPage(it, prioritize = true) },
    )

    override fun isPageVisible(page: ViewerReaderPage): Boolean = page in visibleManyueOwners || super.isPageVisible(page)
    private val detailFilter = ManyueWebGpuDetailFilter().apply { enabled = false }
    private var detailRequested = false
    private var detailStrength = 25

    internal fun configureDetail(enabled: Boolean, strength: Int) {
        if (detailRequested != enabled || detailStrength != strength.coerceIn(0, 100)) {
            detailFilter.retryAfterSettingsChange()
        }
        detailRequested = enabled
        detailStrength = strength.coerceIn(0, 100)
        detailFilter.strength = detailStrength
        detailFilter.enabled = enabled
        activity.updateWebGpuDetailStatus(
            this,
            ManyueGpuDisplayStatus(
                when {
                    !enabled -> ManyueGpuDisplayStatus.State.OFF
                    detailStrength == 0 -> ManyueGpuDisplayStatus.State.ZERO_STRENGTH
                    else -> ManyueGpuDisplayStatus.State.WAITING
                },
                detailStrength,
            ),
        )
    }

    fun comfortScaleForPersistence(): Float? = state.scale.takeIf { it.isFinite() && it >= .5f && it < 1f }

    fun restoreComfortScale(scale: Float) {
        val safe = scale.takeIf { it.isFinite() && it >= .5f && it < 1f } ?: 1f
        state.homeScale = safe
        state.scale = safe
        state.offsetX = 0f
        state.invalidate()
    }

    fun resetComfortScale() = restoreComfortScale(1f)

    internal fun benchmarkSubmittedResult(pageIndex: Int): ManyueReadingCoverage.Result? =
        visibleManyueOwners.firstOrNull { it.page.index == pageIndex }?.let { owner ->
            owner.aiResult?.takeIf { owner.submittedAiToken == it.token && owner.aiBridge.current(it) }
        }

    init {
        state.backgroundColor = readerBackgroundColor()
        state.filters.filters = state.filters.filters + detailFilter
        detailFilter.onFailure = {
            scope.launch {
                activity.updateWebGpuDetailStatus(
                    this@WebGpuViewerContinuous,
                    ManyueGpuDisplayStatus(ManyueGpuDisplayStatus.State.FAILED, detailStrength),
                )
                state.invalidate()
            }
        }

        state.onVisibleImages = { visible ->
            val active = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && pager.isShown
            val entries = if (active) visible.mapNotNull { image ->
                (viewerPageFor(image.page) as? ViewerReaderPage)?.let { it to image }
            } else emptyList()
            val owners = entries.mapTo(linkedSetOf()) { it.first }
            val pageKeys = owners.mapTo(linkedSetOf()) { (it.page.chapter.chapter.id ?: 0L) to it.page.index }
            val head = (currentPage as? ViewerReaderPage)?.page
            val headKey = head?.let { (it.chapter.chapter.id ?: 0L) to it.index }
            if (head != null && (headKey != manyueHead || manyueGeneration != ManyueRuntimeState.generation)) {
                manyueHead = headKey
                manyueGeneration = ManyueRuntimeState.generation
                ManyuePrefetchManager.onPageChanged(headKey!!.first, head.index, pageKeys)
                // The AI head follows the viewport. This does not change read-through progress.
                manyuePrefetcher.onPageSelected(head, managerAlreadyUpdated = true)
            } else {
                ManyuePrefetchManager.updateVisiblePages(pageKeys)
            }
            (visibleManyueOwners - owners).forEach {
                it.aiBridge.updateVisibility(false)
                ManyueReadingCoverage.hidden(it)
            }
            visibleManyueOwners = owners
            if (active) {
                decodeDemand.update(
                    currentPage, state.width, state.height, state.pagesAbove, state.pagesBelow,
                    entries.map { it.first },
                )
            }
            entries.forEach { (owner, image) ->
                owner.aiBridge.updateVisibility(true)
                ManyueReadingCoverage.exposure(
                    owner, owner.aiBridge.source,
                    (image.viewportFraction * 1000).toInt(), (image.pageFraction * 1000).toInt(),
                    image.page.height.toFloat() * state.width / image.page.width.coerceAtLeast(1) * state.scale <= state.height,
                )
            }
        }

        state.onSubmittedImages = { submitted, _ ->
            val effectStrength = if (detailFilter.active) detailFilter.submittedStrength else 0
            // GPU feedback arrives after submission. Resolve the same image object on main:
            // a newer page swap must never borrow an older frame's successful submission.
            scope.launch {
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || !pager.isShown) return@launch
                if (detailRequested && detailStrength > 0 && effectStrength == detailStrength) {
                    activity.updateWebGpuDetailStatus(
                        this@WebGpuViewerContinuous,
                        ManyueGpuDisplayStatus(ManyueGpuDisplayStatus.State.ACTIVE, effectStrength),
                    )
                }
                submitted.forEach { image ->
                    val owner = viewerPageFor(image.page) as? ViewerReaderPage ?: return@forEach
                    if (owner !in visibleManyueOwners) return@forEach
                    ManyueReadingCoverage.frameSubmitted(owner, ManyueReadingCoverage.DrawBackend.WEBGPU_SUBMISSION)
                    if (!image.contentSubmitted) return@forEach
                    val result = owner.aiResult?.takeIf { owner.aiBridge.current(it) }
                    ManyueReadingCoverage.drawn(
                        owner, owner.aiBridge.source, result, ManyueReadingCoverage.DrawBackend.WEBGPU_SUBMISSION,
                    )
                    if (result != null && owner.submittedAiToken != result.token) {
                        owner.submittedAiToken = result.token
                        owner.page.updateEnhancementState(ManyueEnhancementState.AI_READY)
                        owner.page.updateEnhancedImageInfo(result.outputWidth, result.outputHeight)
                        ManyuePerformanceDiagnostics.event(ManyuePerformanceRecorder.Stage.DISPLAYED, owner.page.index, result.token)
                    }
                }
            }
        }

        state.onViewport = { readThrough ->
            val pageChanged = readThrough !== lastReadThrough
            if (pageChanged) {
                lastReadThrough = readThrough
                readThrough?.let { select(it) }
            }

            val page0 = state.getPage(0)
            val edge = (page0 is TransitionPage && page0.prevChapter == null) ||
                (readThrough is TransitionPage && readThrough.nextChapter == null)

            val first = wasAtEdge == null
            val edgeChanged = edge != wasAtEdge
            wasAtEdge = edge
            if (edge) {
                if (edgeChanged) scope.launch { activity.showMenu() }
            } else if (!first && (edgeChanged || pageChanged) && !activity.isScrollingThroughPages) {
                scope.launch { activity.hideMenu() }
            }
        }
    }

    /** Null until the first frame, so opening mid-document neither shows nor hides. */
    @Volatile
    private var wasAtEdge: Boolean? = null

    @Volatile
    private var lastReadThrough: ImagePage? = null

    /**
     * Mark [imagePage] read - its bottom has cleared the viewport, which is later than
     * onPageChange's "top arrived" and the reason this mode selects from here: scrolling up to
     * reveal the page above would otherwise walk progress back past pages still on screen.
     */
    private fun select(imagePage: ImagePage) {
        val page = readerPageFor(imagePage) ?: return
        scope.launch { activity.onPageSelected(page) }
    }

    private fun readerPageFor(imagePage: ImagePage): ReaderPage? {
        // Off the page itself: a transition page is never currentPage (the scroll walk stops
        // before it), so it is the first thing a preload evicts.
        if (imagePage is TransitionPage) return imagePage.prevChapter?.pages?.lastOrNull()

        // Only an image page needs the lookup - nothing on it names its ReaderPage. Evicted
        // means scrolled well clear of, so there is no progress left to report.
        return (viewerPageFor(imagePage) as? ViewerReaderPage)?.page
    }

    override fun destroy() {
        detailFilter.onFailure = null
        visibleManyueOwners.forEach { ManyueReadingCoverage.hidden(it) }
        visibleManyueOwners = emptySet()
        decodeDemand.reset()
        state.onVisibleImages = null
        state.onSubmittedImages = null
        manyuePrefetcher.destroy()
        state.onViewport = null
        lastReadThrough = null
        wasAtEdge = null
        super.destroy()
    }

    override fun refreshManyue() {
        decodeDemand.reset()
        manyuePrefetcher.reset()
        manyueHead = null
        manyueGeneration = -1L
        super.refreshManyue()
    }

    override fun refreshReadingCoverage() {
        state.invalidate()
    }

    override fun reapplyManyueWidth() {
        // Surface resize recalculates document-space geometry; no image cropping or reload.
        state.invalidate()
    }

    private fun scrollByHalfPage(direction: Int) {
        state.animateScroll(direction * state.height / 2f)
    }

    override fun moveRight() = scrollByHalfPage(1)

    override fun moveLeft() = scrollByHalfPage(-1)

    override fun moveToPage(page: ReaderPage) {
        super.moveToPage(page)
        wasAtEdge = null
        lastReadThrough = null
        // Also for a jump to the page already showing, which turns nothing to slide in.
        state.resetScroll()
    }

    override fun animateTurn(direction: Int, fromSpread: ImagePage) {
        state.animateSlideIn(direction)
    }
}
