package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.metadata.SourceImageInfo
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManyueAiRequestRejectionTest {

    @Test
    fun `quality reports exact two times output budget and normal page remains enqueueable`() = withRuntime { context ->
        val identity = ManyuePageBridge.Identity(9201, 9202, 0)
        val largePage = png(1200, 3656)
        var rejection: ManyueAiRequestFactory.Rejection? = null
        val before = inputFiles(context)

        val rejectedToken = ManyueAiRequestFactory.enqueue(
            context = context,
            identity = identity,
            originalBytes = largePage,
            priority = 100,
            onRejected = { rejection = it },
        )

        assertNull(rejectedToken)
        assertEquals(ManyueAiRequestFactory.RejectionReason.DISPLAY_PIXEL_BUDGET, rejection?.reason)
        assertEquals(1200, rejection?.sourceWidth)
        assertEquals(3656, rejection?.sourceHeight)
        assertEquals(2400, rejection?.targetWidth)
        assertEquals(7312, rejection?.targetHeight)
        assertEquals(12_000_000L, rejection?.limit)
        val message = ManyuePageBridge.rejectionMessage(requireNotNull(rejection))
        assertTrue(message.contains("2400×7312"))
        assertTrue(message.contains("17548800 像素"))
        assertTrue(message.contains("12000000 像素上限"))
        assertFalse(message.contains("格式"))
        assertEquals(before, inputFiles(context))

        val normalToken = ManyueAiRequestFactory.enqueue(
            context = context,
            identity = identity.copy(pageIndex = 1),
            originalBytes = png(1200, 2160),
            priority = 100,
        )
        assertNotNull("A normal 1200×2160 page fits the existing 12MP budget at 2×", normalToken)
        val normalRequest = request(requireNotNull(normalToken))
        assertEquals(2400, normalRequest.targetWidth)
        assertEquals(4320, ManyueAiUpscaler.targetHeight(1200, 2160, normalRequest.targetWidth))
        assertTrue(inputFiles(context).size > before.size)
        ManyueAiUpscaler.cancel(requireNotNull(normalToken))
    }

    @Test
    fun `animated and undecodable images report different rejection reasons`() = withRuntime { context ->
        val identity = ManyuePageBridge.Identity(9301, 9302, 4)
        fun reject(bytes: ByteArray): ManyueAiRequestFactory.Rejection? {
            var result: ManyueAiRequestFactory.Rejection? = null
            assertNull(
                ManyueAiRequestFactory.enqueue(
                    context = context,
                    identity = identity,
                    originalBytes = bytes,
                    priority = 100,
                    onRejected = { result = it },
                ),
            )
            return result
        }

        val empty = reject(byteArrayOf())
        assertEquals(ManyueAiRequestFactory.RejectionReason.EMPTY_INPUT, empty?.reason)

        val animated = reject("GIF89a".toByteArray())
        assertEquals(ManyueAiRequestFactory.RejectionReason.ANIMATED_IMAGE, animated?.reason)
        assertTrue(ManyuePageBridge.rejectionMessage(requireNotNull(animated)).contains("动画图片"))

        val undecodable = reject(byteArrayOf(1, 2, 3, 4))
        assertEquals(ManyueAiRequestFactory.RejectionReason.INVALID_DECODE_BOUNDS, undecodable?.reason)
        assertTrue(ManyuePageBridge.rejectionMessage(requireNotNull(undecodable)).contains("无法读取图片尺寸"))
    }

    @Test
    fun `expired generation is reported before parsing input`() = withRuntime { context ->
        val identity = ManyuePageBridge.Identity(9401, 9402, 5)
        var rejection: ManyueAiRequestFactory.Rejection? = null
        val token = ManyueAiRequestFactory.enqueue(
            context = context,
            identity = identity,
            originalBytes = byteArrayOf(),
            priority = 100,
            generation = ManyueRuntimeState.generation - 1,
            onRejected = { rejection = it },
        )

        assertNull(token)
        assertEquals(ManyueAiRequestFactory.RejectionReason.SESSION_EXPIRED, rejection?.reason)
        assertTrue(ManyuePageBridge.rejectionMessage(requireNotNull(rejection)).contains("设置已更改"))
    }

    @Test
    fun `direct legacy Anime flag is suppressed and shares the plain AI request identity`() = withRuntime { context ->
        fun input(name: String) = File(context.cacheDir, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val first = ManyueAiUpscaler.register(
            mangaId = 9501,
            chapterId = 9502,
            pageIndex = 0,
            inputFile = input("manyue-overlay-off-${System.nanoTime()}.bin"),
            sourceWidth = 12,
            sourceHeight = 16,
            targetMode = 2,
            targetWidth = 24,
            priority = 100,
            expectedMode = ManyueEnhancementMode.AI_2X.value,
            generation = ManyueRuntimeState.generation,
            sourceFingerprint = "overlay-retired-input",
            model = ManyueAiModel.QUALITY_REAL_ESRGAN,
            anime4kOverlay = false,
        )
        val legacyOverlayInput = input("manyue-overlay-old-${System.nanoTime()}.bin")
        val second = ManyueAiUpscaler.register(
            mangaId = 9501,
            chapterId = 9502,
            pageIndex = 0,
            inputFile = legacyOverlayInput,
            sourceWidth = 12,
            sourceHeight = 16,
            targetMode = 2,
            targetWidth = 24,
            priority = 100,
            expectedMode = ManyueEnhancementMode.AI_2X.value,
            generation = ManyueRuntimeState.generation,
            sourceFingerprint = "overlay-retired-input",
            model = ManyueAiModel.QUALITY_REAL_ESRGAN,
            anime4kOverlay = true,
        )

        assertEquals(first, second)
        assertFalse(request(first).anime4kOverlay)
        assertFalse("the duplicate old-overlay input should be discarded", legacyOverlayInput.exists())
        ManyueAiUpscaler.cancel(first)
    }

    @Test
    fun `classic cache metadata remains readable but reader APIs never select its variant`() = withRuntime { context ->
        val input = File(context.cacheDir, "manyue-classic-cache-input-${System.nanoTime()}.bin")
            .apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val token = ManyueAiUpscaler.register(
            mangaId = 9601,
            chapterId = 9602,
            pageIndex = 0,
            inputFile = input,
            sourceWidth = 12,
            sourceHeight = 16,
            targetMode = 2,
            targetWidth = 24,
            priority = 100,
            expectedMode = ManyueEnhancementMode.AI_2X.value,
            generation = ManyueRuntimeState.generation,
            sourceFingerprint = "classic-cache-retirement",
            model = ManyueAiModel.QUALITY_REAL_ESRGAN,
        )
        val key = requireNotNull(ManyueAiUpscaler.cacheKeyForToken(token))
        val source = File(context.cacheDir, "manyue-classic-base-${System.nanoTime()}.png")
            .apply { writeBytes(png(24, 32)) }
        val bitmap = Bitmap.createBitmap(24, 32, Bitmap.Config.ARGB_8888)
        var pinned: ManyueEnhancementCache.PinnedCachedImage? = null
        try {
            assertTrue(ManyueEnhancementCache.putImage(context, key, source, 24, 32))
            assertNotNull(ManyueEnhancementCache.putClassicImage(context, key, 25, bitmap))
            assertEquals("classic_luma_v3_25.img", ManyueEnhancementCache.getClassicImage(context, key, 25)?.file?.name)

            assertNull(ManyueAiUpscaler.cachedClassicImage(context, token, 25))
            pinned = ManyueAiUpscaler.pinnedCachedImageForKey(context, key, 25)
            assertEquals("image.png", pinned?.image?.file?.name)
        } finally {
            pinned?.lease?.close()
            bitmap.recycle()
            ManyueAiUpscaler.cancel(token)
            ManyueEnhancementCache.getBundle(context, key)?.deleteRecursively()
            source.delete()
        }
    }

    @Test
    fun `webgpu auto waits for display width then follows original-size and ai factory paths`() = withRuntime { context ->
        ManyueRuntimeState.updateMode(ManyueEnhancementMode.AUTO.value)
        ManyueRuntimeState::class.java.getDeclaredField("displayWidthPx").apply {
            isAccessible = true
            setInt(ManyueRuntimeState, 0)
        }

        val dbChapter = mockk<Chapter>(relaxed = true)
        every { dbChapter.id } returns 9702L
        every { dbChapter.manga_id } returns 9701L
        val chapter = ReaderChapter(dbChapter)
        val sourceEnteredAtZeroWidth = CountDownLatch(1)
        val wideReads = AtomicInteger()
        val narrowReads = AtomicInteger()
        val widePage = readerPage(chapter, index = 0, width = 128, height = 64, readCount = wideReads) {
            sourceEnteredAtZeroWidth.countDown()
        }
        val narrowPage = readerPage(chapter, index = 1, width = 64, height = 64, readCount = narrowReads) {
            sourceEnteredAtZeroWidth.countDown()
        }
        val wideBridge = ManyueWebGpuPageBridge(context, widePage, alive = { true }) { _, _, _ -> false }
        val narrowBridge = ManyueWebGpuPageBridge(context, narrowPage, alive = { true }) { _, _, _ -> false }

        try {
            wideBridge.updateVisibility(isVisible = true)
            narrowBridge.updateVisibility(isVisible = true)
            assertFalse(
                "AUTO must wait before opening source streams when display width is zero",
                sourceEnteredAtZeroWidth.await(300, TimeUnit.MILLISECONDS),
            )
            assertEquals(0, wideReads.get())
            assertEquals(0, narrowReads.get())
            assertEquals("等待阅读区宽度", (widePage.sourceImageInfo.value as SourceImageInfo.Available).enhancementDetail)
            assertEquals("等待阅读区宽度", (narrowPage.sourceImageInfo.value as SourceImageInfo.Available).enhancementDetail)

            val generationBeforeWidth = ManyueRuntimeState.generation
            assertTrue(ManyueRuntimeState.updateDisplayWidth(128))
            assertEquals(generationBeforeWidth + 1, ManyueRuntimeState.generation)
            wideBridge.updateVisibility(isVisible = true)
            narrowBridge.updateVisibility(isVisible = true)

            assertTrue(
                "visible page bridges should reach their existing AUTO factory decisions",
                awaitMainCondition(5_000) {
                    val wideInfo = widePage.sourceImageInfo.value as SourceImageInfo.Available
                    wideInfo.enhancementState == ManyueEnhancementState.SKIPPED &&
                        schedulerRequests().any { it.mangaId == 9701L && it.pageIndex == 1 }
                },
            )
            assertEquals(1, wideReads.get())
            assertEquals(1, narrowReads.get())

            val wideInfo = widePage.sourceImageInfo.value as SourceImageInfo.Available
            assertEquals(ManyueEnhancementState.SKIPPED, wideInfo.enhancementState)
            assertTrue(wideInfo.enhancementDetail.orEmpty().contains("原尺寸"))
            val pageRequests = schedulerRequests().filter { it.mangaId == 9701L }
            assertEquals(listOf(1), pageRequests.map { it.pageIndex })
            assertEquals(64, pageRequests.single().sourceWidth)
            assertEquals(128, pageRequests.single().targetWidth)
            assertEquals(ManyueEnhancementMode.AUTO.value, pageRequests.single().expectedMode)
        } finally {
            wideBridge.destroy()
            narrowBridge.destroy()
        }
    }

    private fun readerPage(
        chapter: ReaderChapter,
        index: Int,
        width: Int,
        height: Int,
        readCount: AtomicInteger,
        onRead: () -> Unit,
    ): ReaderPage {
        val bytes = png(width, height)
        return ReaderPage(index, stream = {
            readCount.incrementAndGet()
            onRead()
            ByteArrayInputStream(bytes)
        }).apply {
            this.chapter = chapter
            status = Page.State.Ready
            updateSourceImageInfo(SourceImageInfo.Available(width, height))
        }
    }

    private fun awaitMainCondition(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(5)
        } while (System.nanoTime() < deadline)
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

    private inline fun withRuntime(block: (Application) -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val runtime = ManyueAiRuntime
        val scheduler = ManyueAiUpscaler
        val runtimeFields = listOf(
            "modeInt", "aiModel", "aiScalePercent", "displayWidthPx", "generation",
        ).associateWith { name ->
            ManyueRuntimeState::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        val oldState = runtimeFields.mapValues { (_, field) -> field.get(ManyueRuntimeState) }
        val probeField = runtime.javaClass.getDeclaredField("cachedProbe").apply { isAccessible = true }
        val oldProbe = probeField.get(null)
        val startedField = scheduler.javaClass.getDeclaredField("started").apply { isAccessible = true }
        val oldStarted = startedField.getBoolean(null)
        val diagnosticsField = ManyueDiagnostics::class.java.getDeclaredField("mutableLatest").apply { isAccessible = true }
        val diagnostics = diagnosticsField.get(ManyueDiagnostics) as MutableStateFlow<String>
        val oldDiagnostic = diagnostics.value
        val oldAbis = Build.SUPPORTED_64_BIT_ABIS
        val oldNativeDir = context.applicationInfo.nativeLibraryDir
        val fakeNativeDir = context.cacheDir.resolve("rejection-test-native").path
        try {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", arrayOf("arm64-v8a"))
            context.applicationInfo.nativeLibraryDir = fakeNativeDir
            probeField.set(null, Triple(fakeNativeDir, ManyueAiModel.QUALITY_REAL_ESRGAN.id, ManyueAiRuntime.Capability.READY))
            startedField.setBoolean(null, true)
            ManyueRuntimeState.updateAiModel(ManyueAiModel.QUALITY_REAL_ESRGAN)
            ManyueRuntimeState.updateMode(ManyueEnhancementMode.AI_2X.value)
            ManyueRuntimeState.updateAiScale(200)

            block(context)
        } finally {
            val requestsField = scheduler.javaClass.getDeclaredField("requests").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val requests = requestsField.get(null) as Map<String, ManyueAiUpscaler.Request>
            // Cancellation deletes private test inputs and never starts native work.
            requests.values.filter { it.mangaId in setOf(9201L, 9301L, 9401L, 9501L, 9601L, 9701L) }
                .map { it.token }
                .forEach(scheduler::cancel)
            ManyuePrefetchManager.reset()
            oldState.forEach { (name, value) -> runtimeFields.getValue(name).set(ManyueRuntimeState, value) }
            startedField.setBoolean(null, oldStarted)
            probeField.set(null, oldProbe)
            diagnostics.value = oldDiagnostic
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", oldAbis)
            context.applicationInfo.nativeLibraryDir = oldNativeDir
        }
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun inputFiles(context: Application): Set<String> =
        context.cacheDir.listFiles()?.filter { it.name.startsWith("manyue_in_") }?.map { it.name }?.toSet().orEmpty()

    private fun request(token: String): ManyueAiUpscaler.Request {
        val field = ManyueAiUpscaler.javaClass.getDeclaredField("requests").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val requests = field.get(null) as Map<String, ManyueAiUpscaler.Request>
        return requireNotNull(requests[token])
    }

    private fun schedulerRequests(): List<ManyueAiUpscaler.Request> {
        val field = ManyueAiUpscaler.javaClass.getDeclaredField("requests").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(null) as Map<String, ManyueAiUpscaler.Request>).values.toList()
    }
}
