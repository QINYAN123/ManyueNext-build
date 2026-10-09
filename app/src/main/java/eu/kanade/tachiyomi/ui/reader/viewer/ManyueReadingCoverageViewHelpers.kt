package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.manyue.ManyueReadingCoverage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

internal data class ManyueCoverageBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class ManyueCoverageFractions(
    val visibleAreaPx: Long,
    val viewportFractionPermille: Int,
    val pageFractionPermille: Int,
)

/** Coverage geometry shared by pager and webtoon holders; rectangle work runs on scroll/layout changes. */
internal fun manyueCoverageFractions(
    page: ManyueCoverageBounds,
    visible: ManyueCoverageBounds,
    viewport: ManyueCoverageBounds,
): ManyueCoverageFractions {
    val left = maxOf(visible.left, viewport.left)
    val top = maxOf(visible.top, viewport.top)
    val right = minOf(visible.right, viewport.right)
    val bottom = minOf(visible.bottom, viewport.bottom)
    val visibleWidth = (right - left).coerceAtLeast(0).toLong()
    val visibleHeight = (bottom - top).coerceAtLeast(0).toLong()
    val visibleArea = visibleWidth * visibleHeight
    val viewportArea = (viewport.right - viewport.left).coerceAtLeast(0).toLong() *
        (viewport.bottom - viewport.top).coerceAtLeast(0).toLong()
    val pageArea = (page.right - page.left).coerceAtLeast(0).toLong() *
        (page.bottom - page.top).coerceAtLeast(0).toLong()

    fun fraction(area: Long, total: Long): Int =
        if (total <= 0L) 0 else ((area * 1000L) / total).toInt().coerceIn(0, 1000)

    return ManyueCoverageFractions(
        visibleAreaPx = visibleArea,
        viewportFractionPermille = fraction(visibleArea, viewportArea),
        pageFractionPermille = fraction(visibleArea, pageArea),
    )
}

internal fun isManyueCoverageExposureEligible(
    fractions: ManyueCoverageFractions,
    isShortPage: Boolean,
): Boolean = fractions.viewportFractionPermille >= 200 ||
    (isShortPage && fractions.pageFractionPermille >= 500)

internal fun ReaderPage.toManyueCoverageSource(
    fingerprint: String? = null,
    sourceWidth: Int = 0,
    sourceHeight: Int = 0,
): ManyueReadingCoverage.Source = ManyueReadingCoverage.Source(
    mangaId = chapter.chapter.manga_id ?: 0L,
    chapterId = chapter.chapter.id ?: 0L,
    page = index,
    fingerprint = fingerprint,
    sourceWidth = sourceWidth,
    sourceHeight = sourceHeight,
)

/** Uses the same encoded input as the AI request, including pager split/rotation transforms. */
internal fun manyueCoverageInputSize(
    encodedBytes: ByteArray?,
    fallbackWidth: Int,
    fallbackHeight: Int,
): Pair<Int, Int> {
    if (encodedBytes != null) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeByteArray(encodedBytes, 0, encodedBytes.size, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) return bounds.outWidth to bounds.outHeight
    }
    return fallbackWidth.coerceAtLeast(0) to fallbackHeight.coerceAtLeast(0)
}

/** Calls coverage only after the draw body returns successfully; failed draws are rethrown. */
internal inline fun drawReaderContentThenReport(
    draw: () -> Unit,
    onDraw: () -> Unit,
    onFailure: (Throwable) -> Unit,
) {
    try {
        draw()
    } catch (error: Throwable) {
        runCatching { onFailure(error) }
        throw error
    }
    onDraw()
}

internal object ManyueCoverageDrawPolicy {
    fun shouldRecordFailure(
        isActiveChild: Boolean,
        isShown: Boolean,
        exposureEligible: Boolean,
        imageIsAi: Boolean,
    ): Boolean = isActiveChild && isShown && exposureEligible && imageIsAi

    fun shouldReport(
        isActiveChild: Boolean,
        isImageReady: Boolean,
        isShown: Boolean,
        exposureEligible: Boolean,
        source: ManyueReadingCoverage.Source?,
        result: ManyueReadingCoverage.Result?,
        imageIsAi: Boolean,
        expectedGeneration: Long?,
        actualOutputWidth: Int? = null,
        actualOutputHeight: Int? = null,
    ): Boolean {
        if (!isActiveChild || !isImageReady || !isShown || !exposureEligible || source == null) return false
        if (!imageIsAi) return result == null && expectedGeneration == null
        val stamp = result ?: return false
        val generation = expectedGeneration ?: return false
        return stamp.generation == generation &&
            source == stamp.source &&
            stamp.outputWidth > 0 && stamp.outputHeight > 0 &&
            actualOutputWidth == stamp.outputWidth &&
            actualOutputHeight == stamp.outputHeight
    }
}
