package eu.kanade.tachiyomi.ui.reader.metadata

import android.app.Application
import android.graphics.Bitmap
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceImageBoundsBytesTest {
    @Test fun `encoded bytes and existing stream path agree across supported formats without mutation`() {
        val bitmap = Bitmap.createBitmap(17, 31, Bitmap.Config.ARGB_8888)
        try {
            for (format in listOf(Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.WEBP_LOSSLESS)) {
                val bytes = ByteArrayOutputStream().use {
                    check(bitmap.compress(format, 100, it))
                    it.toByteArray()
                }
                val original = bytes.copyOf()
                val expected = SourceImageBoundsDecoder.decode(Buffer().write(bytes))
                assertEquals(SourceImageInfo.Available(17, 31), expected)
                assertEquals(expected, SourceImageBoundsDecoder.decode(bytes))
                assertArrayEquals(original, bytes)
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun `invalid encoded bytes remain unavailable`() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1, 2, 3), "GIF89a".toByteArray())) {
            assertEquals(SourceImageInfo.Unavailable, SourceImageBoundsDecoder.decode(bytes))
        }
    }
}
