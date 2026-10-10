package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.view.View
import android.view.ViewGroup
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.PAN_LIMIT_INSIDE
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class WebtoonSubsamplingImageViewResizeTest {

    @Test fun widthResizeNormalizesFitWidthScaleAndPreservesVisibleSourceCenter() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val source = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
        val view = WebtoonSubsamplingImageView(activity).apply {
            setMinimumScaleType(SCALE_TYPE_FIT_WIDTH)
            setPanLimit(PAN_LIMIT_INSIDE)
            setImage(ImageSource.bitmap(source))
        }
        activity.setContentView(view, ViewGroup.LayoutParams(800, 640))

        try {
            layoutAndDraw(view, 800, 640)
            assertTrue("Bitmap-backed SSIV should be ready after a real draw", view.isReady)
            val initialFitScale = view.scale
            assertEquals(0.8f, initialFitScale, 0.01f)

            val zoomScale = initialFitScale * 1.2f
            val sourceCenter = PointF(550f, 400f)
            view.setScaleAndCenter(zoomScale, sourceCenter)
            layoutAndDraw(view, 800, 640)
            assertEquals(zoomScale, view.scale, 0.01f)
            assertCenter(view, sourceCenter)

            // Mimics a narrower foldable viewport. Both scale and center stay tied to source
            // coordinates, while the actual SSIV draw/pre-draw path applies the pending state.
            layoutAndDraw(view, 400, 320)
            val normalizedScale = zoomScale * 400f / 800f
            assertEquals(normalizedScale, view.scale, 0.01f)
            assertCenter(view, sourceCenter)

            // A height-only change must not change the FIT_WIDTH scale.
            layoutAndDraw(view, 400, 500)
            assertEquals(normalizedScale, view.scale, 0.01f)
        } finally {
            view.recycle()
            if (!source.isRecycled) source.recycle()
            activity.finish()
        }
    }

    @Test fun widthResizeAtFitWidthAllowsBoundsToClampAnUnreachableCenter() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val source = Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888)
        val view = WebtoonSubsamplingImageView(activity).apply {
            setMinimumScaleType(SCALE_TYPE_FIT_WIDTH)
            setPanLimit(PAN_LIMIT_INSIDE)
            setImage(ImageSource.bitmap(source))
        }
        activity.setContentView(view, ViewGroup.LayoutParams(800, 640))

        try {
            layoutAndDraw(view, 800, 640)
            assertTrue(view.isReady)
            val fitScale = view.scale

            // At FIT_WIDTH the complete source width is already visible, so SSIV must clamp
            // this requested off-center point to the only feasible center before resizing.
            view.setScaleAndCenter(fitScale, PointF(300f, 200f))
            layoutAndDraw(view, 800, 640)
            assertCenter(view, PointF(500f, 400f))

            layoutAndDraw(view, 400, 320)
            assertEquals(fitScale * 0.5f, view.scale, 0.01f)
            assertCenter(view, PointF(500f, 400f))
        } finally {
            view.recycle()
            if (!source.isRecycled) source.recycle()
            activity.finish()
        }
    }

    private fun layoutAndDraw(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(output))
        } finally {
            output.recycle()
        }
    }

    private fun assertCenter(view: WebtoonSubsamplingImageView, expected: PointF) {
        val actual = requireNotNull(view.center)
        assertEquals(expected.x, actual.x, 1f)
        assertEquals(expected.y, actual.y, 1f)
    }
}
