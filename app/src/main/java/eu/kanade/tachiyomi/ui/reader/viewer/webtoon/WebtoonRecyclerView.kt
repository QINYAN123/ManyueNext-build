package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueReaderWorkGate
import eu.kanade.tachiyomi.ui.reader.viewer.GestureDetectorWithLongTap
import kotlin.math.abs

/**
 * Implementation of a [RecyclerView] used by the webtoon reader.
 */
class WebtoonRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : RecyclerView(context, attrs, defStyle) {

    private var isZooming = false
    private var manyueTouchActive = false
    private var manyueLastInteraction = 0L
    private var manyueAnimating = 0
    private var thermalPowerManager: PowerManager? = null
    private var thermalStatusListener: PowerManager.OnThermalStatusChangedListener? = null
    private var frameCallbackPosted = false
    private var previousFrameTimeNanos = 0L
    private var manyueSwapSampleUntil = 0L

    private val manyueFrameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameCallbackPosted = false
        if (!isFrameSamplingActive()) {
            previousFrameTimeNanos = 0L
            ManyueReaderWorkGate.resetFrameSampling(this)
            return@FrameCallback
        }

        if (previousFrameTimeNanos > 0L && frameTimeNanos > previousFrameTimeNanos) {
            ManyueReaderWorkGate.reportFrame(
                this,
                frameTimeNanos - previousFrameTimeNanos,
                expectedFrameIntervalNanos(),
            )
        } else {
            ManyueReaderWorkGate.resetFrameSampling(this)
        }
        previousFrameTimeNanos = frameTimeNanos
        updateFrameSampling()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> manyueTouchActive = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> manyueTouchActive = false
        }
        manyueLastInteraction = SystemClock.uptimeMillis()
        updateManyueWorkGate()
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !handled) {
            manyueTouchActive = false
            updateManyueWorkGate()
        }
        return handled
    }

    fun isManyueInteractionActive(): Boolean = manyueTouchActive || manyueAnimating > 0 ||
        scrollState != SCROLL_STATE_IDLE || SystemClock.uptimeMillis() - manyueLastInteraction < 300L

    /** Stable scrolling can display a prepared result; pinch, layout, and measured pressure cannot. */
    fun canSwapManyueImage(): Boolean {
        if (isZooming || manyueAnimating > 0 || isComputingLayout ||
            ManyueReaderWorkGate.isDisplayBlocked()
        ) {
            return false
        }
        return scrollState != SCROLL_STATE_IDLE ||
            (!manyueTouchActive && SystemClock.uptimeMillis() - manyueLastInteraction >= 120L)
    }

    /** Include replacement/retirement frames even after scrolling stops. */
    fun noteManyueImageSwap() {
        manyueSwapSampleUntil = SystemClock.uptimeMillis() + 500L
        updateFrameSampling()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateManyueWorkGate()
        startThermalStatusMonitoring()
        updateFrameSampling()
    }

    private fun updateManyueWorkGate() {
        if (!isAttachedToWindow) {
            ManyueReaderWorkGate.release(this)
            return
        }
        ManyueReaderWorkGate.update(this, manyueTouchActive || manyueAnimating > 0 || scrollState != SCROLL_STATE_IDLE)
    }

    private fun isFrameSamplingActive(): Boolean = isAttachedToWindow &&
        (scrollState != SCROLL_STATE_IDLE || SystemClock.uptimeMillis() < manyueSwapSampleUntil)

    private fun updateFrameSampling() {
        if (!isFrameSamplingActive()) {
            stopFrameSampling()
            return
        }
        if (!frameCallbackPosted) {
            frameCallbackPosted = true
            Choreographer.getInstance().postFrameCallback(manyueFrameCallback)
        }
    }

    private fun stopFrameSampling() {
        if (frameCallbackPosted) Choreographer.getInstance().removeFrameCallback(manyueFrameCallback)
        frameCallbackPosted = false
        previousFrameTimeNanos = 0L
        ManyueReaderWorkGate.resetFrameSampling(this)
    }

    private fun expectedFrameIntervalNanos(): Long {
        val refreshRate = display?.refreshRate?.takeIf { it in 30f..240f } ?: 60f
        return (1_000_000_000.0 / refreshRate).toLong()
    }

    private fun startThermalStatusMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val powerManager = context.applicationContext.getSystemService(PowerManager::class.java) ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            ManyueReaderWorkGate.reportThermalStatus(this, status)
        }
        powerManager.addThermalStatusListener(listener)
        thermalPowerManager = powerManager
        thermalStatusListener = listener
        ManyueReaderWorkGate.reportThermalStatus(this, powerManager.currentThermalStatus)
    }

    private fun stopThermalStatusMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = thermalPowerManager
            val listener = thermalStatusListener
            if (powerManager != null && listener != null) {
                powerManager.removeThermalStatusListener(listener)
            }
        }
        thermalPowerManager = null
        thermalStatusListener = null
    }

    override fun onDetachedFromWindow() {
        cancelActiveZoomAnimations()
        manyueTouchActive = false
        manyueLastInteraction = 0L
        stopFrameSampling()
        stopThermalStatusMonitoring()
        ManyueReaderWorkGate.release(this)
        super.onDetachedFromWindow()
    }
    private var atLastPosition = false
    private var atFirstPosition = false
    private var halfWidth = 0
    private var halfHeight = 0
    private var viewportWidth = 0
    private var zoomTargetRate: Float? = null
    private var zoomAnimator: AnimatorSet? = null
    private var zoomFlingActive = false
    private var zoomFlingAnimator: AnimatorSet? = null
    var originalHeight = 0
        private set
    private var hasViewportSize = false
    private var pendingComfortScale: Float? = null
    private var firstVisibleItemPosition = 0
    private var lastVisibleItemPosition = 0
    private var currentScale = DEFAULT_RATE
    var zoomOutDisabled = false
        set(value) {
            field = value
            if (value && currentScale < DEFAULT_RATE) {
                zoom(currentScale, DEFAULT_RATE, x, 0f, y, 0f)
            }
        }
    private val minRate
        get() = if (zoomOutDisabled) DEFAULT_RATE else MIN_RATE

    private val listener = GestureListener()
    private val detector = Detector()

    var doubleTapZoom = true

    var tapListener: ((MotionEvent) -> Unit)? = null
    var longTapListener: ((MotionEvent) -> Boolean)? = null

    private var isManuallyScrolling = false
    private var tapDuringManualScroll = false

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        halfWidth = measuredWidth / 2
        halfHeight = measuredHeight / 2
        if (!hasViewportSize && originalHeight == 0) {
            originalHeight = measuredHeight
        }
    }

    /** Called with the frame's viewport, never the possibly expanded recycler height. */
    fun onViewportSizeChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        val heightChanged = originalHeight != height
        val widthChanged = viewportWidth != width
        originalHeight = height
        hasViewportSize = true
        viewportWidth = width
        halfWidth = width / 2

        val restoreScale = pendingComfortScale
        if (restoreScale != null) {
            pendingComfortScale = null
            applyComfortScale(restoreScale)
            return
        }

        if (!heightChanged && !widthChanged) return
        updateScaleLayout(currentScale)
        if (currentScale < DEFAULT_RATE) {
            x = 0f
            y = WebtoonComfortScalePolicy.verticalOffset(originalHeight, layoutHeightForScale(currentScale))
        } else {
            x = getPositionX(x)
            y = getPositionY(y)
        }
    }

    /** Apply saved book comfort zoom once the reader has a real viewport. */
    fun restoreComfortScale(scale: Float) {
        if (zoomOutDisabled) return
        val rememberedScale = WebtoonComfortScalePolicy.rememberedScale(scale) ?: return
        cancelActiveZoomAnimations()
        if (!hasViewportSize || originalHeight <= 0) {
            pendingComfortScale = rememberedScale
            return
        }
        applyComfortScale(rememberedScale)
    }

    /** Reset the current reader shape to 1x and discard any deferred saved shrink scale. */
    fun resetComfortScale() {
        pendingComfortScale = null
        cancelActiveZoomAnimations()
        currentScale = DEFAULT_RATE
        zoomTargetRate = null
        setScaleRate(DEFAULT_RATE)
        updateScaleLayout(DEFAULT_RATE)
        x = 0f
        y = 0f
        requestLayout()
    }

    /** Only a deliberate sub-1 scale is persistent; magnification and reset mean no saved zoom. */
    fun comfortScaleForPersistence(): Float? = WebtoonComfortScalePolicy.rememberedScale(
        zoomTargetRate?.takeIf { isZooming } ?: pendingComfortScale ?: currentScale,
    )

    private fun applyComfortScale(scale: Float) {
        currentScale = scale
        setScaleRate(scale)
        updateScaleLayout(scale)
        x = 0f
        y = WebtoonComfortScalePolicy.verticalOffset(originalHeight, layoutHeightForScale(scale))
        requestLayout()
    }

    private fun cancelActiveZoomAnimations() {
        val scaleAnimator = zoomAnimator
        zoomAnimator = null
        scaleAnimator?.cancel()

        val flingAnimator = zoomFlingAnimator
        zoomFlingAnimator = null
        flingAnimator?.cancel()

        isZooming = false
        zoomTargetRate = null
        zoomFlingActive = false
        isManuallyScrolling = false
    }

    private fun layoutHeightForScale(scale: Float): Int =
        if (scale < DEFAULT_RATE) {
            WebtoonComfortScalePolicy.contentHeight(originalHeight, scale)
        } else {
            originalHeight
        }

    private fun updateScaleLayout(scale: Float) {
        val params = layoutParams ?: return
        val targetHeight = if (scale < DEFAULT_RATE) {
            layoutHeightForScale(scale)
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        if (params.height != targetHeight) {
            params.height = targetHeight
            layoutParams = params
        }
        halfHeight = if (scale < DEFAULT_RATE) layoutHeightForScale(scale) / 2 else originalHeight / 2
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            tapDuringManualScroll = isManuallyScrolling || scrollState != SCROLL_STATE_IDLE
        }

        detector.onTouchEvent(e)
        return super.onTouchEvent(e)
    }

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        manyueLastInteraction = SystemClock.uptimeMillis()
        ManyueReaderWorkGate.reportScroll(this, dx, dy, height)
        updateManyueWorkGate()
        updateFrameSampling()
        val layoutManager = layoutManager
        lastVisibleItemPosition =
            (layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
        firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        manyueLastInteraction = SystemClock.uptimeMillis()
        updateManyueWorkGate()
        updateFrameSampling()
        val layoutManager = layoutManager
        val visibleItemCount = layoutManager?.childCount ?: 0
        val totalItemCount = layoutManager?.itemCount ?: 0
        atLastPosition = visibleItemCount > 0 && lastVisibleItemPosition == totalItemCount - 1
        atFirstPosition = firstVisibleItemPosition == 0

        if (state == SCROLL_STATE_IDLE && !zoomFlingActive) {
            isManuallyScrolling = scrollState != SCROLL_STATE_IDLE
        }
    }

    private fun getPositionX(positionX: Float): Float {
        if (currentScale < 1) {
            return 0f
        }
        val maxPositionX = halfWidth * (currentScale - 1)
        return positionX.coerceIn(-maxPositionX, maxPositionX)
    }

    private fun getPositionY(positionY: Float): Float {
        if (currentScale < 1) {
            return WebtoonComfortScalePolicy.verticalOffset(originalHeight, layoutHeightForScale(currentScale))
        }
        val maxPositionY = halfHeight * (currentScale - 1)
        return positionY.coerceIn(-maxPositionY, maxPositionY)
    }

    private fun zoom(
        fromRate: Float,
        toRate: Float,
        fromX: Float,
        toX: Float,
        fromY: Float,
        toY: Float,
    ) {
        val previousAnimator = zoomAnimator
        zoomAnimator = null
        previousAnimator?.cancel()
        if (toRate >= DEFAULT_RATE && currentScale < DEFAULT_RATE) {
            updateScaleLayout(toRate)
        }
        isZooming = true
        zoomTargetRate = toRate
        val animatorSet = AnimatorSet()
        val translationXAnimator = ValueAnimator.ofFloat(fromX, toX)
        translationXAnimator.addUpdateListener { animation -> x = animation.animatedValue as Float }

        val translationYAnimator = ValueAnimator.ofFloat(fromY, toY)
        translationYAnimator.addUpdateListener { animation -> y = animation.animatedValue as Float }

        val scaleAnimator = ValueAnimator.ofFloat(fromRate, toRate)
        scaleAnimator.addUpdateListener { animation ->
            currentScale = animation.animatedValue as Float
            setScaleRate(currentScale)
        }
        animatorSet.playTogether(translationXAnimator, translationYAnimator, scaleAnimator)
        animatorSet.duration = ANIMATOR_DURATION_TIME.toLong()
        animatorSet.interpolator = DecelerateInterpolator()
        manyueAnimating++
        zoomAnimator = animatorSet
        updateManyueWorkGate()
        animatorSet.doOnEnd {
            manyueAnimating--
            manyueLastInteraction = SystemClock.uptimeMillis()
            if (zoomAnimator === animatorSet) {
                zoomAnimator = null
                isZooming = false
                zoomTargetRate = null
                currentScale = toRate
                setScaleRate(toRate)
                updateScaleLayout(toRate)
            }
            updateManyueWorkGate()
        }
        animatorSet.start()
    }

    fun zoomFling(velocityX: Int, velocityY: Int): Boolean {
        if (currentScale <= 1f) return false

        val distanceTimeFactor = 0.4f
        val animatorSet = AnimatorSet()

        if (velocityX != 0) {
            val dx = (distanceTimeFactor * velocityX / 2)
            val newX = getPositionX(x + dx)
            val translationXAnimator = ValueAnimator.ofFloat(x, newX)
            translationXAnimator.addUpdateListener { animation -> x = getPositionX(animation.animatedValue as Float) }
            animatorSet.play(translationXAnimator)
        }
        if (velocityY != 0 && (atFirstPosition || atLastPosition)) {
            val dy = (distanceTimeFactor * velocityY / 2)
            val newY = getPositionY(y + dy)
            val translationYAnimator = ValueAnimator.ofFloat(y, newY)
            translationYAnimator.addUpdateListener { animation -> y = getPositionY(animation.animatedValue as Float) }
            animatorSet.play(translationYAnimator)
        }

        if (animatorSet.childAnimations.isEmpty()) return false

        zoomFlingAnimator?.let { previousAnimator ->
            zoomFlingAnimator = null
            previousAnimator.cancel()
        }
        zoomFlingActive = true
        onManualScroll()

        animatorSet.duration = 400
        animatorSet.interpolator = DecelerateInterpolator()
        manyueAnimating++
        updateManyueWorkGate()
        var finished = false
        fun finishZoomFling() {
            if (finished) return
            finished = true
            manyueAnimating--
            manyueLastInteraction = SystemClock.uptimeMillis()
            if (zoomFlingAnimator === animatorSet) {
                zoomFlingAnimator = null
                zoomFlingActive = false
                isManuallyScrolling = scrollState != SCROLL_STATE_IDLE
            }
            updateManyueWorkGate()
        }
        animatorSet.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishZoomFling()
                override fun onAnimationCancel(animation: Animator) = finishZoomFling()
            },
        )
        zoomFlingAnimator = animatorSet
        animatorSet.start()

        return true
    }

    private fun zoomScrollBy(dx: Int, dy: Int) {
        if (dx != 0) {
            x = getPositionX(x + dx)
        }
        if (dy != 0) {
            y = getPositionY(y + dy)
        }
    }

    private fun setScaleRate(rate: Float) {
        scaleX = rate
        scaleY = rate
    }

    fun onScale(scaleFactor: Float) {
        currentScale *= scaleFactor
        currentScale = currentScale.coerceIn(
            minRate,
            MAX_SCALE_RATE,
        )

        setScaleRate(currentScale)
        updateScaleLayout(currentScale)

        if (currentScale != DEFAULT_RATE) {
            x = getPositionX(x)
            y = getPositionY(y)
        } else {
            x = 0f
            y = 0f
        }

        requestLayout()
    }

    fun onScaleBegin() {
        if (detector.isDoubleTapping) {
            detector.isQuickScaling = true
        }
    }

    fun onScaleEnd() {
        if (scaleX < minRate) {
            zoom(currentScale, minRate, x, 0f, y, 0f)
        }
    }

    private fun onManualScroll() {
        isManuallyScrolling = true
    }

    inner class GestureListener : GestureDetectorWithLongTap.Listener() {

        override fun onSingleTapConfirmed(ev: MotionEvent): Boolean {
            if (!tapDuringManualScroll) {
                tapListener?.invoke(ev)
            }
            return false
        }

        override fun onDoubleTap(ev: MotionEvent): Boolean {
            detector.isDoubleTapping = true
            return false
        }

        fun onDoubleTapConfirmed(ev: MotionEvent) {
            if (!isZooming && doubleTapZoom) {
                if (scaleX != DEFAULT_RATE) {
                    zoom(currentScale, DEFAULT_RATE, x, 0f, y, 0f)
                } else {
                    val toScale = 2f
                    val toX = (halfWidth - ev.x) * (toScale - 1)
                    val toY = (halfHeight - ev.y) * (toScale - 1)
                    zoom(DEFAULT_RATE, toScale, 0f, toX, 0f, toY)
                }
            }
        }

        override fun onLongTapConfirmed(ev: MotionEvent) {
            if (longTapListener?.invoke(ev) == true) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    inner class Detector : GestureDetectorWithLongTap(context, listener) {

        private var scrollPointerId = 0
        private var downX = 0
        private var downY = 0
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var isZoomDragging = false
        var isDoubleTapping = false
        var isQuickScaling = false

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val action = ev.actionMasked
            val actionIndex = ev.actionIndex

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    scrollPointerId = ev.getPointerId(0)
                    downX = (ev.x + 0.5f).toInt()
                    downY = (ev.y + 0.5f).toInt()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    scrollPointerId = ev.getPointerId(actionIndex)
                    downX = (ev.getX(actionIndex) + 0.5f).toInt()
                    downY = (ev.getY(actionIndex) + 0.5f).toInt()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDoubleTapping && isQuickScaling) {
                        return true
                    }

                    val index = ev.findPointerIndex(scrollPointerId)
                    if (index < 0) {
                        return false
                    }

                    val x = (ev.getX(index) + 0.5f).toInt()
                    val y = (ev.getY(index) + 0.5f).toInt()
                    var dx = x - downX
                    var dy = if (atFirstPosition || atLastPosition) y - downY else 0

                    if (!isZoomDragging && currentScale > 1f) {
                        var startScroll = false

                        if (abs(dx) > touchSlop) {
                            if (dx < 0) {
                                dx += touchSlop
                            } else {
                                dx -= touchSlop
                            }
                            startScroll = true
                        }
                        if (abs(dy) > touchSlop) {
                            if (dy < 0) {
                                dy += touchSlop
                            } else {
                                dy -= touchSlop
                            }
                            startScroll = true
                        }

                        if (startScroll) {
                            isZoomDragging = true
                        }
                    }

                    if (isZoomDragging) {
                        zoomScrollBy(dx, dy)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDoubleTapping && !isQuickScaling) {
                        listener.onDoubleTapConfirmed(ev)
                    }
                    isZoomDragging = false
                    isDoubleTapping = false
                    isQuickScaling = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    isZoomDragging = false
                    isDoubleTapping = false
                    isQuickScaling = false
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}

private const val ANIMATOR_DURATION_TIME = 200
private const val MIN_RATE = 0.5f
private const val DEFAULT_RATE = 1f
private const val MAX_SCALE_RATE = 3f
