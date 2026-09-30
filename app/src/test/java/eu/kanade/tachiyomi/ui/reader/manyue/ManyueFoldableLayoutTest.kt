package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import org.junit.Assert.assertEquals
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
class ManyueFoldableLayoutTest {
    @Test fun wideningManualPageUsesViewportInsteadOfOldPageWidth() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val viewport = FrameLayout(activity)
        val page = ReaderPageImageView(activity)
        viewport.addView(page, FrameLayout.LayoutParams(1400, 800))
        activity.setContentView(viewport)
        val oldMode = ManyueRuntimeState.foldableMode
        val oldWidth = ManyueRuntimeState.foldableTargetWidth
        try {
            val exact = View.MeasureSpec.EXACTLY
            viewport.measure(View.MeasureSpec.makeMeasureSpec(2000, exact), View.MeasureSpec.makeMeasureSpec(800, exact))
            viewport.layout(0, 0, 2000, 800)
            assertEquals(1400, page.width)
            ManyueFoldableController.onConfigurationChanged(Configuration().apply { smallestScreenWidthDp = 700 })
            ManyueRuntimeState.foldableMode = 1
            ManyueRuntimeState.foldableTargetWidth = 1800
            ManyueFoldableController.applyToTree(viewport)
            shadowOf(Looper.getMainLooper()).idle()
            val params = page.layoutParams as FrameLayout.LayoutParams
            assertEquals(1800, params.width)
            assertEquals(100, params.marginStart)
            assertEquals(100, params.marginEnd)

            ManyueRuntimeState.foldableMode = 0
            ManyueFoldableController.applyToTree(viewport)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(FrameLayout.LayoutParams.MATCH_PARENT, params.width)
            assertEquals(0, params.marginStart)
        } finally {
            ManyueRuntimeState.foldableMode = oldMode
            ManyueRuntimeState.foldableTargetWidth = oldWidth
            ManyueFoldableController.onConfigurationChanged(activity.resources.configuration)
            controller.pause().stop().destroy()
        }
    }
}
