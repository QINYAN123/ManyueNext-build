package eu.kanade.tachiyomi.ui.reader.metadata

import android.graphics.BitmapFactory
import okio.BufferedSource

object SourceImageBoundsDecoder {
    fun decode(source: BufferedSource): SourceImageInfo {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching {
            BitmapFactory.decodeStream(source.peek().inputStream(), null, options)
            fromBounds(options.outWidth, options.outHeight)
        }.getOrDefault(SourceImageInfo.Unavailable)
    }

    internal fun fromBounds(width: Int, height: Int): SourceImageInfo =
        if (width > 0 && height > 0) SourceImageInfo.Available(width, height) else SourceImageInfo.Unavailable
}
