package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import androidx.core.view.isVisible
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.metadata.SourceImageCaptureOrder
import eu.kanade.tachiyomi.ui.reader.metadata.SourceImageInfo
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null

    init {
        loadJob = scope.launch { loadPageAndProcessStatus() }
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        manyueBridgeRef?.cancel()
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(context)
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus() {
        val loader = page.chapter.pageLoader ?: return

        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        progressIndicator?.setProgress(0)

        val streamFn = page.stream ?: return
        // Sync Manyue preferences into process-wide runtime state (cheap, off the IO thread).
        eu.kanade.tachiyomi.ui.reader.manyue.ManyueRuntimeState.syncFrom(viewer.readerPreferences)

        try {
            val (source, isAnimated, background, originalBytes) = withIOContext {
                val raw = streamFn().use { stream ->
                    SourceImageCaptureOrder.captureThenTransform(
                        raw = Buffer().readFrom(stream),
                        publish = item::updateSourceImageInfo,
                    ) { process(item, it) }
                }
                (item.sourceImageInfo.value as? SourceImageInfo.Available)?.let {
                    eu.kanade.tachiyomi.ui.reader.manyue.ManyueFoldableController.recordSample(it.width, it.height)
                }
                val mode = eu.kanade.tachiyomi.ui.reader.manyue.ManyueRuntimeState.modeInt
                if (mode == eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementMode.OFF.value) {
                    item.updateEnhancementState(eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementState.ORIGINAL)
                }
                if (mode == eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementMode.CLASSIC.value) {
                    item.updateEnhancementState(
                        eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementState.CLASSIC_PROCESSING,
                    )
                }
                // OFF and classic-only do not copy the complete original. AI owns the only full
                // byte copy because it must persist native process input beyond this call.
                val originalBytes = if (
                    mode == eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementMode.AI_2X.value ||
                    mode == eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementMode.AI_2X_CLASSIC.value
                ) raw.peek().readByteArray() else null
                var source = raw
                // Manyue enhancement hook (no-op when OFF / animated / failure)
                source = eu.kanade.tachiyomi.ui.reader.manyue.ManyueReaderHook
                    .applyClassic(source, eu.kanade.tachiyomi.ui.reader.manyue.ManyueRuntimeState.modeInt,
                        eu.kanade.tachiyomi.ui.reader.manyue.ManyueRuntimeState.classicStrength,
                        item::updateEnhancementState)
                val isAnimated = ImageUtil.isAnimatedAndSupported(source)
                val background = if (!isAnimated && viewer.config.automaticBackground) {
                    ImageUtil.chooseBackground(context, source.peek().inputStream())
                } else {
                    null
                }
                ManyueImageResult(source, isAnimated, background, originalBytes)
            }
            val pageConfig = Config(
                zoomDuration = viewer.config.doubleTapAnimDuration,
                minimumScaleType = viewer.config.imageScaleType,
                cropBorders = viewer.config.imageCropBorders,
                zoomStartPosition = viewer.config.imageZoomType,
                landscapeZoom = viewer.config.landscapeZoom,
            )
            withUIContext {
                setImage(
                    source,
                    isAnimated,
                    pageConfig,
                )
                if (!isAnimated) {
                    pageBackground = background
                }
                removeErrorLayout()
            }
            // Kick off async AI upscale (no-op when OFF/CLASSIC/unsupported/animated).
            // Callback verifies page identity before refreshing.
            val aiMode = eu.kanade.tachiyomi.ui.reader.manyue.ManyueRuntimeState.modeInt
            if (aiMode == eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementMode.AI_2X.value ||
                aiMode == eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementMode.AI_2X_CLASSIC.value
            ) {
                try {
                    val bridge = manyueBridge(pageConfig)
                    val identity = eu.kanade.tachiyomi.ui.reader.manyue.ManyuePageBridge.Identity(
                        page.chapter.chapter.manga_id ?: 0L,
                        page.chapter.chapter.id ?: 0L,
                        page.index,
                    )
                    originalBytes?.let {
                        bridge.tryStartAi(
                            context.applicationContext,
                            identity,
                            it,
                            item::updateEnhancedImageInfo,
                            item::updateEnhancementState,
                        )
                    }
                } catch (t: Throwable) {
                    logcat(LogPriority.WARN, t) { "Manyue AI start skipped" }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private var manyueBridgeRef: eu.kanade.tachiyomi.ui.reader.manyue.ManyuePageBridge? = null
    private fun manyueBridge(cfg: Config): eu.kanade.tachiyomi.ui.reader.manyue.ManyuePageBridge {
        return manyueBridgeRef ?: eu.kanade.tachiyomi.ui.reader.manyue.ManyuePageBridge(this, cfg).also { manyueBridgeRef = it }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        return ImageUtil.splitInHalf(imageSource, side)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
        // Manyue foldable width constraint (no-op on regular phones)
        val screenWidth = (parent as? android.view.View)?.width?.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        eu.kanade.tachiyomi.ui.reader.manyue.ManyueFoldableController.applyToView(this, screenWidth)
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }
}

private data class ManyueImageResult(
    val source: BufferedSource,
    val isAnimated: Boolean,
    val background: android.graphics.drawable.Drawable?,
    val originalBytes: ByteArray?,
)
