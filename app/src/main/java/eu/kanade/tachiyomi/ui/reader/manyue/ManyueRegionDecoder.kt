package eu.kanade.tachiyomi.ui.reader.manyue

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Point
import android.graphics.Rect
import com.davemorrissey.labs.subscaleview.CropBorders
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.provider.InputProvider

/** AI output is PNG/WebP: keep encoded data and decode only requested tiles. */
@Suppress("DEPRECATION")
class ManyueRegionDecoder(
    private val cropBorders: Boolean = false,
    private val cacheLease: ManyueEnhancementCache.CacheLease? = null,
) : ImageRegionDecoder {
    private var decoder: BitmapRegionDecoder? = null
    private var crop = Rect()
    private var webp = false
    private var pendingCacheLease: ManyueEnhancementCache.CacheLease? = cacheLease
    private var activeCacheLease: ManyueEnhancementCache.CacheLease? = null

    @Synchronized
    override fun init(context: Context, provider: InputProvider): Point {
        var createdDecoder: BitmapRegionDecoder? = null
        try {
            recycleDecoder()
            activeCacheLease?.close()
            activeCacheLease = pendingCacheLease.also { pendingCacheLease = null }
            val next = provider.openStream().use { stream ->
                checkNotNull(stream) { "Missing AI image stream" }
                val input = stream.buffered()
                input.mark(12)
                val header = ByteArray(12)
                var count = 0
                while (count < header.size) {
                    val value = input.read()
                    if (value < 0) break
                    header[count++] = value.toByte()
                }
                input.reset()
                webp = count == 12 && header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                    header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
                    header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
                checkNotNull(BitmapRegionDecoder.newInstance(input, false)) { "Unsupported AI image" }
            }
            createdDecoder = next
            check(ManyueAiSafetyPolicy.isPixelBudgetSafe(next.width, next.height, ManyueAiSafetyPolicy.MAX_DISPLAY_PIXELS)) {
                "AI image exceeds display pixel budget"
            }
            crop = Rect(0, 0, next.width, next.height)
            if (cropBorders) crop = findCrop(next)
            decoder = next
            createdDecoder = null
            return Point(crop.width(), crop.height())
        } catch (error: Throwable) {
            createdDecoder?.let { runCatching { it.recycle() } }
            runCatching { recycleDecoder() }
            activeCacheLease?.close()
            activeCacheLease = null
            throw error
        }
    }

    // Cropping needs a one-time full scan. Its temporary bitmap and grayscale array are
    // released after init; no full RGBA buffer is retained or copied for every tile.
    private fun findCrop(source: BitmapRegionDecoder): Rect {
        val bitmap = checkNotNull(source.decodeRegion(crop, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }))
        val width = source.width
        val height = source.height
        val gray = try {
            val pixels = ByteArray(width * height)
            val rows = IntArray(width * minOf(64, height))
            var top = 0
            while (top < height) {
                val count = minOf(64, height - top)
                bitmap.getPixels(rows, 0, width, 0, top, width, count)
                for (i in 0 until width * count) {
                    val pixel = rows[i]
                    val red = (pixel ushr 16) and 255
                    val green = (pixel ushr 8) and 255
                    val blue = pixel and 255
                    pixels[top * width + i] = ((red * 77 + green * 150 + blue * 29) shr 8).toByte()
                }
                top += count
            }
            pixels
        } finally {
            bitmap.recycle()
        }
        val bounds = CropBorders.findCropBordersGray(gray, width, height)
        check(bounds.size == 4) { "Invalid crop result" }
        val left = bounds[0]
        val top = bounds[1]
        val w = bounds[2]
        val h = bounds[3]
        return if (left >= 0 && top >= 0 && w > 0 && h > 0 &&
            left.toLong() + w <= width && top.toLong() + h <= height
        ) Rect(left, top, left + w, top + h) else Rect(crop)
    }

    @Synchronized
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        val source = checkNotNull(decoder) { "AI decoder is not ready" }
        require(sampleSize > 0) { "Invalid sample size" }
        require(sRect.left >= 0 && sRect.top >= 0 && !sRect.isEmpty &&
            sRect.right <= crop.width() && sRect.bottom <= crop.height()
        ) { "Tile is outside AI image" }
        val region = Rect(sRect).apply { offset(crop.left, crop.top) }
        // WebP's native codec expands odd left/top coordinates to even coordinates. Ask for
        // that aligned subset explicitly, then map it back to the requested tile, otherwise
        // neighboring tiles overlap and cropped images shift by one source pixel.
        val aligned = if (webp) {
            Rect(region.left and -2, region.top and -2, region.right, region.bottom)
        } else Rect(region)
        val decoded = checkNotNull(source.decodeRegion(aligned, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })) { "AI tile decoding failed" }
        val targetWidth = (region.width() - 1) / sampleSize + 1
        val targetHeight = (region.height() - 1) / sampleSize + 1
        if (region == aligned && decoded.width == targetWidth && decoded.height == targetHeight) return decoded
        try {
            if (sampleSize == 1) {
                return Bitmap.createBitmap(
                    decoded, region.left - aligned.left, region.top - aligned.top,
                    region.width(), region.height(),
                )
            }
            val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            try {
                val scaleX = decoded.width.toFloat() / aligned.width()
                val scaleY = decoded.height.toFloat() / aligned.height()
                val subset = RectF(
                    (region.left - aligned.left) * scaleX,
                    (region.top - aligned.top) * scaleY,
                    (region.right - aligned.left) * scaleX,
                    (region.bottom - aligned.top) * scaleY,
                )
                val matrix = Matrix().apply {
                    setRectToRect(subset, RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat()), Matrix.ScaleToFit.FILL)
                }
                Canvas(result).drawBitmap(decoded, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
                return result
            } catch (error: Throwable) {
                result.recycle()
                throw error
            }
        } finally {
            decoded.recycle()
        }
    }

    @Synchronized
    override fun isReady(): Boolean = decoder?.isRecycled == false

    @Synchronized
    override fun recycle() {
        try {
            recycleDecoder()
        } finally {
            pendingCacheLease?.close()
            pendingCacheLease = null
            activeCacheLease?.close()
            activeCacheLease = null
        }
    }

    private fun recycleDecoder() {
        decoder?.recycle()
        decoder = null
        crop = Rect()
        webp = false
    }
}
