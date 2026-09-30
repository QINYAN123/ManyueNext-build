package eu.kanade.tachiyomi.ui.reader.metadata

import eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementState

data class ReaderMetadataLabels(
    val availablePrefix: String,
    val loading: String,
    val unavailable: String,
    val enhancedPrefix: String = "Enhanced",
    val classicProcessing: String = "Classic processing",
    val classicReady: String = "Classic",
    val aiQueued: String = "AI queued",
    val aiProcessing: String = "AI processing",
    val aiWaitingDisplay: String = "AI ready, waiting for idle",
    val aiReady: String = "AI",
    val aiClassicReady: String = "AI + Classic",
    val skipped: String = "Skipped",
    val failed: String = "Failed",
)

object ReaderMetadataPresentation {
    fun visible(menuVisible: Boolean, preferenceEnabled: Boolean): Boolean = menuVisible && preferenceEnabled

    fun text(
        current: Int,
        total: Int,
        info: SourceImageInfo,
        labels: ReaderMetadataLabels,
    ): String {
        val page = "$current / $total"
        val image = when (info) {
            SourceImageInfo.Loading -> labels.loading
            SourceImageInfo.Unavailable -> labels.unavailable
            is SourceImageInfo.Available -> buildString {
                append("${labels.availablePrefix} ${info.width} × ${info.height}")
                if (info.enhancedWidth != null && info.enhancedHeight != null) {
                    append(" · ${labels.enhancedPrefix} ${info.enhancedWidth} × ${info.enhancedHeight}")
                }
                val status = when (info.enhancementState) {
                    ManyueEnhancementState.ORIGINAL -> null
                    ManyueEnhancementState.CLASSIC_PROCESSING -> labels.classicProcessing
                    ManyueEnhancementState.CLASSIC_READY -> labels.classicReady
                    ManyueEnhancementState.AI_QUEUED -> labels.aiQueued
                    ManyueEnhancementState.AI_PROCESSING -> labels.aiProcessing
                    ManyueEnhancementState.AI_WAITING_DISPLAY -> labels.aiWaitingDisplay
                    ManyueEnhancementState.AI_READY -> labels.aiReady
                    ManyueEnhancementState.AI_CLASSIC_READY -> labels.aiClassicReady
                    ManyueEnhancementState.SKIPPED -> labels.skipped
                    ManyueEnhancementState.FAILED -> labels.failed
                }
                if (status != null) {
                    append(" · $status")
                    info.enhancementDetail?.takeIf(String::isNotBlank)?.let { append(" ($it)") }
                }
            }
        }
        return "$page · $image"
    }
}
