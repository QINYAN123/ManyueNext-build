package eu.kanade.tachiyomi.ui.reader.manyue

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Canvas

/**
 * Classic (non-AI) bitmap enhancement: contrast/saturate/brightness + unsharp mask via 3x3 convolution.
 * Pure Android Bitmap processing; callers retain the original on failure.
 */
object ManyueClassicEnhancer {

    fun effectiveLevel(strength: Int, isAiCombined: Boolean): Int =
        if (strength <= 0) 0 else if (isAiCombined) maxOf(1, Math.round(strength * 0.45f)) else strength

    fun sharpenDelta(e: Double): Double = 0.18 * e + 0.24 * e * e

    fun contrast(e: Double): Double = 1.0 + 0.1 * e + 0.08 * e * e
    fun saturate(e: Double): Double = 1.0 + 0.04 * e
    fun brightness(e: Double): Double = 1.0 + 0.01 * e

    /**
     * The caller owns the input and successful output. Failed temporary outputs are released.
     */
    fun enhance(bitmap: Bitmap, strength: Int, isAiCombined: Boolean): Bitmap {
        var output: Bitmap? = null
        return try {
            val level = effectiveLevel(strength, isAiCombined)
            if (level <= 0) return bitmap
            val e = level / 100.0
            val d = sharpenDelta(e)

            val width = bitmap.width
            val height = bitmap.height
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            output = out

            // Color matrix: apply contrast + saturate + brightness.
            val cm = ColorMatrix()
            val sat = saturate(e).toFloat()
            val contrast = contrast(e).toFloat()
            val brightness = brightness(e).toFloat()
            cm.setSaturation(sat)
            val cmContrast = ColorMatrix().apply {
                setScale(contrast, contrast, contrast, 1f)
                postConcat(ColorMatrix(floatArrayOf(
                    brightness, 0f, 0f, 0f, 0f,
                    0f, brightness, 0f, 0f, 0f,
                    0f, 0f, brightness, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )))
            }
            cm.postConcat(cmContrast)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(cm)
            }
            Canvas(out).drawBitmap(bitmap, 0f, 0f, paint)

            // Unsharp mask via separable 3x3 convolution if d > 0.
            if (d > 0.001) {
                sharpenInPlace(out, d.toFloat())
            }
            out
        } catch (t: Throwable) {
            output?.takeIf { it !== bitmap && !it.isRecycled }?.recycle()
            throw t
        }
    }

    private fun sharpenInPlace(bmp: Bitmap, d: Float) {
        val w = bmp.width
        val h = bmp.height
        if (w < 3 || h < 3) return
        // Keep source rows until their last convolution use. Rows below the current one
        // have not been overwritten, so the output is identical to a full-image snapshot.
        var previous = IntArray(w)
        var current = IntArray(w)
        var next = IntArray(w)
        val result = IntArray(w)
        bmp.getPixels(previous, 0, w, 0, 0, w, 1)
        bmp.getPixels(current, 0, w, 0, 1, w, 1)
        bmp.getPixels(next, 0, w, 0, 2, w, 1)
        val c = 1f + 4f * d
        for (y in 1 until h - 1) {
            current.copyInto(result)
            for (x in 1 until w - 1) {
                val pC = current[x]
                val pU = previous[x]
                val pD = next[x]
                val pL = current[x - 1]
                val pR = current[x + 1]
                val a = (pC ushr 24) and 0xff
                val r = sharpenChannel(pC, pU, pD, pL, pR, c, d, 16)
                val g = sharpenChannel(pC, pU, pD, pL, pR, c, d, 8)
                val b = sharpenChannel(pC, pU, pD, pL, pR, c, d, 0)
                result[x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            bmp.setPixels(result, 0, w, 0, y, w, 1)
            val reuse = previous
            previous = current
            current = next
            next = reuse
            if (y + 2 < h) bmp.getPixels(next, 0, w, 0, y + 2, w, 1)
        }
    }

    private inline fun sharpenChannel(
        c: Int, u: Int, d: Int, l: Int, r: Int,
        cw: Float, dw: Float, shift: Int,
    ): Int {
        val cv = ((c shr shift) and 0xff).toFloat()
        val uv = ((u shr shift) and 0xff).toFloat()
        val dv = ((d shr shift) and 0xff).toFloat()
        val lv = ((l shr shift) and 0xff).toFloat()
        val rv = ((r shr shift) and 0xff).toFloat()
        val v = cv * cw - (uv + dv + lv + rv) * dw
        return v.coerceIn(0f, 255f).toInt()
    }
}
