package eu.kanade.tachiyomi.ui.reader.manyue

import android.graphics.Bitmap
import android.os.Build

/** WEBP_LOSSY was added in API 30; retain lossy encoding on supported Android 8–10. */
internal object ManyueBitmapEncoding {
    @Suppress("DEPRECATION")
    fun lossyWebpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    /** Lossless quality is compression effort, not pixel quality; legacy Android uses PNG. */
    fun fastLosslessFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            Bitmap.CompressFormat.PNG
        }
}
