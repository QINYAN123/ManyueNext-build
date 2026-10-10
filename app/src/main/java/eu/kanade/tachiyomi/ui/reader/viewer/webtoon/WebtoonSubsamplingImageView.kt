package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.content.Context
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderSubsamplingScaleImageView

/**
 * Implementation of subsampling scale image view that ignores all touch events, because the
 * webtoon viewer handles all the gestures.
 */
class WebtoonSubsamplingImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ReaderSubsamplingScaleImageView(context, attrs) {

    /**
     * Webtoon pages use FIT_WIDTH, so a width change must preserve the scale relative to the new
     * width. SSIV's default keeps its old pixel scale, which crops the page after moving to a
     * narrower viewport. Its callback reads the center after the View already has its new size;
     * translate that center back to the old viewport before asking SSIV to apply the normalized
     * scale. SSIV then clamps centers that cannot remain visible at the new bounds and reloads
     * tiles through its normal pre-draw path.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val oldScale = scale
        val wasReady = isReady
        super.onSizeChanged(w, h, oldw, oldh)

        if (!wasReady || oldw <= 0 || w <= 0 || oldw == w || oldScale <= 0f || !oldScale.isFinite()) return

        val newViewportCenter = center ?: return
        val oldViewportCenter = PointF(
            newViewportCenter.x + (oldw / 2 - w / 2) / oldScale,
            newViewportCenter.y + (oldh / 2 - h / 2) / oldScale,
        )
        val newScale = oldScale * w / oldw
        if (newScale <= 0f || !newScale.isFinite()) return

        setScaleAndCenter(newScale, oldViewportCenter)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return false
    }
}
