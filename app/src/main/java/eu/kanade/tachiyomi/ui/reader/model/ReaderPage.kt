package eu.kanade.tachiyomi.ui.reader.model

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementState
import eu.kanade.tachiyomi.ui.reader.metadata.SourceImageInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    private val mutableSourceImageInfo = MutableStateFlow<SourceImageInfo>(SourceImageInfo.Loading)
    val sourceImageInfo = mutableSourceImageInfo.asStateFlow()

    /** Clear stale applied dimensions before a new stream/settings generation starts loading. */
    fun resetEnhancementState(mode: Int) {
        updateEnhancementState(
            when {
                eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementMode.fromInt(mode).usesAi() ->
                    ManyueEnhancementState.AI_QUEUED
                mode == eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementMode.CLASSIC.value ->
                    ManyueEnhancementState.CLASSIC_PROCESSING
                else -> ManyueEnhancementState.ORIGINAL
            },
        )
    }

    fun updateSourceImageInfo(info: SourceImageInfo) {
        mutableSourceImageInfo.update { current ->
            when {
                info is SourceImageInfo.Available && current is SourceImageInfo.Available &&
                    info.width == current.width && info.height == current.height -> info.copy(
                    enhancedWidth = current.enhancedWidth,
                    enhancedHeight = current.enhancedHeight,
                    enhancementState = current.enhancementState,
                    enhancementDetail = current.enhancementDetail,
                )
                info is SourceImageInfo.Available -> info
                current is SourceImageInfo.Loading -> info
                else -> current
            }
        }
    }

    fun updateEnhancedImageInfo(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        mutableSourceImageInfo.update { current ->
            (current as? SourceImageInfo.Available)?.copy(
                enhancedWidth = width,
                enhancedHeight = height,
            ) ?: current
        }
    }

    fun updateEnhancementState(state: ManyueEnhancementState, detail: String? = null) {
        mutableSourceImageInfo.update { current ->
            (current as? SourceImageInfo.Available)?.copy(
                enhancementState = state,
                enhancementDetail = detail,
                enhancedWidth = if (
                    state == ManyueEnhancementState.AI_READY || state == ManyueEnhancementState.AI_CLASSIC_READY ||
                    state == ManyueEnhancementState.INTERPOLATED_READY ||
                    state == ManyueEnhancementState.CLASSIC_READY
                ) {
                    current.enhancedWidth
                } else {
                    null
                },
                enhancedHeight = if (
                    state == ManyueEnhancementState.AI_READY || state == ManyueEnhancementState.AI_CLASSIC_READY ||
                    state == ManyueEnhancementState.INTERPOLATED_READY ||
                    state == ManyueEnhancementState.CLASSIC_READY
                ) {
                    current.enhancedHeight
                } else {
                    null
                },
            ) ?: current
        }
    }

    open lateinit var chapter: ReaderChapter
}
