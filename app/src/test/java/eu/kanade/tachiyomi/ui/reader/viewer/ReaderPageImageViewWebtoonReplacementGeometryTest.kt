package eu.kanade.tachiyomi.ui.reader.viewer

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH
import java.io.File
import java.util.concurrent.Executor
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
class ReaderPageImageViewWebtoonReplacementGeometryTest {

    @Test fun stagedReplacementUsesActiveFitWidthHeightAfterWidthRoundTrip() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val source = Bitmap.createBitmap(720, 562, Bitmap.Config.ARGB_8888)
        val aiBitmap = Bitmap.createBitmap(720, 562, Bitmap.Config.ARGB_8888)
        val aiFile = File(activity.cacheDir, "webtoon-fit-width-replacement.png")
        aiFile.outputStream().use { output ->
            check(aiBitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        aiBitmap.recycle()

        val config = ReaderPageImageView.Config(
            zoomDuration = 1,
            minimumScaleType = SCALE_TYPE_FIT_WIDTH,
        )
        val frame = BitmapWebtoonFrame(activity)
        frame.setImage(BitmapDrawable(activity.resources, source), config)
        val parent = FrameLayout(activity)
        parent.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        try {
            layoutAndDraw(parent, 1200, 1200)
            assertTrue(activeImage(frame).isReady)
            assertEquals(720, activeImage(frame).sWidth)
            assertEquals(562, activeImage(frame).sHeight)
            assertEquals(936, frame.measuredHeight)

            // A width round-trip changes the active SSIV viewport while its page stays measured
            // from the real bitmap's FIT_WIDTH aspect ratio.
            layoutAndDraw(parent, 600, 1200)
            assertEquals(468, frame.measuredHeight)

            // Fold return can temporarily measure the item to the viewport height. Stage a real
            // tiled replacement during that pass, then let the wrap-content parent measure again.
            frame.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1200)
            layoutAndDraw(parent, 1200, 1200)
            assertEquals(1200, frame.measuredHeight)

            frame.setTiledImagePreservingCurrent(
                file = aiFile,
                config = config,
                shouldCommit = { false },
                maySwap = { false },
                onReady = { _, _ -> },
                onError = { throw AssertionError(it) },
            )
            frame.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutAndDraw(parent, 1200, 1200)

            assertEquals("staging must not pin the transient viewport height", 936, frame.measuredHeight)
            assertEquals(936, activeImage(frame).measuredHeight)
        } finally {
            frame.cancelStagedImage()
            frame.recycle()
            if (!source.isRecycled) source.recycle()
            aiFile.delete()
            activity.finish()
        }
    }

    private fun activeImage(frame: ReaderPageImageView): SubsamplingScaleImageView =
        (0 until frame.childCount)
            .asSequence()
            .mapNotNull { frame.getChildAt(it) as? SubsamplingScaleImageView }
            .first { it.isReady }

    private fun layoutAndDraw(parent: FrameLayout, width: Int, height: Int) {
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, width, height)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            parent.draw(Canvas(output))
        } finally {
            output.recycle()
        }
    }

    private class BitmapWebtoonFrame(context: Activity) : ReaderPageImageView(context, isWebtoon = true) {
        override fun aiImageExecutor(): Executor = Executor { }
    }
}
