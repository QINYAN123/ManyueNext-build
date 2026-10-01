package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.os.SystemClock
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementCache
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueViewportPolicy
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import java.io.File
import okio.BufferedSource

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private var pageView: View? = null
    private var stagedPageView: SubsamplingScaleImageView? = null
    private var stagedCacheLease: ManyueEnhancementCache.CacheLease? = null
    private var pageCacheLease: ManyueEnhancementCache.CacheLease? = null
    private var stagedImageId = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sourceGeneration = 0L
    private var activeSourceGeneration: Long? = null
    private var landscapeZoomHandler: Handler? = null
    private var landscapeZoomRunnable: Runnable? = null

    private var config: Config? = null
    private var manyueTouchActive = false
    private var manyueLastTouch = 0L
    private var replacementHeight = 0
    private var replacementWidth = 0
    private var stagedCommit: Runnable? = null

    companion object {
        // Normal strip pages also decode large source images. Keep their shared pool bounded
        // instead of competing through AsyncTask's process-wide pool with every attached page.
        private val readerImageExecutor = Executors.newFixedThreadPool(2) { task ->
            Thread({
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                task.run()
            }, "manyue-reader-images").apply { isDaemon = true }
        }
        // AI tile decoding must not fan out across every attached strip at once.
        private val aiTileExecutor = Executors.newSingleThreadExecutor { task ->
            Thread({
                runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                task.run()
            }, "manyue-ai-tiles").apply { isDaemon = true }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (replacementHeight > 0 && MeasureSpec.getSize(widthMeasureSpec) == replacementWidth) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(replacementHeight, MeasureSpec.EXACTLY))
        } else {
            replacementHeight = 0
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        stagedCommit?.let {
            mainHandler.removeCallbacks(it)
            stagedPageView?.removeCallbacks(it)
            postOnAnimation(it)
        }
    }

    override fun onDetachedFromWindow() {
        stagedCommit?.let {
            mainHandler.removeCallbacks(it)
            removeCallbacks(it)
            stagedPageView?.removeCallbacks(it)
        }
        cancelLandscapeZoom()
        manyueTouchActive = false
        manyueLastTouch = 0L
        super.onDetachedFromWindow()
    }

    private fun cancelLandscapeZoom() {
        val handler = landscapeZoomHandler
        val runnable = landscapeZoomRunnable
        if (handler != null && runnable != null) handler.removeCallbacks(runnable)
        landscapeZoomHandler = null
        landscapeZoomRunnable = null
    }

    private fun beginSourceReplacement() {
        cancelLandscapeZoom()
        sourceGeneration++
        activeSourceGeneration = sourceGeneration
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Strip image views deliberately reject touches. Android then sends their UP/CANCEL
        // to the RecyclerView, so a child-local DOWN flag would stay set forever.
        if (isWebtoon) return super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> manyueTouchActive = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> manyueTouchActive = false
        }
        manyueLastTouch = SystemClock.uptimeMillis()
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !handled) manyueTouchActive = false
        return handled
    }

    fun isManyueImageReady(): Boolean = when (val current = pageView) {
        is SubsamplingScaleImageView -> current.isReady
        is AppCompatImageView -> current.drawable != null
        else -> false
    }

    fun isManyueInteractionActive(): Boolean {
        if (isWebtoon) {
            var ancestor = parent
            while (ancestor != null) {
                if (ancestor is WebtoonRecyclerView) return ancestor.isManyueInteractionActive()
                ancestor = (ancestor as? View)?.parent
            }
            return false
        }
        return manyueTouchActive || SystemClock.uptimeMillis() - manyueLastTouch < 120L
    }

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            this@ReaderPageImageView.onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        val zoomConfig = config ?: return
        val expectedGeneration = activeSourceGeneration ?: return
        if (
            zoomConfig.landscapeZoom &&
            zoomConfig.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            cancelLandscapeZoom()
            val zoomView = this
            val handler = zoomView.handler ?: return
            val runnable = object : Runnable {
                override fun run() {
                    if (landscapeZoomRunnable !== this) return
                    landscapeZoomRunnable = null
                    landscapeZoomHandler = null
                    if (
                        sourceGeneration != expectedGeneration ||
                        activeSourceGeneration != expectedGeneration ||
                        pageView !== zoomView ||
                        !zoomView.isAttachedToWindow ||
                        !zoomView.isReady ||
                        this@ReaderPageImageView.config !== zoomConfig
                    ) return

                    val point = when (zoomConfig.zoomStartPosition) {
                        ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(zoomView.sWidth.toFloat(), 0F)
                        ZoomStartPosition.RIGHT -> if (forward) PointF(zoomView.sWidth.toFloat(), 0F) else PointF(0F, 0F)
                        ZoomStartPosition.CENTER -> zoomView.center ?: return
                    }

                    val targetScale = zoomView.height.toFloat() / zoomView.sHeight.toFloat()
                    val animation = zoomView.animateScaleAndCenter(targetScale, point) ?: return
                    animation
                        .withDuration(500)
                        .withEasing(EASE_IN_OUT_QUAD)
                        .withInterruptible(true)
                        .start()
                }
            }
            landscapeZoomHandler = handler
            landscapeZoomRunnable = runnable
            handler.postDelayed(runnable, 500L)
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        beginSourceReplacement()
        cancelStagedImage()
        replacementHeight = 0
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        beginSourceReplacement()
        cancelStagedImage()
        replacementHeight = 0
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun recycle() {
        cancelLandscapeZoom()
        sourceGeneration++
        activeSourceGeneration = null
        manyueTouchActive = false
        manyueLastTouch = 0L
        replacementHeight = 0
        cancelStagedImage()
        val current = pageView
        if (current != null) try {
            when (current) {
                is SubsamplingScaleImageView -> current.recycle()
                is AppCompatImageView -> current.dispose()
            }
        } finally {
            current.isVisible = false
            val lease = pageCacheLease
            pageCacheLease = null
            if (lease != null) mainHandler.postDelayed(32L) { lease.close() }
        }
    }

    /**
     * Prepare an encoded AI result in a second tiled view, then swap it in once SSIV has a base
     * layer ready. Keeping the current page visible avoids the blank interval caused by reset().
     */
    fun setTiledImagePreservingCurrent(
        file: File,
        config: Config,
        shouldCommit: () -> Boolean,
        maySwap: () -> Boolean,
        onReady: (width: Int, height: Int) -> Unit,
        onError: (Throwable?) -> Unit,
        onDisplayError: (Throwable) -> Unit = {},
    ): Long {
        cancelStagedImage()
        this.config = config
        val requestId = ++stagedImageId
        val stagedLease = ManyueEnhancementCache.pinFile(context, file)
        if (stagedLease == null || !file.isFile) {
            stagedLease?.close()
            onError(IllegalStateException("Cached AI image is being removed or is missing"))
            return requestId
        }
        stagedCacheLease = stagedLease
        try {
            if (isWebtoon && height > 0) {
                // Keep the same RecyclerView item geometry while both views are measured and
                // after the swap. Tiny aspect-ratio rounding differences must not move the strip.
                replacementHeight = height
                replacementWidth = width
            }
            val nextView = createSubsamplingImageView().apply {
                var committedGeneration: Long? = null
                setExecutor(aiImageExecutor())
                setMaxTileSize(1024)
                setPrepareBaseTilesToDraw(true)
                setRegionDecoderFactory {
                    val decoderLease = ManyueEnhancementCache.pinFile(context, file)
                        ?: error("Cached AI image is being removed")
                    try {
                        eu.kanade.tachiyomi.ui.reader.manyue.ManyueRegionDecoder(config.cropBorders, decoderLease)
                    } catch (error: Throwable) {
                        decoderLease.close()
                        throw error
                    }
                }
                setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
                setMinimumScaleType(config.minimumScaleType)
                setMinimumDpi(1)
                setCropBorders(config.cropBorders)
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            // A small image can finish decoding before its first measured layout.
                            scheduleCommit()
                        }

                        override fun onImageLoaded() {
                            // onReady only guarantees source dimensions; tiles can still be empty.
                            // Retain the original until the complete base layer has been decoded.
                            scheduleCommit()
                        }

                        private fun scheduleCommit() {
                            if (!isImageLoaded || !isReady || stagedCommit != null) return
                            // SSIV may emit onReady from onDraw. Mutating FrameLayout children
                            // during dispatchDraw can skip a child or dereference a removed child.
                            lateinit var commit: Runnable
                            commit = Runnable {
                                if (
                                    stagedCommit === commit &&
                                    this@ReaderPageImageView.isAttachedToWindow &&
                                    this@apply.isAttachedToWindow
                                ) {
                                    stagedCommit = null
                                    tryCommit()
                                }
                            }
                            stagedCommit = commit
                            if (this@ReaderPageImageView.isAttachedToWindow && this@apply.isAttachedToWindow) {
                                this@ReaderPageImageView.postOnAnimation(commit)
                            }
                        }

                        private fun tryCommit() {
                            if (!this@ReaderPageImageView.isAttachedToWindow || !this@apply.isAttachedToWindow) return
                            if (stagedPageView !== this@apply || requestId != stagedImageId || !shouldCommit()) {
                                cancelStagedImage(requestId)
                                return
                            }
                            if (!isImageLoaded || !isReady) return
                            if (!maySwap()) {
                                stagedCommit?.let {
                                    mainHandler.removeCallbacks(it)
                                    this@ReaderPageImageView.removeCallbacks(it)
                                    this@apply.removeCallbacks(it)
                                }
                                val retry = Runnable { stagedCommit = null; scheduleCommit() }
                                stagedCommit = retry
                                if (isAttachedToWindow) mainHandler.postDelayed(retry, 80L)
                                return
                            }
                            cancelLandscapeZoom()
                            sourceGeneration++
                            activeSourceGeneration = sourceGeneration
                            committedGeneration = sourceGeneration
                            val previous = pageView
                            val old = previous as? SubsamplingScaleImageView
                            val center = old?.center
                            val viewport = if (old?.isReady == true && center != null) {
                                ManyueViewportPolicy.rescale(
                                    old.scale, center.x, center.y, old.sWidth, old.sHeight, sWidth, sHeight,
                                )
                            } else null
                            setupZoom(config)
                            if (viewport != null) {
                                setScaleAndCenter(viewport.scale, PointF(viewport.centerX, viewport.centerY))
                            } else if (isVisibleOnScreen()) {
                                landscapeZoom(true)
                            }
                            pageView = this@apply
                            stagedPageView = null
                            stagedCacheLease = null
                            val previousLease = pageCacheLease
                            pageCacheLease = stagedLease
                            previous?.let { old ->
                                try {
                                    removeView(old)
                                } finally {
                                    mainHandler.postDelayed(32L) {
                                        disposeRemovedPageView(old, previousLease)
                                    }
                                }
                            }
                            if (previous == null) previousLease?.close()
                            // Image replacement is not a first-load event: do not reapply foldable
                            // layout/zoom callbacks to the whole RecyclerView during a swap.
                            background = pageBackground
                            onReady(sWidth, sHeight)
                        }

                        override fun onTileLoadError(e: Exception) {
                            if (stagedPageView === this@apply && requestId == stagedImageId) {
                                onImageLoadError(e)
                            } else if (
                                pageView === this@apply &&
                                activeSourceGeneration == committedGeneration
                            ) {
                                onDisplayError(e)
                            }
                        }

                        override fun onImageLoadError(e: Exception) {
                            if (stagedPageView === this@apply && requestId == stagedImageId) {
                                cancelStagedImage(requestId)
                                onError(e)
                            }
                        }
                    },
                )
                isVisible = true
            }
            stagedPageView = nextView
            // Keep the old view above this one while the decoder builds the base layer. The staged
            // view remains visible to Android and therefore continues to receive onDraw callbacks.
            addView(nextView, 0, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            nextView.setImage(ImageSource.uri(context, Uri.fromFile(file)))
        } catch (error: Throwable) {
            cancelStagedImage(requestId)
            onError(error)
        }
        return requestId
    }

    fun cancelStagedImage(requestId: Long? = null) {
        if (requestId != null && requestId != stagedImageId) return
        stagedImageId++
        stagedCommit?.let {
            mainHandler.removeCallbacks(it)
            removeCallbacks(it)
            stagedPageView?.removeCallbacks(it)
        }
        stagedCommit = null
        val staged = stagedPageView
        val lease = stagedCacheLease
        stagedPageView = null
        stagedCacheLease = null
        if (staged != null) {
            try {
                removeView(staged)
            } finally {
                mainHandler.postDelayed(32L) {
                    disposeRemovedPageView(staged, lease)
                }
            }
        } else {
            lease?.close()
        }
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = createSubsamplingImageView()
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun releasePageCacheLeaseAfterDecoderReset() {
        val lease = pageCacheLease ?: return
        pageCacheLease = null
        mainHandler.postDelayed(32L) { lease.close() }
    }

    /** Dispose removed views off the UI thread and release their cache pin only after decoder close. */
    private fun disposeRemovedPageView(view: View, lease: ManyueEnhancementCache.CacheLease?) {
        when (view) {
            is SubsamplingScaleImageView -> view.disposeDetached { lease?.close() }
            is AppCompatImageView -> try {
                view.dispose()
            } finally {
                lease?.close()
            }
            else -> lease?.close()
        }
    }

    protected open fun aiImageExecutor(): Executor = aiTileExecutor

    private fun createSubsamplingImageView(): SubsamplingScaleImageView =
        (if (isWebtoon) WebtoonSubsamplingImageView(context) else SubsamplingScaleImageView(context)).apply {
            setExecutor(readerImageExecutor)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        // Not used
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                releasePageCacheLeaseAfterDecoderReset()
                isVisible = true
            }
            is BufferedSource -> {
                if (!isWebtoon) {
                    setImage(ImageSource.inputStream(data.inputStream()))
                    releasePageCacheLeaseAfterDecoderReset()
                    isVisible = true
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            releasePageCacheLeaseAfterDecoderReset()
                            isVisible = true
                        },
                    )
                    .listener(
                        onError = { _, result ->
                            onImageLoadError(result.throwable)
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        val previous = pageView
        removeView(previous)
        if (previous is SubsamplingScaleImageView) {
            val lease = pageCacheLease
            pageCacheLease = null
            mainHandler.postDelayed(32L) {
                disposeRemovedPageView(previous, lease)
            }
        }

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F
