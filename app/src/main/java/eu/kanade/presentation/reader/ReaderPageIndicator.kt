package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.reader.metadata.ReaderMetadataLabels
import eu.kanade.tachiyomi.ui.reader.metadata.ReaderMetadataPresentation
import eu.kanade.tachiyomi.ui.reader.metadata.SourceImageInfo
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderPageIndicator(
    currentPage: Int,
    totalPages: Int,
    sourceImageInfo: SourceImageInfo,
    modifier: Modifier = Modifier,
    displayEnhancementLabel: String? = null,
) {
    if (currentPage <= 0 || totalPages <= 0) return

    val labels = ReaderMetadataLabels(
        availablePrefix = stringResource(MR.strings.reader_source_image_original),
        loading = stringResource(MR.strings.reader_source_image_loading),
        unavailable = stringResource(MR.strings.reader_source_image_unavailable),
        enhancedPrefix = stringResource(MR.strings.reader_source_image_enhanced),
        classicProcessing = stringResource(MR.strings.reader_enhancement_classic_processing),
        classicReady = stringResource(MR.strings.reader_enhancement_classic_ready),
        aiQueued = stringResource(MR.strings.reader_enhancement_ai_queued),
        aiPreparing = stringResource(MR.strings.reader_enhancement_ai_preparing),
        aiWaitingResources = stringResource(MR.strings.reader_enhancement_ai_waiting_resources),
        aiProcessing = stringResource(MR.strings.reader_enhancement_ai_processing),
        aiWaitingDisplay = stringResource(MR.strings.reader_enhancement_ai_waiting_display),
        aiReady = stringResource(MR.strings.reader_enhancement_ai_ready),
        aiClassicReady = stringResource(MR.strings.reader_enhancement_ai_classic_ready),
        skipped = stringResource(MR.strings.reader_enhancement_skipped),
        failed = stringResource(MR.strings.reader_enhancement_failed),
        interpolatedReady = stringResource(MR.strings.reader_enhancement_interpolated_ready),
    )
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.78f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = buildString {
                append(ReaderMetadataPresentation.text(currentPage, totalPages, sourceImageInfo, labels))
                displayEnhancementLabel?.let { append(" · $it") }
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@PreviewLightDark
@Composable
private fun ReaderPageIndicatorPreview() {
    TachiyomiPreviewTheme {
        Surface {
            ReaderPageIndicator(
                currentPage = 10,
                totalPages = 69,
                sourceImageInfo = SourceImageInfo.Available(2400, 3600),
            )
        }
    }
}
