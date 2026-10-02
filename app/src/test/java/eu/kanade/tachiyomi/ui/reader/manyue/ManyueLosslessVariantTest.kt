package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [26, 29, 34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManyueLosslessVariantTest {
    @Test fun losslessVariantRetainsOpaquePixelsAndAlphaAtFastCompressionEffort() {
        val width = 33
        val height = 47
        val input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val source = IntArray(width * height) { index ->
            if (index % 9 == 0) {
                0x40000000 or 0x00ff00ff
            } else {
                0xff000000.toInt() or ((index * 31 and 255) shl 16) or
                    ((index * 73 and 255) shl 8) or (index * 13 and 255)
            }
        }
        input.setPixels(source, 0, width, 0, 0, width, height)
        val bytes = ByteArrayOutputStream().use {
            assertEquals(true, input.compress(ManyueBitmapEncoding.fastLosslessFormat(), 0, it))
            it.toByteArray()
        }
        val output = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val before = IntArray(source.size)
            val after = IntArray(source.size)
            input.getPixels(before, 0, width, 0, 0, width, height)
            output.getPixels(after, 0, width, 0, 0, width, height)
            assertArrayEquals(before, after)
            assertEquals(width, output.width)
            assertEquals(height, output.height)
        } finally {
            input.recycle()
            output.recycle()
        }
    }

    @Test(expected = kotlinx.coroutines.CancellationException::class)
    fun cancelledClassicWorkRetainsTheCallerBitmap() {
        val input = Bitmap.createBitmap(32, 65, Bitmap.Config.ARGB_8888)
        input.eraseColor(0xff668899.toInt())
        var polls = 0
        try {
            ManyueClassicEnhancer.enhance(input, 100, true) { ++polls >= 2 }
        } finally {
            assertFalse(input.isRecycled)
            assertEquals(0xff668899.toInt(), input.getPixel(16, 16))
            assertEquals(2, polls)
            input.recycle()
        }
    }
}
