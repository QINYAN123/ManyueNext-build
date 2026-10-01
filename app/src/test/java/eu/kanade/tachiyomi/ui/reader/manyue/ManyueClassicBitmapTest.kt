package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.graphics.Bitmap
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    @Test
    fun `flat color regions and smooth gradients are preserved`() {
        val width = 19
        val height = 17
        val blockColors = intArrayOf(
            0xff5878a0.toInt(),
            0xffa05f74.toInt(),
            0xff4f9771.toInt(),
            0xffbead83.toInt(),
        )
        val blockPixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val quadrant = (if (y < height / 2) 0 else 2) + if (x < width / 2) 0 else 1
            blockColors[quadrant]
        }

        val blocks = bitmapFrom(width, height, blockPixels)
        val enhancedBlocks = ManyueClassicEnhancer.enhance(blocks, 100, false)
        try {
            // Interior samples stay flat. The algorithm does not invent texture in color fills.
            assertEquals(blockColors[0], enhancedBlocks.getPixel(4, 4))
            assertEquals(blockColors[1], enhancedBlocks.getPixel(14, 4))
            assertEquals(blockColors[2], enhancedBlocks.getPixel(4, 12))
            assertEquals(blockColors[3], enhancedBlocks.getPixel(14, 12))
        } finally {
            enhancedBlocks.recycle()
            blocks.recycle()
        }

        val gradientPixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            argb(
                alpha = 255,
                red = 38 + x * 2 + y,
                green = 58 + x + y * 2,
                blue = 78 + x + y,
            )
        }
        val gradient = bitmapFrom(width, height, gradientPixels)
        val enhancedGradient = ManyueClassicEnhancer.enhance(gradient, 100, false)
        try {
            assertArrayEquals(pixelsOf(gradient), pixelsOf(enhancedGradient))
        } finally {
            enhancedGradient.recycle()
            gradient.recycle()
        }
    }

    @Test
    fun `luminance detail increases with a bounded shift and avoids clipping`() {
        val width = 21
        val height = 17
        val pixels = IntArray(width * height) { index ->
            val gray = if (index % width < 10) 80 else 170
            argb(255, gray, gray, gray)
        }
        val input = bitmapFrom(width, height, pixels)
        val output = ManyueClassicEnhancer.enhance(input, 100, false)
        try {
            val beforeDark = input.getPixel(9, 8) and 0xff
            val beforeLight = input.getPixel(10, 8) and 0xff
            val afterDark = output.getPixel(9, 8) and 0xff
            val afterLight = output.getPixel(10, 8) and 0xff

            assertTrue("Existing edge contrast should increase", afterLight - afterDark > beforeLight - beforeDark)
            assertTrue("The dark side should not be clipped or brightened", afterDark < beforeDark)
            assertTrue("The light side should not be clipped or darkened", afterLight > beforeLight)

            val original = pixelsOf(input)
            val enhanced = pixelsOf(output)
            for (index in original.indices) {
                val before = original[index] and 0xff
                val after = enhanced[index] and 0xff
                assertTrue("Per-pixel detail shift must stay bounded", abs(after - before) <= 19)
                assertTrue("Output must remain in the 8-bit gamut", after in 0..255)
            }
        } finally {
            output.recycle()
            input.recycle()
        }
    }

    @Test
    fun `shared luminance shift preserves color differences and alpha`() {
        val width = 17
        val height = 17
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val inDetail = x in 7..9 && y in 7..9
            val level = if (inDetail) 20 else 0
            val alpha = if (x == 8 && y == 8) {
                255
            } else {
                when ((x + y) % 5) {
                    0 -> 0
                    1 -> 1
                    2 -> 128
                    3 -> 254
                    else -> 255
                }
            }
            argb(alpha, 100 + level, 130 + level, 160 + level)
        }
        val input = bitmapFrom(width, height, pixels)
        val output = ManyueClassicEnhancer.enhance(input, 100, false)
        try {
            val before = pixelsOf(input)
            val after = pixelsOf(output)
            for (index in before.indices) {
                assertEquals("Alpha must be preserved at pixel $index", before[index] ushr 24, after[index] ushr 24)
            }

            val centerBefore = input.getPixel(8, 8)
            val centerAfter = output.getPixel(8, 8)
            val redShift = ((centerAfter ushr 16) and 0xff) - ((centerBefore ushr 16) and 0xff)
            val greenShift = ((centerAfter ushr 8) and 0xff) - ((centerBefore ushr 8) and 0xff)
            val blueShift = (centerAfter and 0xff) - (centerBefore and 0xff)
            assertTrue("A clear local detail should receive a small gain", redShift > 0)
            assertTrue(abs(redShift - greenShift) <= 1)
            assertTrue(abs(greenShift - blueShift) <= 1)
            assertEquals(255, centerAfter ushr 24)
        } finally {
            output.recycle()
            input.recycle()
        }
    }

    @Test
    fun `pure black and white endpoints remain unchanged`() {
        val width = 17
        val height = 13
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val gray = when {
                x < 5 -> 0
                x >= 12 -> 255
                else -> 128
            }
            argb(255, gray, gray, gray)
        }
        val input = bitmapFrom(width, height, pixels)
        val output = ManyueClassicEnhancer.enhance(input, 100, false)
        try {
            for (y in 2 until height - 2) {
                assertEquals(0xff000000.toInt(), output.getPixel(3, y))
                assertEquals(0xffffffff.toInt(), output.getPixel(13, y))
            }
        } finally {
            output.recycle()
            input.recycle()
        }
    }

    @Test
    fun `output keeps dimensions and input ownership while zero strength is a no op`() {
        val inputPixels = IntArray(11 * 9) { index ->
            val x = index % 11
            val y = index / 11
            argb(
                alpha = (index * 29) and 0xff,
                red = 35 + x * 7,
                green = 45 + y * 8,
                blue = 80 + x * 2 + y * 3,
            )
        }
        val input = bitmapFrom(11, 9, inputPixels)
        val before = pixelsOf(input)
        val output = ManyueClassicEnhancer.enhance(input, 50, false)
        try {
            assertNotSame(input, output)
            assertEquals(input.width, output.width)
            assertEquals(input.height, output.height)
            assertArrayEquals("Enhancement must not mutate the caller's bitmap", before, pixelsOf(input))
            assertArrayEquals("Zero strength must return the unchanged input", before, pixelsOf(ManyueClassicEnhancer.enhance(input, 0, false)))
            assertSame(input, ManyueClassicEnhancer.enhance(input, 0, false))
        } finally {
            output.recycle()
            assertFalse(input.isRecycled)
            assertArrayEquals(before, pixelsOf(input))
            input.recycle()
        }
    }

    @Test
    fun `invalid bitmap failure propagates for caller fallback`() {
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

    private fun bitmapFrom(width: Int, height: Int, pixels: IntArray): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }

    private fun pixelsOf(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        ((alpha.coerceIn(0, 255)) shl 24) or
            ((red.coerceIn(0, 255)) shl 16) or
            ((green.coerceIn(0, 255)) shl 8) or
            blue.coerceIn(0, 255)
}
