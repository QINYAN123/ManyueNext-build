package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.metadata.SourceImageInfo
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class ManyueAutomaticReaderTest {
    private fun image(): ByteArray {
        val bmp = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(0xff785032.toInt())
        val bytes = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bmp.recycle()
        return bytes
    }

    @Test fun wideAutomaticSourceKeepsDimensionsAndDoesNotLabelItAi() = runBlocking {
        val source = Buffer().write(image())
        val states = mutableListOf<ManyueEnhancementState>()
        val enhanced = ManyueReaderHook.applyClassic(source, 4, 25, { state, _ -> states += state }, displayWidthPx = 8)
        val outputBytes = enhanced.readByteArray()
        val bitmap = BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.size)
        assertEquals(12, bitmap.width)
        assertEquals(16, bitmap.height)
        assertEquals(listOf(ManyueEnhancementState.CLASSIC_PROCESSING, ManyueEnhancementState.CLASSIC_READY), states)
        assertFalse(states.contains(ManyueEnhancementState.AI_READY))
        bitmap.recycle()
    }

    @Test fun narrowAutomaticSourceLeavesOriginalForAsyncAiInsteadOfClassicReencoding() = runBlocking {
        val bytes = image()
        val source = Buffer().write(bytes)
        val states = mutableListOf<ManyueEnhancementState>()
        assertSame(source, ManyueReaderHook.applyClassic(source, 4, 25, { state, _ -> states += state }, displayWidthPx = 24))
        assertArrayEquals(bytes, source.readByteArray())
        assertTrue(states.isEmpty())
    }

    @Test fun zeroStrengthPipelineDoesNotDecodeOrReencode() = runBlocking {
        val bytes = image()
        val copy = bytes.clone()
        assertNull(ManyueImagePipeline.classicEnhance(bytes, 0, false))
        assertArrayEquals(copy, bytes)
    }

    @Test fun reloadingSameSourceClearsOldAppliedStateBeforeStreamFailure() {
        val page = ReaderPage(0)
        page.updateSourceImageInfo(SourceImageInfo.Available(690, 1421))
        page.updateEnhancedImageInfo(1380, 2842)
        page.updateEnhancementState(ManyueEnhancementState.AI_READY)
        page.resetEnhancementState(4)
        page.updateSourceImageInfo(SourceImageInfo.Available(690, 1421))
        val info = page.sourceImageInfo.value as SourceImageInfo.Available
        assertEquals(ManyueEnhancementState.AI_QUEUED, info.enhancementState)
        assertNull(info.enhancedWidth)
        assertNull(info.enhancedHeight)
    }
}
