package eu.kanade.tachiyomi.ui.reader.metadata

import eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementState

sealed interface SourceImageInfo {
    data object Loading : SourceImageInfo
    data class Available(
        val width: Int,
        val height: Int,
        val enhancedWidth: Int? = null,
        val enhancedHeight: Int? = null,
        val enhancementState: ManyueEnhancementState = ManyueEnhancementState.ORIGINAL,
        val enhancementDetail: String? = null,
    ) : SourceImageInfo
    data object Unavailable : SourceImageInfo
}
