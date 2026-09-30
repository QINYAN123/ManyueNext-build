package eu.kanade.tachiyomi.ui.reader.metadata

import okio.BufferedSource

object SourceImageCaptureOrder {
    fun <T> captureThenTransform(
        raw: BufferedSource,
        publish: (SourceImageInfo) -> Unit,
        transform: (BufferedSource) -> T,
    ): T {
        publish(SourceImageBoundsDecoder.decode(raw))
        return transform(raw)
    }
}
