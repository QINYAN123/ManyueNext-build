package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import ca.mpreg.webgpuviewer.ImageViewContinuous
import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
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
    private val selectionPolicy = WebGpuProgressSelectionPolicy()
    private var manyueHead: Pair<Long, Int>? = null
    private var manyueGeneration = -1L
    @Volatile private var visibleManyueOwners = emptySet<ViewerReaderPage>()
    private val decodeDemand = WebGpuDecodeDemand<ViewerPage>(
        preloadWindow = ::preloadPages,
        prioritizeVisible = { preloadPage(it, prioritize = true) },
    )
    private val nextPreparation = WebGpuNextPagePreparation<ViewerReaderPage>(
        neighbors = { listOfNotNull(it.prev as? ViewerReaderPage, it.next as? ViewerReaderPage) },
        eligible = { ManyuePrefetchManager.isNextDisplayPage(it.page.chapter.chapter.id ?: 0L, it.page.index) },
        prepare = { owner, visible, allowPreparation ->
            owner.aiBridge.updateVisibility(visible, prepareNext = allowPreparation)
        },
    )
    // Same-APK fast-reading trial missed its prospective benefit gate. Keep benchmark access
    // for further study, without enabling an unaccepted extra GPU workload by default.
    internal var nextPreparationEnabled = false
        private set

    internal fun benchmarkPresentationChanged() {
        state.invalidate()
    }

    internal fun benchmarkPrewarm(enabled: Boolean) {
        nextPreparationEnabled = enabled
        if (!enabled) nextPreparation.reset(visibleManyueOwners)
        state.invalidate()
    }

    private fun allVisibleAiImagesSubmitted(owners: Set<ViewerReaderPage>): Boolean = owners.all { owner ->
        val result = owner.aiResult
        result != null && owner.submittedAiToken == result.token && owner.aiBridge.current(result)
    }

    private fun updateNextPreparation(owners: Set<ViewerReaderPage>) {
        if (!nextPreparationEnabled) return
        nextPreparation.update(
            visible = owners,
            revision = manyueHead to manyueGeneration,
            allowPreparation = owners.isNotEmpty() && allVisibleAiImagesSubmitted(owners),
        )
    }

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

    // Persistence deliberately ignores ordinary zoom-in. Diagnostics must not turn that null
    // into a false claim that the viewport is exactly 1x.
    internal fun benchmarkActualScale(): Float = state.scale
    internal fun benchmarkHomeScale(): Float = state.homeScale

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
            // Metadata follows the actual visible bottom image. Keep the progress fallback
            // below separate because it may refer to a page that has already left the viewport.
            val visibleHead = WebGpuViewportAiHead.head(owners, null)?.page
            activity.updateWebGpuVisibleHeadPage(this@WebGpuViewerContinuous, visibleHead)
            val head = WebGpuViewportAiHead.head(
                owners,
                currentPage as? ViewerReaderPage,
            )?.page
            val headKey = head?.let { (it.chapter.chapter.id ?: 0L) to it.index }
            if (head != null && (headKey != manyueHead || manyueGeneration != ManyueRuntimeState.generation)) {
                manyueHead = headKey
                manyueGeneration = ManyueRuntimeState.generation
                ManyuePerformanceDiagnostics.event(
                    ManyuePerformanceRecorder.Stage.AI_VIEWPORT_HEAD_CHANGED, head.index,
                    null,
                    ManyuePerformanceRecorder.Metric.CURRENT_GENERATION to manyueGeneration,
                    ManyuePerformanceRecorder.Metric.VISIBLE_PAGE_COUNT to owners.size.toLong(),
                )
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
            // All visible owners are protected by the manager above. Start bottom work first,
            // and reset a stale bridge before its source is used by the exposure recorder.
            for (index in entries.lastIndex downTo 0) {
                val (owner, image) = entries[index]
                owner.aiBridge.updateVisibility(true)
                ManyueReadingCoverage.exposure(
                    owner, owner.aiBridge.source,
                    (image.viewportFraction * 1000).toInt(), (image.pageFraction * 1000).toInt(),
                    image.page.height.toFloat() * state.width / image.page.width.coerceAtLeast(1) * state.scale <= state.height,
                )
            }
            // Start visible source decisions before a newly admitted background neighbor.
            updateNextPreparation(owners)
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
                var submittedCurrentAiImage = false
                submitted.forEach { image ->
                    val owner = viewerPageFor(image.page) as? ViewerReaderPage ?: return@forEach
                    if (owner !in visibleManyueOwners) return@forEach
                    val frame = ManyueReadingCoverage.frameSubmitted(owner, ManyueReadingCoverage.DrawBackend.WEBGPU_SUBMISSION)
                    // Progress/error Render pages can submit their own background and indicator.
                    // They are frames, but are not a decoded original or AI image draw.
                    if (!image.contentSubmitted || image.page !is ImagePage.ImageSingle) return@forEach
                    val result = owner.aiResult?.takeIf { owner.aiBridge.current(it) }
                    ManyueReadingCoverage.drawn(
                        owner, owner.aiBridge.source, result, ManyueReadingCoverage.DrawBackend.WEBGPU_SUBMISSION,
                        submittedFrame = frame,
                    )
                    if (result != null && owner.submittedAiToken != result.token) {
                        owner.submittedAiToken = result.token
                        submittedCurrentAiImage = true
                        owner.page.updateEnhancementState(ManyueEnhancementState.AI_READY)
                        owner.page.updateEnhancedImageInfo(result.outputWidth, result.outputHeight)
                        ManyuePerformanceDiagnostics.event(ManyuePerformanceRecorder.Stage.DISPLAYED, owner.page.index, result.token)
                    }
                }
                // The final visible AI submission can open the offscreen preparation gate
                // without waiting for another viewport invalidation/frame callback.
                if (submittedCurrentAiImage && nextPreparationEnabled) {
                    updateNextPreparation(visibleManyueOwners)
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
        val eventEpoch = selectionPolicy.epoch()
        val owner = viewerPageFor(imagePage) ?: return
        val anchorChapterId = (state.getPage(0)?.let(::viewerPageFor) as? ViewerReaderPage)
            ?.page?.chapter?.chapter?.id
        val page = readerPageFor(imagePage, owner, eventEpoch, anchorChapterId) ?: return
        scope.launch {
            val liveOwner = viewerPageFor(imagePage)
            if (
                liveOwner !== owner ||
                !selectionPolicy.isCurrentDelivery(eventEpoch, imagePage, liveOwner?.imagePage)
            ) {
                return@launch
            }
            activity.onPageSelected(page)
        }
    }

    private fun readerPageFor(
        imagePage: ImagePage,
        owner: ViewerPage,
        eventEpoch: Long,
        anchorChapterId: Long?,
    ): ReaderPage? {
        // Transition pages are not reader pages. Report the previous chapter's last page only
        // after a forward crossing, or when page zero is a real image in that previous chapter.
        if (imagePage is TransitionPage) {
            val transition = owner as? ViewerTransitionPage ?: return null
            val previous = transition.prevChapter ?: return null
            val previousId = previous.chapter.id ?: return null
            val nextId = transition.nextChapter?.chapter?.id
            val key = WebGpuTransitionKey(previousId, nextId)
            if (!selectionPolicy.claimTransitionProgress(key, eventEpoch, anchorChapterId)) return null
            return previous.pages?.lastOrNull()
        }

        // Only an image page needs the lookup - nothing on it names its ReaderPage. Evicted
        // means scrolled well clear of, so there is no progress left to report.
        if (!selectionPolicy.canSelectImagePage(eventEpoch)) return null
        return (owner as? ViewerReaderPage)?.page
    }

    private fun recordTransitionCrossing(transition: ViewerTransitionPage, direction: Int) {
        val previousId = transition.prevChapter?.chapter?.id ?: return
        val nextId = transition.nextChapter?.chapter?.id
        selectionPolicy.recordPageStep(
            WebGpuTransitionKey(previousId, nextId), direction, selectionPolicy.epoch(),
        )
    }

    override fun onPageCrossed(from: ViewerPage, to: ViewerPage, direction: Int) {
        (from as? ViewerTransitionPage)?.let { recordTransitionCrossing(it, direction) }
        if (to !== from) (to as? ViewerTransitionPage)?.let { recordTransitionCrossing(it, direction) }
    }

    override fun setChapters(chapters: ViewerChapters) {
        val retainedAnchorChapterId = when (val anchor = currentPage) {
            is ViewerReaderPage -> anchor.page.chapter.chapter.id
            is ViewerTransitionPage -> anchor.prevChapter?.chapter?.id ?: anchor.nextChapter?.chapter?.id
            else -> null
        }
        if (selectionPolicy.shouldResetForChapterTable(retainedAnchorChapterId, chapters.currChapter.chapter.id)) {
            selectionPolicy.reset()
            lastReadThrough = null
        }
        super.setChapters(chapters)
    }

    override fun destroy() {
        activity.updateWebGpuVisibleHeadPage(this, null)
        selectionPolicy.reset()
        detailFilter.onFailure = null
        nextPreparation.reset(visibleManyueOwners)
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
        nextPreparation.reset(visibleManyueOwners)
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
        selectionPolicy.reset()
        lastReadThrough = null
        super.moveToPage(page)
        wasAtEdge = null
        // Also for a jump to the page already showing, which turns nothing to slide in.
        state.resetScroll()
    }

    override fun animateTurn(direction: Int, fromSpread: ImagePage) {
        state.animateSlideIn(direction)
    }
}
