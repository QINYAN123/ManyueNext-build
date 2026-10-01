package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Activity
import android.app.Application
import android.os.Looper
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import androidx.recyclerview.widget.RecyclerView
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManyueDisplayCommitTest {
    @Test fun stableScrollingCanSwapAndThermalAloneDoesNotHideAReadyImage() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val recycler = WebtoonRecyclerView(controller.get())
        controller.get().setContentView(recycler)
        val state = RecyclerView::class.java.getDeclaredField("mScrollState").apply { isAccessible = true }
        try {
            ManyueReaderWorkGate.update(recycler, true)
            state.setInt(recycler, RecyclerView.SCROLL_STATE_DRAGGING)
            assertTrue("Normal scrolling need not wait for idle", recycler.canSwapManyueImage())
            repeat(3) { ManyueReaderWorkGate.reportFrame(recycler, 40_000_000L, 16_666_667L) }
            assertFalse("Repeated missed frames protect scrolling", recycler.canSwapManyueImage())
            repeat(2) { ManyueReaderWorkGate.reportFrame(recycler, 16_666_667L, 16_666_667L) }
            ManyueReaderWorkGate.reportThermalStatus(recycler, ManyueReaderWorkGate.THERMAL_STATUS_SEVERE)
            assertTrue("Severe thermal status still pauses native inference", ManyueReaderWorkGate.isBlocked())
            assertFalse(ManyueReaderWorkGate.isDisplayBlocked())
            assertTrue("A completed visible result can commit after frame pressure clears", recycler.canSwapManyueImage())
        } finally {
            state.setInt(recycler, RecyclerView.SCROLL_STATE_IDLE)
            ManyueReaderWorkGate.release(recycler)
            controller.pause().stop().destroy()
        }
    }

    @Test fun replacementFramesAreSampledAfterScrollingStops() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val recycler = WebtoonRecyclerView(controller.get())
        controller.get().setContentView(recycler)
        val field = WebtoonRecyclerView::class.java.getDeclaredField("frameCallbackPosted").apply { isAccessible = true }
        try {
            assertFalse(field.getBoolean(recycler))
            recycler.noteManyueImageSwap()
            assertTrue(field.getBoolean(recycler))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertFalse("Sampling stops after the bounded replacement window", field.getBoolean(recycler))
        } finally {
            ManyueReaderWorkGate.release(recycler)
            controller.pause().stop().destroy()
        }
    }
}
