package eu.kanade.tachiyomi.ui.reader.metadata

import android.graphics.BitmapFactory
import okio.BufferedSource

object SourceImageBoundsDecoder {
    /** The caller already owns encoded bytes: avoid copying them into an intermediate Buffer. */
    fun decode(bytes: ByteArray): SourceImageInfo {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            fromBounds(options.outWidth, options.outHeight)
        }.getOrDefault(SourceImageInfo.Unavailable)
    }

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
