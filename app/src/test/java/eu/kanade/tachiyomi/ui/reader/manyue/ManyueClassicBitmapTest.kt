package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.graphics.Bitmap
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManyueClassicBitmapTest {
    @Test fun rowWindowMatchesOriginalFullImageConvolutionIncludingBorders() {
        // Independent reference is the previous full-image algorithm. Compare all pixels,
        // including thin images and a tall strip; this is not a visual-quality approximation.
        val random = Random(95)
        for ((w, h) in listOf(1 to 7, 9 to 2, 3 to 3, 7 to 9, 32 to 512)) {
            for (d in listOf(0.02f, 0.18f, 0.42f)) {
                val pixels = IntArray(w * h) { random.nextInt() or (255 shl 24) }
                val expected = pixels.copyOf()
                val c = 1f + 4f * d
                for (y in 1 until h - 1) for (x in 1 until w - 1) {
                    val i = y * w + x
                    var color = pixels[i] and (255 shl 24)
                    for (shift in listOf(16, 8, 0)) {
                        fun channel(index: Int) = ((pixels[index] shr shift) and 255).toFloat()
                        val value = channel(i) * c -
                            (channel(i - w) + channel(i + w) + channel(i - 1) + channel(i + 1)) * d
                        color = color or (value.coerceIn(0f, 255f).toInt() shl shift)
                    }
                    expected[i] = color
                }
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                    setPixels(pixels, 0, w, 0, 0, w, h)
                }
                try {
                    ManyueClassicEnhancer.javaClass.getDeclaredMethod(
                        "sharpenInPlace", Bitmap::class.java, Float::class.javaPrimitiveType,
                    ).apply { isAccessible = true }.invoke(ManyueClassicEnhancer, bitmap, d)
                    val actual = IntArray(w * h)
                    bitmap.getPixels(actual, 0, w, 0, 0, w, h)
                    assertArrayEquals("${w}x$h d=$d", expected, actual)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test fun enhancementFailureCannotReturnInputAsSuccessfulOutput() {
        val input = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)
        input.recycle()
        var failed = false
        try {
            ManyueClassicEnhancer.enhance(input, 50, false)
        } catch (_: RuntimeException) {
            failed = true
        }
        assertTrue("Invalid input must propagate failure to the caller", failed)
    }

    @Test fun outputAndInputHaveSeparateOwnershipAndZeroStrengthIsExplicitNoOp() {
        val input = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val output = ManyueClassicEnhancer.enhance(input, 50, false)
        try {
            assertNotSame(input, output)
            output.recycle()
            assertFalse(input.isRecycled)
            assertSame(input, ManyueClassicEnhancer.enhance(input, 0, false))
        } finally {
            if (!output.isRecycled) output.recycle()
            input.recycle()
        }
    }
}
