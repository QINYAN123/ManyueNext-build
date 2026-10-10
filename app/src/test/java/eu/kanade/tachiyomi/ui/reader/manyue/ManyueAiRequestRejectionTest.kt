package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
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
            requests.values.filter { it.mangaId in setOf(9201L, 9301L, 9401L) }
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
}
