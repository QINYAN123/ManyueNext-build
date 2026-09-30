package eu.kanade.tachiyomi.ui.reader.metadata

import eu.kanade.tachiyomi.ui.reader.manyue.ManyueEnhancementState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReaderMetadataPresentationTest {
    private val zhLabels = ReaderMetadataLabels(
        availablePrefix = "原图",
        loading = "原图读取中",
        unavailable = "原图未知",
    )

    @Test
    fun `metadata is visible only when controls and preference are enabled`() {
        assertFalse(ReaderMetadataPresentation.visible(menuVisible = false, preferenceEnabled = true))
        assertFalse(ReaderMetadataPresentation.visible(menuVisible = true, preferenceEnabled = false))
        assertTrue(ReaderMetadataPresentation.visible(menuVisible = true, preferenceEnabled = true))
    }

    @Test
    fun `available dimensions format on one compact line`() {
        assertEquals(
            "12 / 38 · 原图 2400 × 3600",
            ReaderMetadataPresentation.text(12, 38, SourceImageInfo.Available(2400, 3600), zhLabels),
        )
    }

    @Test
    fun `enhanced dimensions are shown from the actual output bitmap`() {
        assertEquals(
            "12 / 38 · 原图 690 × 1971 · 超分后 1380 × 3942",
            ReaderMetadataPresentation.text(
                12,
                38,
                SourceImageInfo.Available(690, 1971, enhancedWidth = 1380, enhancedHeight = 3942),
                zhLabels.copy(enhancedPrefix = "超分后"),
            ),
        )
    }

    @Test
    fun `actual enhancement state is shown independently from dimensions`() {
        assertEquals(
            "2 / 86 · 原图 690 × 1240 · AI 超分排队中",
            ReaderMetadataPresentation.text(
                2,
                86,
                SourceImageInfo.Available(
                    690,
                    1240,
                    enhancementState = ManyueEnhancementState.AI_QUEUED,
                ),
                zhLabels.copy(aiQueued = "AI 超分排队中"),
            ),
        )
    }

    @Test
    fun `loading and unavailable have distinct text`() {
        assertEquals("12 / 38 · 原图读取中", ReaderMetadataPresentation.text(12, 38, SourceImageInfo.Loading, zhLabels))
        assertEquals("12 / 38 · 原图未知", ReaderMetadataPresentation.text(12, 38, SourceImageInfo.Unavailable, zhLabels))
    }

    @Test
    fun `inference and waiting to display are not reported as queued`() {
        val labels = zhLabels.copy(aiProcessing = "AI 超分处理中", aiWaitingDisplay = "AI 超分已完成，等待停止滑动")
        assertEquals(
            "2 / 86 · 原图 690 × 1240 · AI 超分处理中",
            ReaderMetadataPresentation.text(2, 86, SourceImageInfo.Available(690, 1240, enhancementState = ManyueEnhancementState.AI_PROCESSING), labels),
        )
        assertEquals(
            "2 / 86 · 原图 690 × 1240 · AI 超分已完成，等待停止滑动",
            ReaderMetadataPresentation.text(2, 86, SourceImageInfo.Available(690, 1240, enhancementState = ManyueEnhancementState.AI_WAITING_DISPLAY), labels),
        )
    }
}
