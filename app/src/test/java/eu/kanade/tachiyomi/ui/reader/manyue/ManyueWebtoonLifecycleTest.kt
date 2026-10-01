package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonRecyclerView
import java.io.File
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercise Android's real touch routing and the actual SSIV base-layer callback contract. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManyueWebtoonLifecycleTest {
    @Test fun originalDecodersShareTwoWorkersAcrossPages() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val createView = ReaderPageImageView::class.java.getDeclaredMethod("createSubsamplingImageView")
            .apply { isAccessible = true }
        val executorField = SubsamplingScaleImageView::class.java.getDeclaredField("executor")
            .apply { isAccessible = true }
        val firstView = createView.invoke(Strip(activity)) as SubsamplingScaleImageView
        val secondView = createView.invoke(Strip(activity)) as SubsamplingScaleImageView
        val executor = executorField.get(firstView) as Executor
        assertSame(executor, executorField.get(secondView))
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val third = CountDownLatch(1)
        try {
            repeat(2) {
                executor.execute { started.countDown(); release.await() }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            executor.execute { third.countDown() }
            assertFalse(third.await(150, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        assertTrue(third.await(5, TimeUnit.SECONDS))
        firstView.recycle()
        secondView.recycle()
        activity.finish()
    }

    private fun createDecoderBackedView(
        activity: Activity,
        decoder: BlockingRegionDecoder,
        fileName: String,
    ): Pair<SubsamplingScaleImageView, java.util.concurrent.ExecutorService> {
        val executor = Executors.newSingleThreadExecutor()
        val view = SubsamplingScaleImageView(activity).apply {
            setExecutor(executor)
            setMaxTileSize(512)
            setRegionDecoderFactory { decoder }
        }
        layout(view)
        view.setImage(ImageSource.uri(activity, Uri.fromFile(pendingFile(activity, fileName))))
        return view to executor
    }

    @Test fun detachedDisposeDoesNotWaitForRegionReadAndReleasesLeaseAfterDecoder() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val allowDecodeToFinish = CountDownLatch(1)
        val decoder = BlockingRegionDecoder(decodeRelease = allowDecodeToFinish)
        val (view, executor) = createDecoderBackedView(activity, decoder, "dispose-blocked-tile.webp")
        val leaseReleased = CountDownLatch(1)
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val releaseWatchdog = watchdog.schedule({ allowDecodeToFinish.countDown() }, 2, TimeUnit.SECONDS)
        try {
            assertTrue(decoder.initEntered.await(5, TimeUnit.SECONDS))
            // initEntered is signalled from inside the decoder factory/init worker. Wait for the
            // AsyncTask to finish and enqueue its main-thread result before draining the paused
            // looper; otherwise idle() can run before onPostExecute schedules the tile read.
            executor.submit { }.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Expected a tile read to hold the decoder lock", decoder.decodeEntered.await(5, TimeUnit.SECONDS))

            val startNanos = System.nanoTime()
            view.disposeDetached { leaseReleased.countDown() }
            val disposeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            assertTrue("disposeDetached blocked the UI caller for ${disposeMillis}ms", disposeMillis < 500)
            assertFalse("decoder was recycled before its region read finished", decoder.recycleCalled)
            assertFalse("cache lease closed before decoder release", leaseReleased.await(100, TimeUnit.MILLISECONDS))

            allowDecodeToFinish.countDown()
            releaseWatchdog.cancel(false)
            // AsyncTask posts onPostExecute from FutureTask.done(); wait until that post exists
            // before draining the paused main looper and asserting stale-output cleanup.
            executor.submit { }.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("background disposal did not finish", leaseReleased.await(5, TimeUnit.SECONDS))
            assertTrue(decoder.recycleCalled)
            assertFalse(decoder.recycledBeforeDecodeReturned)
            assertTrue("late tile output must be recycled after the generation changes", decoder.decodedBitmap?.isRecycled == true)
        } finally {
            allowDecodeToFinish.countDown()
            releaseWatchdog.cancel(false)
            watchdog.shutdownNow()
            executor.shutdownNow()
            activity.finish()
        }
    }

    @Test fun detachedDisposeKeepsLeaseUntilPendingInitDecoderIsReleased() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val allowInitToFinish = CountDownLatch(1)
        val decoder = BlockingRegionDecoder(initRelease = allowInitToFinish)
        val (view, executor) = createDecoderBackedView(activity, decoder, "dispose-blocked-init.webp")
        val leaseReleased = CountDownLatch(1)
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val releaseWatchdog = watchdog.schedule({ allowInitToFinish.countDown() }, 2, TimeUnit.SECONDS)
        try {
            assertTrue(decoder.initEntered.await(5, TimeUnit.SECONDS))

            val startNanos = System.nanoTime()
            view.disposeDetached { leaseReleased.countDown() }
            val disposeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            assertTrue("disposeDetached waited for decoder init on the UI caller", disposeMillis < 500)
            assertFalse(decoder.recycleCalled)
            assertFalse("cache lease closed while init still owned its decoder", leaseReleased.await(100, TimeUnit.MILLISECONDS))

            allowInitToFinish.countDown()
            releaseWatchdog.cancel(false)
            // The decoder is transferred or deferred from TilesInitTask.onPostExecute on main.
            executor.submit { }.get(5, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("deferred decoder was not released", leaseReleased.await(5, TimeUnit.SECONDS))
            assertTrue(decoder.recycleCalled)
            assertFalse(decoder.recycledBeforeInitReturned)
        } finally {
            allowInitToFinish.countDown()
            releaseWatchdog.cancel(false)
            watchdog.shutdownNow()
            executor.shutdownNow()
            activity.finish()
        }
    }

    @Test fun detachedDisposeDoesNotWaitForQueuedInitThatNeverStarted() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val decoder = BlockingRegionDecoder()
        val view = SubsamplingScaleImageView(activity).apply {
            setExecutor(Executor { }) // Deliberately retain the queued task without running it.
            setRegionDecoderFactory { decoder }
            setImage(ImageSource.uri(activity, Uri.fromFile(pendingFile(activity, "dispose-queued-init.webp"))))
        }
        val leaseReleased = CountDownLatch(1)
        try {
            view.disposeDetached { leaseReleased.countDown() }
            assertTrue("disposal waited for an init task that never acquired decoder resources", leaseReleased.await(3, TimeUnit.SECONDS))
            assertFalse(decoder.initEntered.await(100, TimeUnit.MILLISECONDS))
            assertFalse(decoder.recycleCalled)
        } finally {
            activity.finish()
        }
    }

    private val config = ReaderPageImageView.Config(
        zoomDuration = 1,
        minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_FIT_WIDTH,
    )

    private class Strip(context: android.content.Context) : ReaderPageImageView(context, isWebtoon = true) {
        // Keep file decoding pending so tests can deliver controlled bitmap/preview callbacks.
        override fun aiImageExecutor(): Executor = Executor { }
    }

    private class PendingImageView(context: Context) : ReaderPageImageView(context) {
        override fun aiImageExecutor(): Executor = Executor { }
    }

    private class BlockingRegionDecoder(
        private val initRelease: CountDownLatch? = null,
        private val decodeRelease: CountDownLatch? = null,
    ) : ImageRegionDecoder {
        val initEntered = CountDownLatch(1)
        val initReturned = CountDownLatch(1)
        val decodeEntered = CountDownLatch(1)
        val decodeReturned = CountDownLatch(1)
        @Volatile var decodedBitmap: Bitmap? = null
        @Volatile var recycleCalled = false
        @Volatile var recycledBeforeInitReturned = false
        @Volatile var recycledBeforeDecodeReturned = false

        override fun init(context: Context, provider: InputProvider): Point {
            initEntered.countDown()
            check(initRelease?.await(5, TimeUnit.SECONDS) != false) { "Timed out waiting to finish fake decoder init" }
            initReturned.countDown()
            return Point(2400, 3800)
        }

        override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
            decodeEntered.countDown()
            check(decodeRelease?.await(5, TimeUnit.SECONDS) != false) { "Timed out waiting to finish fake tile decode" }
            return Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also {
                decodedBitmap = it
                decodeReturned.countDown()
            }
        }

        override fun isReady(): Boolean = !recycleCalled

        override fun recycle() {
            recycledBeforeInitReturned = initReturned.count != 0L
            recycledBeforeDecodeReturned = decodeReturned.count != 0L
            recycleCalled = true
        }
    }

    private val landscapeConfig = ReaderPageImageView.Config(
        zoomDuration = 1,
        minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE,
        landscapeZoom = true,
    )

    private fun touch(target: View, action: Int) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, 50f, 50f, 0).let {
            target.dispatchTouchEvent(it)
            it.recycle()
        }
    }

    private fun draw(view: View) {
        val bitmap = Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.recycle()
    }

    private fun layout(view: View, width: Int = 200, height: Int = 400) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun landscapeView(activity: Activity): Pair<ReaderPageImageView, SubsamplingScaleImageView> {
        val imageView = ReaderPageImageView(activity)
        imageView.setImage(
            BitmapDrawable(activity.resources, Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)),
            landscapeConfig,
        )
        activity.setContentView(imageView, ViewGroup.LayoutParams(200, 400))
        layout(imageView)
        draw(imageView)
        val image = imageView.getChildAt(0) as SubsamplingScaleImageView
        check(image.isReady)
        imageView.onPageSelected(forward = true)
        return imageView to image
    }

    private fun sendTileLoadError(view: SubsamplingScaleImageView, error: Exception) {
        val listener = SubsamplingScaleImageView::class.java
            .getDeclaredField("onImageEventListener")
            .apply { isAccessible = true }
            .get(view) as SubsamplingScaleImageView.OnImageEventListener
        listener.onTileLoadError(error)
    }

    private fun pendingFile(context: Context, name: String): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            val encoded = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP, 100, it) }
            check(encoded && file.isFile && file.length() > 0L) { "Could not create the WebP lifecycle fixture" }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    @Test fun rejectedStripDownCannotLatchInteractionForever() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val strip = Strip(activity)
        layout(strip)
        touch(strip, MotionEvent.ACTION_DOWN)
        // A strip rejects DOWN; Android does not deliver its later UP to this child.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertFalse(strip.isManyueInteractionActive())
        activity.finish()
    }

    @Test fun recyclerOwnsTouchEvenWhenStripRejectsDownAndClearsUpAndCancel() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val recycler = WebtoonRecyclerView(activity)
        recycler.layoutManager = LinearLayoutManager(activity)
        lateinit var strip: Strip
        recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = 1
            override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
                strip = Strip(activity)
                strip.layoutParams = RecyclerView.LayoutParams(200, 2000)
                return object : RecyclerView.ViewHolder(strip) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
        }
        activity.setContentView(recycler, ViewGroup.LayoutParams(200, 400))
        layout(recycler)
        touch(recycler, MotionEvent.ACTION_DOWN)
        assertTrue(strip.isManyueInteractionActive())
        assertEquals(android.os.PowerManager.THERMAL_STATUS_SEVERE, ManyueReaderWorkGate.THERMAL_STATUS_SEVERE)
        assertFalse(ManyueReaderWorkGate.isBlocked())
        touch(recycler, MotionEvent.ACTION_UP)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertFalse(strip.isManyueInteractionActive())
        touch(recycler, MotionEvent.ACTION_DOWN)
        touch(recycler, MotionEvent.ACTION_CANCEL)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertFalse(strip.isManyueInteractionActive())
        assertFalse(ManyueReaderWorkGate.isBlocked())
        activity.setContentView(View(activity))
        assertFalse(ManyueReaderWorkGate.isBlocked())
        activity.finish()
    }

    @Test fun readyCallbackDoesNotCommitBeforeLoadedFlagAndIdle() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val strip = Strip(activity)
        strip.setImage(BitmapDrawable(activity.resources, Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)), config)
        activity.setContentView(strip, ViewGroup.LayoutParams(200, 400))
        layout(strip)
        draw(strip)
        val old = strip.getChildAt(0)
        var swapAllowed = false
        var swaps = 0
        val swapChecks = mutableListOf<Boolean>()
        lateinit var staged: SubsamplingScaleImageView
        val file = pendingFile(activity, "pending-strip.webp")
        strip.setTiledImagePreservingCurrent(file, config, { true }, {
            swapChecks += staged.isImageLoaded
            swapAllowed
        }, { _, _ -> swaps++ }, { error -> throw AssertionError(error) })
        layout(strip)
        staged = strip.getChildAt(0) as SubsamplingScaleImageView
        // The actual library emits onReady before setting imageLoadedSent. Its subsequent
        // onImageLoaded callback must trigger the commit, even if no new draw occurs.
        staged.setImage(ImageSource.bitmap(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)))
        draw(strip)
        assertTrue(staged.isReady)
        assertTrue(staged.isImageLoaded)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(swapChecks.isNotEmpty())
        assertTrue(swapChecks.all { it })
        assertSame(old, strip.getChildAt(1))
        assertEquals(0, swaps)
        swapAllowed = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(1, swaps)
        assertSame(staged, strip.getChildAt(0))
        assertEquals(1, strip.childCount)
        strip.recycle()
        activity.finish()
    }

    @Test fun replacementKeepsStripHeightAndDoesNotEmitAnotherInitialLoad() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val strip = Strip(activity)
        var initialLoads = 0
        strip.onImageLoaded = { initialLoads++ }
        strip.setImage(BitmapDrawable(activity.resources, Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)), config)
        activity.setContentView(strip, ViewGroup.LayoutParams(200, 400))
        layout(strip)
        draw(strip)
        assertEquals(1, initialLoads)
        var swaps = 0
        strip.setTiledImagePreservingCurrent(pendingFile(activity, "pending-strip.webp"), config, { true }, { true }, { _, _ -> swaps++ }, { error -> throw AssertionError(error) })
        val staged = strip.getChildAt(0) as SubsamplingScaleImageView
        staged.setImage(ImageSource.bitmap(Bitmap.createBitmap(200, 401, Bitmap.Config.ARGB_8888)))
        layout(strip)
        draw(strip)
        shadowOf(Looper.getMainLooper()).idle()
        strip.measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(400, strip.measuredHeight)
        assertEquals(1, swaps)
        assertEquals(1, initialLoads)
        strip.recycle()
        activity.finish()
    }

    @Test fun cancelledStagingCannotSwapFromAnOldRetry() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val strip = Strip(activity)
        strip.setImage(BitmapDrawable(activity.resources, Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)), config)
        activity.setContentView(strip, ViewGroup.LayoutParams(200, 400))
        layout(strip)
        draw(strip)
        val old = strip.getChildAt(0)
        var allowed = false
        var swaps = 0
        val id = strip.setTiledImagePreservingCurrent(pendingFile(activity, "pending-strip.webp"), config, { true }, { allowed }, { _, _ -> swaps++ }, {})
        val staged = strip.getChildAt(0) as SubsamplingScaleImageView
        staged.setImage(ImageSource.bitmap(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)))
        layout(strip)
        draw(strip)
        strip.cancelStagedImage(id)
        allowed = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(0, swaps)
        assertSame(old, strip.getChildAt(0))
        strip.recycle()
        activity.finish()
    }

    @Test fun imageDecodedBeforeMeasurementCommitsWhenItsViewBecomesReady() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val strip = Strip(activity)
        strip.setImage(BitmapDrawable(activity.resources, Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)), config)
        activity.setContentView(strip, ViewGroup.LayoutParams(200, 400))
        layout(strip)
        draw(strip)
        var swaps = 0
        strip.setTiledImagePreservingCurrent(pendingFile(activity, "pending-strip.webp"), config, { true }, { true }, { _, _ -> swaps++ }, {})
        val staged = strip.getChildAt(0) as SubsamplingScaleImageView
        staged.setImage(ImageSource.bitmap(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)))
        assertTrue(staged.isImageLoaded)
        assertFalse(staged.isReady)
        assertEquals(0, swaps)
        layout(strip)
        draw(strip)
        // Swapping during dispatchDraw itself used to corrupt FrameLayout's child traversal.
        assertEquals(0, swaps)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, swaps)
        strip.recycle()
        activity.finish()
    }

    @Test fun detachedCachedStripResumesPendingSwapWhenReattached() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val strip = Strip(activity)
        strip.setImage(BitmapDrawable(activity.resources, Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)), config)
        activity.setContentView(strip, ViewGroup.LayoutParams(200, 400))
        layout(strip)
        draw(strip)
        var allowed = false
        var swaps = 0
        strip.setTiledImagePreservingCurrent(pendingFile(activity, "pending-strip.webp"), config, { true }, { allowed }, { _, _ -> swaps++ }, {})
        val staged = strip.getChildAt(0) as SubsamplingScaleImageView
        staged.setImage(ImageSource.bitmap(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)))
        layout(strip)
        draw(strip)
        activity.setContentView(View(activity))
        allowed = true
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(0, swaps)
        activity.setContentView(strip, ViewGroup.LayoutParams(200, 400))
        layout(strip)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(1, swaps)
        strip.recycle()
        activity.finish()
    }

    @Test fun stagedTileLoadErrorCancelsTheWaitAndReportsFailure() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val strip = Strip(activity)
        strip.setImage(BitmapDrawable(activity.resources, Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)), config)
        activity.setContentView(strip, ViewGroup.LayoutParams(200, 400))
        layout(strip)
        draw(strip)
        val old = strip.getChildAt(0)
        var reported: Throwable? = null
        strip.setTiledImagePreservingCurrent(
            pendingFile(activity, "pending-strip.webp"),
            config,
            { true },
            { true },
            { _, _ -> throw AssertionError("a failed tile must not commit") },
            { reported = it },
        )
        val staged = strip.getChildAt(0) as SubsamplingScaleImageView
        val listener = SubsamplingScaleImageView::class.java
            .getDeclaredField("onImageEventListener")
            .apply { isAccessible = true }
            .get(staged) as SubsamplingScaleImageView.OnImageEventListener
        val failure = IllegalStateException("tile decode failed")

        // Drive the real staged listener's tile-error event deterministically. The decoder's
        // background executor is intentionally paused in these lifecycle tests.
        listener.onTileLoadError(failure)

        assertSame(failure, reported)
        assertSame(old, strip.getChildAt(0))
        assertEquals(1, strip.childCount)
        strip.recycle()
        activity.finish()
    }

    @Test fun delayedLandscapeZoomIsCancelledWhenThePageIsRecycled() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (imageView, image) = landscapeView(activity)

        imageView.recycle()
        assertFalse(image.isReady)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        activity.finish()
    }

    @Test fun delayedLandscapeZoomStillRunsForTheCurrentReadyPage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (imageView, image) = landscapeView(activity)
        val minimumScale = image.minScale
        assertEquals(minimumScale, image.scale, 0.001f)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        val animation = SubsamplingScaleImageView::class.java.getDeclaredField("anim")
            .apply { isAccessible = true }.get(image)
        assertTrue("delayed zoom should start for the ready current page", animation != null)
        // SSIV advances its animation from System.currentTimeMillis() during onDraw, while
        // Robolectric's paused looper only advances uptime when idleFor() runs the delayed task.
        // Advance the real animation's own timestamp deterministically, then drive its next frame.
        animation!!.javaClass.getDeclaredField("time").apply { isAccessible = true }
            .setLong(animation, System.currentTimeMillis() - 100L)
        draw(imageView)
        assertTrue(image.scale > minimumScale + 0.05f)

        imageView.recycle()
        activity.finish()
    }

    @Test fun delayedLandscapeZoomDoesNotCarryOverToAReboundPage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (imageView, _) = landscapeView(activity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        imageView.setImage(
            BitmapDrawable(activity.resources, Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)),
            landscapeConfig.copy(landscapeZoom = false),
        )
        layout(imageView)
        draw(imageView)
        val rebound = imageView.getChildAt(0) as SubsamplingScaleImageView
        val reboundScale = rebound.scale

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        draw(imageView)
        assertEquals(reboundScale, rebound.scale, 0.001f)

        imageView.recycle()
        activity.finish()
    }

    @Test fun delayedLandscapeZoomIsCancelledWhenStagedImageReplacesThePage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val imageView = PendingImageView(activity)
        imageView.setImage(
            BitmapDrawable(activity.resources, Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)),
            landscapeConfig,
        )
        activity.setContentView(imageView, ViewGroup.LayoutParams(200, 400))
        layout(imageView)
        draw(imageView)
        imageView.onPageSelected(forward = true)
        val original = imageView.getChildAt(0) as SubsamplingScaleImageView
        check(original.isReady)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        imageView.setTiledImagePreservingCurrent(
            pendingFile(activity, "pending-landscape.webp"),
            landscapeConfig,
            { true },
            { true },
            { _, _ -> },
            { error -> throw AssertionError(error) },
        )
        val staged = imageView.getChildAt(0) as SubsamplingScaleImageView
        staged.setImage(ImageSource.bitmap(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)))
        layout(imageView)
        draw(imageView)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(staged, imageView.getChildAt(0))

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        imageView.recycle()
        activity.finish()
    }

    @Test fun lateTileFailureReportsForActiveImageAndIgnoresReplacedImage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val strip = Strip(activity)
        strip.setImage(
            BitmapDrawable(activity.resources, Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)),
            config,
        )
        activity.setContentView(strip, ViewGroup.LayoutParams(200, 400))
        layout(strip)
        draw(strip)
        val displayErrors = mutableListOf<Throwable>()
        var stagingErrors = 0

        fun stage(onDisplayError: (Throwable) -> Unit): SubsamplingScaleImageView {
            strip.setTiledImagePreservingCurrent(
                pendingFile(activity, "pending-strip-${displayErrors.size}.webp"),
                config,
                { true },
                { true },
                { _, _ -> },
                { stagingErrors++ },
                onDisplayError,
            )
            val staged = strip.getChildAt(0) as SubsamplingScaleImageView
            staged.setImage(ImageSource.bitmap(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)))
            layout(strip)
            draw(strip)
            shadowOf(Looper.getMainLooper()).idle()
            return staged
        }

        val first = stage { displayErrors += it }
        val active = stage { displayErrors += it }
        val staleFailure = IllegalStateException("replaced tile failed")
        sendTileLoadError(first, staleFailure)
        assertTrue(displayErrors.isEmpty())

        val activeFailure = IllegalStateException("active tile failed")
        sendTileLoadError(active, activeFailure)
        assertEquals(listOf(activeFailure), displayErrors)
        assertEquals(0, stagingErrors)
        assertSame(active, strip.getChildAt(0))
        assertEquals(1, strip.childCount)

        strip.recycle()
        activity.finish()
    }
}
