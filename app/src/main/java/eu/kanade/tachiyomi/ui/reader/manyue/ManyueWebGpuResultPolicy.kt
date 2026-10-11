package eu.kanade.tachiyomi.ui.reader.manyue

/** A submitted frame may only claim the exact source and session prepared for its page slot. */
internal object ManyueWebGpuResultPolicy {
    fun matches(
        identity: ManyuePageBridge.Identity,
        generation: Long,
        mode: Int,
        model: Int,
        scale: Int,
        fingerprint: String?,
        result: ManyueReadingCoverage.Result,
    ): Boolean = result.generation == generation && result.mode == mode && result.model == model &&
        result.scalePercent == scale && result.source.mangaId == identity.mangaId &&
        result.source.chapterId == identity.chapterId && result.source.page == identity.pageIndex &&
        !fingerprint.isNullOrBlank() && result.source.fingerprint == fingerprint && result.token.isNotBlank() &&
        result.source.sourceWidth > 0 && result.source.sourceHeight > 0 &&
        result.outputWidth.toLong() == result.source.sourceWidth.toLong() * 2 &&
        result.outputHeight.toLong() == result.source.sourceHeight.toLong() * 2
}
