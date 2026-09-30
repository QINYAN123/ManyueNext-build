package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import java.io.ByteArrayOutputStream
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercise BitmapRegionDecoder against encoded PNG and lossless WebP bytes, not an in-memory bitmap. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManyueRegionDecoderTest {
    @Before fun preloadNativeGraphics() {
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).recycle()
    }

    @Test fun decodesPngRegionAndSampledTile() = assertEncodedRegion(PNG)

    @Test fun decodesWebpRegionAndSampledTile() = assertEncodedRegion(WEBP)

    @Test fun rejectsInvalidRectAndSampleAndDecodeAfterRecycle() {
        val decoder = newReadyDecoder(PNG)
        try {
            assertThrows(IllegalArgumentException::class.java) {
                decoder.decodeRegion(Rect(-1, 0, 2, 2), sampleSize = 1)
            }
            assertThrows(IllegalArgumentException::class.java) {
                decoder.decodeRegion(Rect(0, 0, 2, 2), sampleSize = 0)
            }
        } finally {
            decoder.recycle()
        }
        assertFalse(decoder.isReady())
        assertThrows(IllegalStateException::class.java) {
            decoder.decodeRegion(Rect(0, 0, 2, 2), sampleSize = 1)
        }
    }

    @Test fun closesInputAndRemainsUnreadyWhenInitFails() {
        val stream = object : InputStream() {
            var closed = false
            override fun read(): Int = throw IOException("test stream failure")
            override fun close() {
                closed = true
            }
        }
        val decoder = ManyueRegionDecoder()
        try {
            assertThrows(Exception::class.java) {
                decoder.init(RuntimeEnvironment.getApplication(), InputProvider { stream })
            }
            assertTrue(stream.closed)
            assertFalse(decoder.isReady())
        } finally {
            decoder.recycle()
        }
    }

    @Test fun decodesTheBottomTileOfTallPng() {
        val width = 32
        val height = 8192
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            Color.rgb((x * 17 + y) and 255, (y * 3 + x * 5) and 255, (x xor y) and 255)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val encoded = ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()

        val decoder = ManyueRegionDecoder()
        try {
            assertEquals(Point(width, height), decoder.init(
                RuntimeEnvironment.getApplication(),
                InputProvider { ByteArrayInputStream(encoded) },
            ))
            val bottom = decoder.decodeRegion(Rect(0, height - 8, width, height), sampleSize = 1)
            try {
                assertEquals(width, bottom.width)
                assertEquals(8, bottom.height)
                assertEquals(pixels.last(), bottom.getPixel(width - 1, 7))
            } finally {
                bottom.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }

    private fun assertEncodedRegion(encoded: ByteArray) {
        val decoder = ManyueRegionDecoder()
        val dimensions = decoder.init(
            RuntimeEnvironment.getApplication(),
            InputProvider { ByteArrayInputStream(encoded) },
        )
        assertEquals(Point(IMAGE_WIDTH, IMAGE_HEIGHT), dimensions)
        assertTrue(decoder.isReady())

        val region = decoder.decodeRegion(Rect(2, 1, 6, 5), sampleSize = 1)
        try {
            assertEquals(4, region.width)
            assertEquals(4, region.height)
            assertEquals(expectedPixel(2, 1), region.getPixel(0, 0))
            assertEquals(expectedPixel(3, 2), region.getPixel(1, 1))
            assertEquals(expectedPixel(5, 4), region.getPixel(3, 3))
        } finally {
            region.recycle()
        }

        val oddOrigin = decoder.decodeRegion(Rect(1, 1, 5, 5), sampleSize = 1)
        try {
            assertEquals(4, oddOrigin.width)
            assertEquals(4, oddOrigin.height)
            assertEquals(expectedPixel(1, 1), oddOrigin.getPixel(0, 0))
            assertEquals(expectedPixel(4, 4), oddOrigin.getPixel(3, 3))
        } finally {
            oddOrigin.recycle()
        }

        val oddOriginSampled = decoder.decodeRegion(Rect(1, 1, 5, 5), sampleSize = 2)
        try {
            assertEquals(2, oddOriginSampled.width)
            assertEquals(2, oddOriginSampled.height)
            assertTrue(Color.alpha(oddOriginSampled.getPixel(0, 0)) > 0)
            assertTrue(Color.alpha(oddOriginSampled.getPixel(1, 1)) > 0)
        } finally {
            oddOriginSampled.recycle()
        }

        val sampled = decoder.decodeRegion(Rect(2, 0, 8, 6), sampleSize = 2)
        try {
            assertEquals(3, sampled.width)
            assertEquals(3, sampled.height)
            assertTrue(Color.alpha(sampled.getPixel(0, 0)) > 0)
            assertTrue(Color.alpha(sampled.getPixel(2, 2)) > 0)
        } finally {
            sampled.recycle()
            decoder.recycle()
            decoder.recycle()
        }
        assertFalse(decoder.isReady())
    }

    private fun newReadyDecoder(encoded: ByteArray): ManyueRegionDecoder = ManyueRegionDecoder().apply {
        init(
            RuntimeEnvironment.getApplication(),
            InputProvider { ByteArrayInputStream(encoded) },
        )
    }

    private fun expectedPixel(x: Int, y: Int): Int = Color.rgb(
        10 + x * 29,
        20 + y * 31,
        5 + (x + y) * 17,
    )

    private companion object {
        const val IMAGE_WIDTH = 8
        const val IMAGE_HEIGHT = 6

        // Generated as an 8x6 RGBA gradient; WebP is encoded losslessly.
        val PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAGCAYAAAD+Bd/7AAAAHklEQVR4nGPkEmH9L8sgyIALszDICzIwMODGg0EBANy8Bb1e6DzwAAAAAElFTkSuQmCC",
        )
        val WEBP = Base64.getDecoder().decode(
            "UklGRjQAAABXRUJQVlA4TCcAAAAvB0ABALmM6H/sIqL/ASJtm4kYn3+fR1cgFkzx7hx6QURMgOE7bwAA",
        )
    }
}
