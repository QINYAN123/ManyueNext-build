package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.app.Activity
import android.app.Application
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class WebtoonComfortScaleResetTest {

    @Test fun `reset before viewport clears deferred shrink`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val recycler = newRecycler(controller.get())

        recycler.restoreComfortScale(0.8f)
        assertEquals(0.8f, recycler.comfortScaleForPersistence() ?: -1f, 0f)

        recycler.resetComfortScale()
        recycler.onViewportSizeChanged(1000, 1200)

        assertEquals(1f, recycler.scaleX, 0f)
        assertEquals(1f, recycler.scaleY, 0f)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, recycler.layoutParams.height)
        assertNull(recycler.comfortScaleForPersistence())
        controller.pause().stop().destroy()
    }

    @Test fun `reset returns an applied shrink to full viewport scale`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val recycler = newRecycler(controller.get())
        recycler.onViewportSizeChanged(1000, 1200)
        recycler.restoreComfortScale(0.8f)

        assertEquals(0.8f, recycler.scaleX, 0f)
        assertEquals(1500, recycler.layoutParams.height)

        recycler.resetComfortScale()

        assertEquals(1f, recycler.scaleX, 0f)
        assertEquals(1f, recycler.scaleY, 0f)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, recycler.layoutParams.height)
        assertNull(recycler.comfortScaleForPersistence())
        controller.pause().stop().destroy()
    }

    private fun newRecycler(activity: Activity): WebtoonRecyclerView = WebtoonRecyclerView(activity).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
}
