package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ManyueNativeTargetRequestTest {
    @Test fun originalSizeEnhancementQueuesTheOriginalPixelsForTheTwoTimesModel() =
        checkRequest(mode = 2, scalePercent = 100, displayWidth = 0, expectedTarget = 8)

    @Test fun automaticUpscaleQueuesOriginalPixelsAndOnlyTheNecessaryTargetWidth() =
        checkRequest(mode = 4, scalePercent = 200, displayWidth = 12, expectedTarget = 12)

    @Test fun automaticWideSourceCreatesNoNativeRequestOrInputFile() =
        checkRequest(mode = 4, scalePercent = 200, displayWidth = 6, expectedTarget = null)

    private fun checkRequest(mode: Int, scalePercent: Int, displayWidth: Int, expectedTarget: Int?) {
        val context: Application = RuntimeEnvironment.getApplication()
        val runtime = ManyueAiRuntime
        val scheduler = ManyueAiUpscaler
        val probeField = runtime.javaClass.getDeclaredField("cachedProbe").apply { isAccessible = true }
        val startedField = scheduler.javaClass.getDeclaredField("started").apply { isAccessible = true }
        val oldProbe = probeField.get(null)
        val oldStarted = startedField.getBoolean(null)
        val oldAbis = Build.SUPPORTED_64_BIT_ABIS
        val oldNativeDir = context.applicationInfo.nativeLibraryDir
        val oldMode = ManyueRuntimeState.modeInt
        val oldScale = ManyueRuntimeState.aiScalePercent
        var token: String? = null
        try {
            // Exercise request construction without starting an Android native executable.
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", arrayOf("arm64-v8a"))
            context.applicationInfo.nativeLibraryDir = context.cacheDir.resolve("test-native").path
            probeField.set(null, context.applicationInfo.nativeLibraryDir to ManyueAiRuntime.Capability.READY)
            startedField.setBoolean(null, true)
            ManyueRuntimeState.updateMode(mode)
            ManyueRuntimeState.updateAiScale(scalePercent)
            val bitmap = Bitmap.createBitmap(8, 17, Bitmap.Config.ARGB_8888)
            val originalBytes = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            }
            bitmap.recycle()
            val originalCopy = originalBytes.clone()

            val inputsBefore = context.cacheDir.listFiles()?.filter { it.name.startsWith("manyue_in_") }?.toSet()
            token = ManyueAiRequestFactory.enqueue(
                context,
                ManyuePageBridge.Identity(7901, 7902, 0),
                originalBytes,
                priority = 100,
                displayWidthPx = displayWidth,
            )
            if (expectedTarget == null) {
                assertNull(token)
                assertEquals(inputsBefore, context.cacheDir.listFiles()?.filter { it.name.startsWith("manyue_in_") }?.toSet())
                return
            }
            assertNotNull("1x target must enqueue real x2 inference instead of bypassing AI", token)
            val requestsField = scheduler.javaClass.getDeclaredField("requests").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val requests = requestsField.get(null) as Map<String, ManyueAiUpscaler.Request>
            val request = requireNotNull(requests[token])
            assertEquals(8, request.sourceWidth)
            assertEquals(17, request.sourceHeight)
            assertEquals(expectedTarget.toInt(), request.targetWidth)
            assertEquals(2, request.model.scale)
            assertArrayEquals(originalCopy, originalBytes)
            assertArrayEquals(originalCopy, request.inputFile.readBytes())
        } finally {
            token?.let(scheduler::cancel)
            ManyueRuntimeState.updateAiScale(oldScale)
            ManyueRuntimeState.updateMode(oldMode)
            startedField.setBoolean(null, oldStarted)
            probeField.set(null, oldProbe)
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_64_BIT_ABIS", oldAbis)
            context.applicationInfo.nativeLibraryDir = oldNativeDir
        }
    }
}
