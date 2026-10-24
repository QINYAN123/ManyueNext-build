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
    val aiPreparing: String = "AI preparing",
    val aiWaitingResources: String = "AI waiting for resources",
    val aiProcessing: String = "AI processing",
    val aiWaitingDisplay: String = "AI ready, preparing display",
    val aiReady: String = "AI",
    val aiClassicReady: String = "AI + Classic",
    val skipped: String = "Skipped",
    val failed: String = "Failed",
    val interpolatedReady: String = "Interpolation",
)

object ReaderMetadataPresentation {
    fun visible(menuVisible: Boolean, preferenceEnabled: Boolean): Boolean = menuVisible && preferenceEnabled

    fun text(
        current: Int,
        total: Int,
        info: SourceImageInfo,
        labels: ReaderMetadataLabels,
        visibleHeadSourceImageInfo: SourceImageInfo? = null,
        metadataAttribution: String? = null,
    ): String {
        val page = "$current / $total"
        val effectiveInfo = visibleHeadSourceImageInfo ?: info
        val image = when (effectiveInfo) {
            SourceImageInfo.Loading -> labels.loading
            SourceImageInfo.Unavailable -> labels.unavailable
            is SourceImageInfo.Available -> buildString {
                append("${labels.availablePrefix} ${effectiveInfo.width} × ${effectiveInfo.height}")
                if (effectiveInfo.enhancedWidth != null && effectiveInfo.enhancedHeight != null) {
                    append(
                        " · ${labels.enhancedPrefix} ${effectiveInfo.enhancedWidth} × ${effectiveInfo.enhancedHeight}",
                    )
                }
                val status = when (effectiveInfo.enhancementState) {
                    ManyueEnhancementState.ORIGINAL -> null
                    ManyueEnhancementState.CLASSIC_PROCESSING -> labels.classicProcessing
                    ManyueEnhancementState.CLASSIC_READY -> labels.classicReady
                    ManyueEnhancementState.AI_QUEUED -> labels.aiQueued
                    ManyueEnhancementState.AI_PREPARING -> labels.aiPreparing
                    ManyueEnhancementState.AI_WAITING_RESOURCES -> labels.aiWaitingResources
                    ManyueEnhancementState.AI_PROCESSING -> labels.aiProcessing
                    ManyueEnhancementState.AI_WAITING_DISPLAY -> labels.aiWaitingDisplay
                    ManyueEnhancementState.AI_READY -> labels.aiReady
                    ManyueEnhancementState.AI_CLASSIC_READY -> labels.aiClassicReady
                    ManyueEnhancementState.INTERPOLATED_READY -> labels.interpolatedReady
                    ManyueEnhancementState.SKIPPED -> labels.skipped
                    ManyueEnhancementState.FAILED -> labels.failed
                }
                if (status != null) {
                    append(" · $status")
                    effectiveInfo.enhancementDetail?.takeIf(String::isNotBlank)?.let { append(" ($it)") }
                }
            }
        }
        val attribution = metadataAttribution?.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
        return "$page$attribution · $image"
    }
}
