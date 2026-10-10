package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.animation.AnimatorSet
import android.app.Activity
import android.app.Application
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WebtoonFrameFlingTapTest {

    @Test fun `unhandled shrink flings leave center tap available for menu`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val (frame, recycler) = createReader(activity)
        recycler.restoreComfortScale(0.8f)
        var tapCount = 0
        recycler.tapListener = { tapCount++ }

        val start = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 500f, 500f, 0)
        val end = MotionEvent.obtain(0, 60, MotionEvent.ACTION_UP, 560f, 500f, 0)
        repeat(6) {
            assertFalse(frame.FlingListener().onFling(start, end, 1000f, 0f))
        }

        // Exercise RecyclerView's real down path, which snapshots the sticky suppression flag.
        recycler.onTouchEvent(start)
        singleTapListener(recycler).onSingleTapConfirmed(end)

        assertFalse(booleanField(recycler, "isManuallyScrolling"))
        assertEquals(1, tapCount)

        start.recycle()
        end.recycle()
        controller.pause().stop().destroy()
    }

    @Test fun `handled zoom fling clears tap suppression when cancelled`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val (_, recycler) = createReader(activity)
        recycler.onScale(2f)

        assertTrue(recycler.zoomFling(1000, 0))
        assertTrue(booleanField(recycler, "isManuallyScrolling"))
        setScrollState(recycler, RecyclerView.SCROLL_STATE_SETTLING)
        notifyScrollStateChanged(recycler, RecyclerView.SCROLL_STATE_SETTLING)

        (field(recycler, "zoomFlingAnimator") as AnimatorSet).cancel()

        assertTrue(booleanField(recycler, "isManuallyScrolling"))
        setScrollState(recycler, RecyclerView.SCROLL_STATE_IDLE)
        notifyScrollStateChanged(recycler, RecyclerView.SCROLL_STATE_IDLE)
        assertFalse(booleanField(recycler, "isManuallyScrolling"))
        assertFalse(booleanField(recycler, "zoomFlingActive"))
        controller.pause().stop().destroy()
    }

    private fun createReader(activity: Activity): Pair<WebtoonFrame, WebtoonRecyclerView> {
        val frame = WebtoonFrame(activity)
        val recycler = WebtoonRecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
        }
        frame.addView(
            recycler,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        activity.setContentView(frame)

        val size = View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY)
        frame.measure(size, size)
        frame.layout(0, 0, 1000, 1000)
        return frame to recycler
    }

    private fun singleTapListener(recycler: WebtoonRecyclerView): WebtoonRecyclerView.GestureListener =
        field(recycler, "listener") as WebtoonRecyclerView.GestureListener

    private fun booleanField(recycler: WebtoonRecyclerView, name: String): Boolean =
        (field(recycler, name) as Boolean)

    private fun setScrollState(recycler: WebtoonRecyclerView, state: Int) {
        RecyclerView::class.java.getDeclaredField("mScrollState").apply { isAccessible = true }
            .setInt(recycler, state)
    }

    private fun notifyScrollStateChanged(recycler: WebtoonRecyclerView, state: Int) {
        WebtoonRecyclerView::class.java.getDeclaredMethod("onScrollStateChanged", Int::class.javaPrimitiveType!!)
            .apply { isAccessible = true }
            .invoke(recycler, state)
    }

    private fun field(recycler: WebtoonRecyclerView, name: String): Any? =
        WebtoonRecyclerView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(recycler)
}
