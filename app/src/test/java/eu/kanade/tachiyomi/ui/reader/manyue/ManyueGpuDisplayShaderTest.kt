package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.media.ImageReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.ByteBuffer
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
// Robolectric's API 33/34 native backend uses Android 12 SkSL (no child.eval).
// API 36 uses the current AGSL backend; keep the production shader unchanged.
@Config(application = Application::class, sdk = [36], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManyueGpuDisplayShaderTest {
    private fun render(source: Bitmap, strength: Float): Bitmap {
        val shader = RuntimeShader(ManyueGpuDisplayShader.SOURCE)
        shader.setFloatUniform("viewportSize", source.width.toFloat(), source.height.toFloat())
        shader.setFloatUniform("strength", strength)
        // Exercise the same RenderNode/RenderEffect path as the reader. Software Canvas does
        // not support RuntimeShader. This is host rendering, not a phone GPU benchmark.
        val node = RenderNode("gpu-display-test")
        node.setPosition(0, 0, source.width, source.height)
        val canvas = node.beginRecording()
        canvas.drawBitmap(source, 0f, 0f, Paint())
        node.endRecording()
        node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        ImageReader.newInstance(source.width, source.height, PixelFormat.RGBA_8888, 1).use { reader ->
            val renderer = HardwareRenderer()
            val surface = reader.surface
            try {
                renderer.setOpaque(false)
                renderer.setSurface(surface)
                renderer.setContentRoot(node)
                val result = renderer.createRenderRequest().syncAndDraw()
                assertEquals(0, result and HardwareRenderer.SYNC_FRAME_DROPPED)
                reader.acquireNextImage().use { image ->
                    assertNotNull(image)
                    val plane = image.planes[0]
                    assertEquals(4, plane.pixelStride)
                    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                    // RNG's Windows host pipeline uses BGRA, matching Bitmap's native buffer.
                    val compact = ByteBuffer.allocateDirect(output.byteCount)
                    for (y in 0 until source.height) {
                        val row = plane.buffer.duplicate()
                        row.position(y * plane.rowStride)
                        row.limit(y * plane.rowStride + source.width * 4)
                        compact.put(row)
                    }
                    compact.flip()
                    output.copyPixelsFromBuffer(compact)
                    return output
                }
            } finally {
                renderer.destroy()
                surface.release()
                node.discardDisplayList()
            }
        }
    }

    @Test fun shaderCompilesAndCanBeUsedAsARenderEffect() {
        val shader = RuntimeShader(ManyueGpuDisplayShader.SOURCE)
        shader.setFloatUniform("viewportSize", 2344f, 2156f)
        shader.setFloatUniform("strength", 0.25f)
        assertNotNull(RenderEffect.createRuntimeShaderEffect(shader, "content"))
    }

    @Test fun zeroStrengthAndFlatFillsPreservePixelsIncludingAlpha() {
        val source = Bitmap.createBitmap(17, 19, Bitmap.Config.ARGB_8888)
        try {
            for (color in intArrayOf(
                0xff5878a0.toInt(),
                Color.WHITE,
                Color.BLACK,
                0x80708090.toInt(),
                Color.TRANSPARENT,
            )) {
                source.eraseColor(color)
                for (strength in floatArrayOf(0f, 1f)) {
                    val output = render(source, strength)
                    try {
                        assertEquals(source.getPixel(8, 9), output.getPixel(8, 9))
                        assertEquals(source.getPixel(0, 0), output.getPixel(0, 0))
                        assertEquals(source.getPixel(16, 18), output.getPixel(16, 18))
                    } finally {
                        output.recycle()
                    }
                }
            }
        } finally {
            source.recycle()
        }
    }

    @Test fun enhancementChangesEdgesWithoutChangingAlphaOrExceedingShiftLimit() {
        val source = Bitmap.createBitmap(31, 23, Bitmap.Config.ARGB_8888)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val base = when {
                    x < 10 -> 70
                    x < 15 -> 160
                    x < 19 -> 90
                    else -> 120
                }
                source.setPixel(x, y, Color.rgb(base, base + 20, base + 30))
            }
        }
        val output = render(source, 1f)
        try {
            var changed = 0
            for (y in 0 until source.height) {
                for (x in 0 until source.width) {
                    val before = source.getPixel(x, y)
                    val after = output.getPixel(x, y)
                    if (before != after) changed++
                    assertEquals(Color.alpha(before), Color.alpha(after))
                    assertTrue(abs(Color.red(before) - Color.red(after)) <= 16)
                    assertTrue(abs((Color.green(after) - Color.red(after)) - 20) <= 1)
                    assertTrue(abs((Color.blue(after) - Color.red(after)) - 30) <= 1)
                }
            }
            assertTrue("The shader must actually change detailed pixels", changed > 0)
            assertEquals(source.getPixel(4, 10), output.getPixel(4, 10))
            val identity = render(source, 0f)
            try {
                val originalPixels = IntArray(source.width * source.height)
                val identityPixels = IntArray(originalPixels.size)
                source.getPixels(originalPixels, 0, source.width, 0, 0, source.width, source.height)
                identity.getPixels(identityPixels, 0, source.width, 0, 0, source.width, source.height)
                assertArrayEquals(originalPixels, identityPixels)
            } finally {
                identity.recycle()
            }
        } finally {
            output.recycle()
            source.recycle()
        }
    }
}
