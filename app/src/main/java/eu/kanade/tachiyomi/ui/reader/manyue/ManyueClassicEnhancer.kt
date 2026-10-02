package eu.kanade.tachiyomi.ui.reader.manyue

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Restrained classic enhancement for comic bitmaps.
 *
 * A small alpha-weighted luminance window boosts existing local detail without changing the
 * bitmap dimensions, synthesizing texture, or applying independent RGB contrast/saturation.
 * Input pixels are never mutated. The five-row window keeps working memory proportional to
 * image width instead of allocating a second full-image pixel array.
 */
object ManyueClassicEnhancer {

    private const val RADIUS = 2
    private const val WINDOW_SIZE = RADIUS * 2 + 1
    private const val MAX_DETAIL_SHIFT = 18f
    private const val LUMA_RED = 0.2126f
    private const val LUMA_GREEN = 0.7152f
    private const val LUMA_BLUE = 0.0722f

    fun effectiveLevel(strength: Int, isAiCombined: Boolean): Int {
        val boundedStrength = strength.coerceIn(0, 100)
        if (boundedStrength == 0) return 0
        return if (isAiCombined) maxOf(1, Math.round(boundedStrength * 0.45f)) else boundedStrength
    }

    /**
     * The caller owns the input and successful output. Failed temporary outputs are released.
     * A zero effective level is a true no-op and returns the original bitmap.
     */
    fun enhance(
        bitmap: Bitmap,
        strength: Int,
        isAiCombined: Boolean,
        isCancelled: () -> Boolean = { false },
    ): Bitmap {
        val level = effectiveLevel(strength, isAiCombined)
        if (level == 0) return bitmap

        var output: Bitmap? = null
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val enhanced = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            output = enhanced

            val amount = level / 100f
            val detailGain = 0.42f * amount
            val noiseGate = 2f + 2f * (1f - amount)
            val detailLimit = MAX_DETAIL_SHIFT * amount

            val sourceRow = IntArray(width)
            val processingRow = IntArray(width)
            val windowLuma = Array(WINDOW_SIZE) { FloatArray(width) }
            val windowAlpha = Array(WINDOW_SIZE) { FloatArray(width) }
            val horizontalPixels = IntArray(width)

            // Seed the vertical 5-row window. Edge rows are replicated; output border pixels
            // themselves are copied unchanged, avoiding artificial frame-edge contrast.
            for (slot in 0 until WINDOW_SIZE) {
                val sourceY = (slot - RADIUS).coerceIn(0, height - 1)
                fillHorizontalStatistics(
                    bitmap = bitmap,
                    y = sourceY,
                    pixels = horizontalPixels,
                    lumaSums = windowLuma[slot],
                    alphaSums = windowAlpha[slot],
                )
            }

            var windowStart = 0
            for (y in 0 until height) {
                if (y % 32 == 0 &&
                    isCancelled()
                ) {
                    throw kotlinx.coroutines.CancellationException("Classic job cancelled")
                }
                bitmap.getPixels(sourceRow, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val pixel = sourceRow[x]
                    val alpha = (pixel ushr 24) and 0xff
                    val centerLuma = luminance(pixel)

                    if (
                        x < RADIUS || x >= width - RADIUS ||
                        y < RADIUS || y >= height - RADIUS ||
                        alpha == 0 ||
                        centerLuma <= 0.5f || centerLuma >= 254.5f
                    ) {
                        processingRow[x] = pixel
                        continue
                    }

                    var localLumaSum = 0f
                    var localAlphaSum = 0f
                    for (offset in 0 until WINDOW_SIZE) {
                        val slot = (windowStart + offset) % WINDOW_SIZE
                        localLumaSum += windowLuma[slot][x]
                        localAlphaSum += windowAlpha[slot][x]
                    }

                    val localBase = if (localAlphaSum > 0f) {
                        localLumaSum / localAlphaSum
                    } else {
                        centerLuma
                    }
                    val residual = centerLuma - localBase
                    val aboveGate = abs(residual) - noiseGate
                    if (aboveGate <= 0f) {
                        processingRow[x] = pixel
                        continue
                    }

                    val signedDetail = if (residual < 0f) -aboveGate else aboveGate
                    val lumaShift = (signedDetail * detailGain).coerceIn(-detailLimit, detailLimit)
                    processingRow[x] = shiftRgbTogether(pixel, lumaShift)
                }
                enhanced.setPixels(processingRow, 0, width, 0, y, width, 1)

                if (y + 1 < height) {
                    val slotToReuse = windowStart
                    val sourceY = (y + RADIUS + 1).coerceIn(0, height - 1)
                    fillHorizontalStatistics(
                        bitmap = bitmap,
                        y = sourceY,
                        pixels = horizontalPixels,
                        lumaSums = windowLuma[slotToReuse],
                        alphaSums = windowAlpha[slotToReuse],
                    )
                    windowStart = (windowStart + 1) % WINDOW_SIZE
                }
            }
            enhanced
        } catch (t: Throwable) {
            output?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
            throw t
        }
    }

    private fun fillHorizontalStatistics(
        bitmap: Bitmap,
        y: Int,
        pixels: IntArray,
        lumaSums: FloatArray,
        alphaSums: FloatArray,
    ) {
        val width = bitmap.width
        bitmap.getPixels(pixels, 0, width, 0, y, width, 1)

        var lumaSum = 0f
        var alphaSum = 0f
        for (offset in -RADIUS..RADIUS) {
            val pixel = pixels[offset.coerceIn(0, width - 1)]
            val alpha = ((pixel ushr 24) and 0xff).toFloat()
            lumaSum += luminance(pixel) * alpha
            alphaSum += alpha
        }

        for (x in 0 until width) {
            lumaSums[x] = lumaSum
            alphaSums[x] = alphaSum

            val leaving = pixels[(x - RADIUS).coerceIn(0, width - 1)]
            val entering = pixels[(x + RADIUS + 1).coerceIn(0, width - 1)]
            val leavingAlpha = ((leaving ushr 24) and 0xff).toFloat()
            val enteringAlpha = ((entering ushr 24) and 0xff).toFloat()
            lumaSum += luminance(entering) * enteringAlpha - luminance(leaving) * leavingAlpha
            alphaSum += enteringAlpha - leavingAlpha
        }
    }

    private fun luminance(pixel: Int): Float {
        val red = ((pixel ushr 16) and 0xff).toFloat()
        val green = ((pixel ushr 8) and 0xff).toFloat()
        val blue = (pixel and 0xff).toFloat()
        return LUMA_RED * red + LUMA_GREEN * green + LUMA_BLUE * blue
    }

    /**
     * Adds a luminance offset equally to RGB, preserving channel differences (and therefore
     * hue/chroma) where the color gamut allows it. Clamp the shared offset before rounding so
     * no channel clips independently and creates colored edge halos.
     */
    private fun shiftRgbTogether(pixel: Int, requestedShift: Float): Int {
        if (requestedShift == 0f) return pixel

        val red = (pixel ushr 16) and 0xff
        val green = (pixel ushr 8) and 0xff
        val blue = pixel and 0xff
        val minimum = minOf(red, green, blue)
        val maximum = maxOf(red, green, blue)
        val shift = requestedShift
            .coerceIn(-minimum.toFloat(), (255 - maximum).toFloat())
            .roundToInt()
        if (shift == 0) return pixel

        val alpha = pixel and (0xff shl 24)
        return alpha or
            ((red + shift) shl 16) or
            ((green + shift) shl 8) or
            (blue + shift)
    }
}
