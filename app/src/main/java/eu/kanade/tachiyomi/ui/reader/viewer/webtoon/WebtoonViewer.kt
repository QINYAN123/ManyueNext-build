package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.graphics.PointF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.core.app.ActivityCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.WebtoonLayoutManager
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerNavigation.NavigationRegion
import eu.kanade.tachiyomi.ui.reader.manyue.ManyuePrefetchManager
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueReaderPrefetcher
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import kotlin.math.max
import kotlin.math.min

/**
 * Implementation of a [Viewer] to display pages with a [RecyclerView].
 */
class WebtoonViewer(val activity: ReaderActivity, val isContinuous: Boolean = true) : Viewer {

    val graph by lazy { activity.appGraph }
    val downloadManager by lazy { graph.downloadManager }
    val readerPreferences by lazy { graph.readerPreferences }

    private val scope = MainScope()
    private val manyuePrefetcher = ManyueReaderPrefetcher(activity)

    /**
     * Recycler view used by this viewer.
     */
    val recycler = WebtoonRecyclerView(activity)

    /**
     * Frame containing the recycler view.
     */
    private val frame = WebtoonFrame(activity)

    /**
     * Distance to scroll when the user taps on one side of the recycler view.
     */
    private val scrollDistance = activity.resources.displayMetrics.heightPixels * 3 / 4

    /**
     * Layout manager of the recycler view.
     */
    private val layoutManager = WebtoonLayoutManager(activity, scrollDistance)

    /**
     * Configuration used by this viewer, like allow taps, or crop image borders.
     */
    val config = WebtoonConfig(scope, readerPreferences)

    /**
     * Adapter of the recycler view.
     */
    private val adapter = WebtoonAdapter(this)

    /**
     * Currently active item. It can be a chapter page or a chapter transition.
     */
    private var currentPage: Any? = null
    private var manyueFirstVisible = -1
    private var manyueLastVisible = -1
    private var manyueVisibleItems: List<Any>? = null
    private var manyueVisiblePages = emptySet<Pair<Long, Int>>()
    private var manyueScheduledVisiblePages: Set<Pair<Long, Int>>? = null

    private val threshold: Int by lazy { readerPreferences.readerHideThreshold.get().threshold }

    init {
        recycler.setItemViewCacheSize(RECYCLER_VIEW_CACHE_SIZE)
        recycler.isVisible = false // Don't let the recycler layout yet
        recycler.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        recycler.isFocusable = false
        recycler.itemAnimator = null
        recycler.layoutManager = layoutManager
        recycler.adapter = adapter
        recycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    onScrolled()

                    if ((dy > threshold || dy < -threshold) && activity.viewModel.state.value.menuVisible) {
                        activity.hideMenu()
                    }

                    if (dy < 0) {
                        val firstIndex = layoutManager.findFirstVisibleItemPosition()
                        val firstItem = adapter.items.getOrNull(firstIndex)
                        if (firstItem is ChapterTransition.Prev && firstItem.to != null) {
                            activity.requestPreloadChapter(firstItem.to)
                        }
                    }

                    val lastIndex = layoutManager.findLastEndVisibleItemPosition()
                    val lastItem = adapter.items.getOrNull(lastIndex)
                    if (lastItem is ChapterTransition.Next && lastItem.to == null) {
                        activity.showMenu()
                    }
                }
            },
        )
        recycler.tapListener = { event ->
            val viewPosition = IntArray(2)
            recycler.getLocationOnScreen(viewPosition)
            val viewPositionRelativeToWindow = IntArray(2)
            recycler.getLocationInWindow(viewPositionRelativeToWindow)
            val pos = PointF(
                (event.rawX - viewPosition[0] + viewPositionRelativeToWindow[0]) / recycler.width,
                (event.rawY - viewPosition[1] + viewPositionRelativeToWindow[1]) / recycler.originalHeight,
            )
            when (config.navigator.getAction(pos)) {
                NavigationRegion.MENU -> activity.toggleMenu()
                NavigationRegion.NEXT, NavigationRegion.RIGHT -> scrollDown()
                NavigationRegion.PREV, NavigationRegion.LEFT -> scrollUp()
            }
        }
        recycler.longTapListener = f@{ event ->
            if (activity.viewModel.state.value.menuVisible || config.longTapEnabled) {
                val child = recycler.findChildViewUnder(event.x, event.y)
                if (child != null) {
                    val position = recycler.getChildAdapterPosition(child)
                    val item = adapter.items.getOrNull(position)
                    if (item is ReaderPage) {
                        activity.onPageLongTap(item)
                        return@f true
                    }
                }
            }
            false
        }

        config.imagePropertyChangedListener = {
            refreshAdapter()
        }

        config.themeChangedListener = {
            ActivityCompat.recreate(activity)
        }

        config.doubleTapZoomChangedListener = {
            frame.doubleTapZoom = it
        }

        config.zoomPropertyChangedListener = {
            frame.zoomOutDisabled = it
        }

        config.navigationModeChangedListener = {
            val showOnStart = config.navigationOverlayOnStart || config.forceNavigationOverlay
            activity.binding.navigationOverlay.setNavigation(config.navigator, showOnStart)
        }

        frame.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        frame.addView(recycler)
    }

    private fun checkAllowPreload(page: ReaderPage?): Boolean {
        // Page is transition page - preload allowed
        page ?: return true

        // Initial opening - preload allowed
        currentPage ?: return true

        val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
        val nextChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as? ReaderPage)?.chapter

        // Allow preload for
        // 1. Going between pages of same chapter
        // 2. Next chapter page
        return when (page.chapter) {
            (currentPage as? ReaderPage)?.chapter -> true
            nextChapter -> true
            else -> false
        }
    }

    /**
     * Returns the view this viewer uses.
     */
    override fun getView(): View {
        return frame
    }

    /**
     * Destroys this viewer. Called when leaving the reader or swapping viewers.
     */
    override fun destroy() {
        super.destroy()
        manyuePrefetcher.destroy()
        eu.kanade.tachiyomi.ui.reader.manyue.ManyueRuntimeState.invalidateSession()
        scope.cancel()
    }

    override fun refreshManyue() {
        manyuePrefetcher.reset()
        ManyuePrefetchManager.reset()
        (currentPage as? ReaderPage)?.let { page ->
            val chapterId = page.chapter.chapter.id ?: 0L
            ManyuePrefetchManager.onPageChanged(chapterId, page.index, visibleManyuePages())
            manyuePrefetcher.onPageSelected(page, managerAlreadyUpdated = true)
        }
        refreshAdapter()
    }

    override fun reapplyManyueWidth() {
        val position = layoutManager.findFirstVisibleItemPosition()
        val offset = layoutManager.findViewByPosition(position)?.top ?: 0
        eu.kanade.tachiyomi.ui.reader.manyue.ManyueFoldableController.applyToTree(frame)
        if (position >= 0) {
            recycler.doOnNextLayout { layoutManager.scrollToPositionWithOffset(position, offset) }
        }
    }

    /**
     * Called from the RecyclerView listener when a [page] is marked as active. It notifies the
     * activity of the change and requests the preload of the next chapter if this is the last page.
     */
    private fun onPageSelected(page: ReaderPage, allowPreload: Boolean) {
        val pages = page.chapter.pages ?: return
        logcat { "onPageSelected: ${page.number}/${pages.size}" }
        activity.onPageSelected(page)
        // Move the scheduling cursor before the holder registers the visible request. This
        // guarantees a newly selected page receives priority 100 even when paging backwards.
        ManyuePrefetchManager.onPageChanged(page.chapter.chapter.id ?: 0L, page.index, visibleManyuePages())
        manyuePrefetcher.onPageSelected(page, managerAlreadyUpdated = true)

        // Preload next chapter once we're within the last 5 pages of the current chapter
        val inPreloadRange = pages.size - page.number < 5
        if (inPreloadRange && allowPreload && page.chapter == adapter.currentChapter) {
            logcat { "Request preload next chapter because we're at page ${page.number} of ${pages.size}" }
            val nextItem = adapter.items.getOrNull(adapter.items.size - 1)
            val transitionChapter = (nextItem as? ChapterTransition.Next)?.to ?: (nextItem as?ReaderPage)?.chapter
            if (transitionChapter != null) {
                logcat { "Requesting to preload chapter ${transitionChapter.chapter.chapter_number}" }
                activity.requestPreloadChapter(transitionChapter)
            }
        }
    }

    /**
     * Called from the RecyclerView listener when a [transition] is marked as active. It request the
     * preload of the destination chapter of the transition.
     */
    private fun onTransitionSelected(transition: ChapterTransition) {
        logcat { "onTransitionSelected: $transition" }
        val toChapter = transition.to
        if (toChapter != null) {
            logcat { "Request preload destination chapter because we're on the transition" }
            activity.requestPreloadChapter(toChapter)
        }
    }

    /**
     * Tells this viewer to set the given [chapters] as active.
     */
    override fun setChapters(chapters: ViewerChapters) {
        val chapterChanged = adapter.currentChapter?.chapter?.id != chapters.currChapter.chapter.id
        val previouslyVisiblePages = if (chapterChanged) visibleManyuePages() else emptySet()
        if (chapterChanged) {
            manyuePrefetcher.reset()
            eu.kanade.tachiyomi.ui.reader.manyue.ManyueFoldableController.clearSamples()
        }
        val forceTransition = config.alwaysShowChapterTransition || currentPage is ChapterTransition
        adapter.setChapters(chapters, forceTransition)

        if (chapterChanged) {
            reconcileManyueAfterChapterChange(chapters, previouslyVisiblePages)
        }

        if (recycler.isGone) {
            logcat { "Recycler first layout" }
            val pages = chapters.currChapter.pages ?: return
            moveToPage(pages[min(chapters.currChapter.requestedPage, pages.lastIndex)])
            recycler.isVisible = true
        }
    }

    /**
     * Reconcile the selected and still-visible page requests after the adapter changes chapters.
     * The selected ReaderPage can be the same object as [currentPage], so waiting for another
     * scroll callback would leave the prefetch window on the previous chapter.
     */
    private fun reconcileManyueAfterChapterChange(
        chapters: ViewerChapters,
        previouslyVisiblePages: Set<Pair<Long, Int>>,
    ) {
        val chapterId = chapters.currChapter.chapter.id ?: 0L
        val visiblePages = previouslyVisiblePages + visibleManyuePages()
        val selectedPage = (currentPage as? ReaderPage)
            ?.takeIf { it.chapter.chapter.id == chapterId && it in adapter.items }
            ?: run {
                val first = layoutManager.findFirstVisibleItemPosition()
                val last = layoutManager.findLastEndVisibleItemPosition()
                if (first >= 0 && last >= first) {
                    (last downTo first).asSequence()
                        .mapNotNull { adapter.items.getOrNull(it) as? ReaderPage }
                        .firstOrNull { it.chapter.chapter.id == chapterId }
                } else {
                    null
                }
            }
            ?: chapters.currChapter.pages
                ?.takeIf { it.isNotEmpty() }
                ?.let { pages -> pages[min(chapters.currChapter.requestedPage.coerceAtLeast(0), pages.lastIndex)] }

        val selected = selectedPage ?: run {
            ManyuePrefetchManager.updateVisiblePages(visiblePages)
            return
        }
        ManyuePrefetchManager.onChapterChanged(chapterId, selected.index, visiblePages)
        // Deliberately reconcile directly: this must not call activity.onPageSelected() or
        // recurse into chapter transition callbacks.
        manyuePrefetcher.onPageSelected(selected, managerAlreadyUpdated = true)
    }

    /**
     * Tells this viewer to move to the given [page].
     */
    override fun moveToPage(page: ReaderPage) {
        val position = adapter.items.indexOf(page)
        if (position != -1) {
            layoutManager.scrollToPositionWithOffset(position, 0)
            if (layoutManager.findLastEndVisibleItemPosition() == -1) {
                onScrolled(pos = position)
            }
        } else {
            logcat { "Page $page not found in adapter" }
        }
    }

    private fun visibleManyuePages(): Set<Pair<Long, Int>> {
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == manyueFirstVisible && last == manyueLastVisible && manyueVisibleItems === adapter.items) {
            return manyueVisiblePages
        }
        manyueFirstVisible = first
        manyueLastVisible = last
        manyueVisibleItems = adapter.items
        manyueVisiblePages = if (first < 0 || last < first) emptySet() else (first..last).mapNotNull { position ->
            (adapter.items.getOrNull(position) as? ReaderPage)?.let {
                (it.chapter.chapter.id ?: 0L) to it.index
            }
        }.toSet()
        return manyueVisiblePages
    }

    fun onScrolled(pos: Int? = null) {
        val visible = visibleManyuePages()
        if (manyueScheduledVisiblePages !== visible) {
            manyueScheduledVisiblePages = visible
            ManyuePrefetchManager.updateVisiblePages(visible)
        }
        val position = pos ?: layoutManager.findLastEndVisibleItemPosition()
        val item = adapter.items.getOrNull(position)
        val allowPreload = checkAllowPreload(item as? ReaderPage)
        if (item != null && currentPage != item) {
            currentPage = item
            when (item) {
                is ReaderPage -> onPageSelected(item, allowPreload)
                is ChapterTransition -> onTransitionSelected(item)
            }
        }
    }

    /**
     * Scrolls up by [scrollDistance].
     */
    private fun scrollUp() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, -scrollDistance)
        } else {
            recycler.scrollBy(0, -scrollDistance)
        }
    }

    /**
     * Scrolls down by [scrollDistance].
     */
    private fun scrollDown() {
        if (config.usePageTransitions) {
            recycler.smoothScrollBy(0, scrollDistance)
        } else {
            recycler.scrollBy(0, scrollDistance)
        }
    }

    /**
     * Called from the containing activity when a key [event] is received. It should return true
     * if the event was handled, false otherwise.
     */
    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val isUp = event.action == KeyEvent.ACTION_UP

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollDown() else scrollUp()
                }
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (!config.volumeKeysEnabled || activity.viewModel.state.value.menuVisible) {
                    return false
                } else if (isUp) {
                    if (!config.volumeKeysInverted) scrollUp() else scrollDown()
                }
            }
            KeyEvent.KEYCODE_MENU -> if (isUp) activity.toggleMenu()

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            -> if (isUp) scrollUp()

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            -> if (isUp) scrollDown()
            else -> return false
        }
        return true
    }

    /**
     * Called from the containing activity when a generic motion [event] is received. It should
     * return true if the event was handled, false otherwise.
     */
    override fun handleGenericMotionEvent(event: MotionEvent): Boolean {
        return false
    }

    /**
     * Notifies adapter of changes around the current page to trigger a relayout in the recycler.
     * Used when an image configuration is changed.
     */
    private fun refreshAdapter() {
        val position = layoutManager.findLastEndVisibleItemPosition()
        adapter.refresh()
        if (adapter.itemCount == 0) return
        val anchor = position.takeIf { it >= 0 } ?: 0
        val start = max(0, anchor - 3)
        val endExclusive = min(anchor + 4, adapter.itemCount)
        if (endExclusive > start) adapter.notifyItemRangeChanged(start, endExclusive - start)
    }
}

// Double the cache size to reduce rebinds/recycles incurred by the extra layout space on scroll direction changes
private const val RECYCLER_VIEW_CACHE_SIZE = 4
