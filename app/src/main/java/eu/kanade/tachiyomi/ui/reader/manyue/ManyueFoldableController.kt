package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.ViewPager
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView

/**
 * Tracks width configuration; the viewer owns chapter/page/scroll restoration.
 * On regular phones this is effectively a no-op.
 */
object ManyueFoldableController {

    data class ReaderPosition(
        val chapterId: Long = 0L,
        val pageIndex: Int = 0,
        val fraction: Float = 0f,
    )

    @Volatile
    private var lastConfig: Configuration? = null

    @Volatile
    private var position = ReaderPosition()

    fun isFoldable(): Boolean =
        lastConfig?.smallestScreenWidthDp?.let {
            ManyueFoldableWidthPolicy.isInnerScreen(it)
        } ?: false

    fun onConfigurationChanged(newConfig: Configuration) {
        // Keep our own snapshot: Resources may mutate and reuse the supplied object.
        lastConfig = Configuration(newConfig)
    }

    fun savePosition(chapterId: Long, pageIndex: Int, fraction: Float) {
        position = ReaderPosition(chapterId, pageIndex, fraction)
    }

    fun restorePosition(): ReaderPosition = position

    fun suggestedTargetWidth(screenWidthPx: Int, samples: List<Pair<Int, Int>>, mode: Int, manual: Int): Int {
        if (!isFoldable()) return screenWidthPx
        return ManyueFoldableWidthPolicy.computeTargetWidth(screenWidthPx, samples, mode, manual)
    }

    private val nativeSamples = mutableListOf<Pair<Int, Int>>()
    private const val MAX_SAMPLES = 9

    fun recordSample(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(nativeSamples) {
            if (nativeSamples.size >= MAX_SAMPLES) nativeSamples.removeAt(0)
            nativeSamples.add(width to height)
        }
    }

    fun clearSamples() {
        synchronized(nativeSamples) { nativeSamples.clear() }
    }

    fun applyToView(view: View, screenWidthPx: Int) {
        val parentWidth = (view.parent as? View)?.width?.takeIf { it > 0 }
        val availableWidth = minOf(screenWidthPx, parentWidth ?: screenWidthPx)
        val mode = ManyueRuntimeState.foldableMode
        val shouldUseFullWidth = !isFoldable() || mode == 0 || availableWidth <= 0
        val samples = synchronized(nativeSamples) { nativeSamples.toList() }
        val manual = ManyueRuntimeState.foldableTargetWidth
        val targetWidth = if (shouldUseFullWidth) {
            availableWidth
        } else {
            ManyueFoldableWidthPolicy.computeTargetWidth(availableWidth, samples, mode, manual)
                .coerceAtMost(availableWidth)
        }
        view.post {
            val lp = view.layoutParams
            var layoutChanged = false
            if (view.parent is ViewPager) {
                val inset = ((availableWidth - targetWidth) / 2).coerceAtLeast(0)
                if (lp != null && lp.width != ViewGroup.LayoutParams.MATCH_PARENT) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    view.layoutParams = lp
                    layoutChanged = true
                }
                if (view.paddingLeft != inset || view.paddingRight != inset) {
                    view.setPadding(inset, view.paddingTop, inset, view.paddingBottom)
                    layoutChanged = true
                }
            } else {
                val target = targetWidth.takeIf { it < availableWidth } ?: ViewGroup.LayoutParams.MATCH_PARENT
                val inset = ((availableWidth - targetWidth) / 2).coerceAtLeast(0)
                if (view.paddingLeft != 0 || view.paddingRight != 0) {
                    view.setPadding(0, view.paddingTop, 0, view.paddingBottom)
                    layoutChanged = true
                }
                (lp as? ViewGroup.MarginLayoutParams)?.let { margins ->
                    if (margins.marginStart != inset || margins.marginEnd != inset) {
                        margins.marginStart = inset
                        margins.marginEnd = inset
                        layoutChanged = true
                    }
                }
                if (lp != null && lp.width != target) {
                    lp.width = target
                    view.layoutParams = lp
                    layoutChanged = true
                }
            }
            if (layoutChanged) view.requestLayout()
        }
    }

    /** Reapply to already-created Pager/Webtoon image views after a fold state change. */
    fun applyToTree(root: View) {
        val screenWidth = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
        applyToTree(root, screenWidth)
    }

    private fun applyToTree(root: View, screenWidth: Int) {
        // Preserve the viewport width through traversal. A page may still have the old
        // manually constrained width; using that as the viewport prevents it growing again.
        if (root is ReaderPageImageView) applyToView(root, screenWidth)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) applyToTree(root.getChildAt(index), screenWidth)
        }
    }
}
