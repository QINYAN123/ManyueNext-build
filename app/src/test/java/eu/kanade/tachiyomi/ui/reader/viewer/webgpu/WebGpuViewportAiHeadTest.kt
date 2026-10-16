package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WebGpuViewportAiHeadTest {
    private data class Page(val chapter: String, val index: Int, val viewportFraction: Float = 1f)

    @Test fun `partly visible bottom page wins over the reader top anchor`() {
        val top = Page("a", 4)
        val middle = Page("a", 5)
        val bottom = Page("a", 6, .035f)
        // The producer already clips positive overlaps. Counting thresholds must not
        // exclude a visible strip that still needs to be decoded and enhanced.
        assertEquals(bottom, WebGpuViewportAiHead.head(linkedSetOf(top, middle, bottom), top))
    }

    @Test fun `empty viewport retains fallback only until visible geometry arrives`() {
        val fallback = Page("a", 12)
        assertEquals(fallback, WebGpuViewportAiHead.head(emptyList(), fallback))
        assertNull(WebGpuViewportAiHead.head(emptyList<Page>(), null))
        val bottom = Page("a", 14)
        assertEquals(bottom, WebGpuViewportAiHead.head(listOf(bottom), fallback))
    }

    @Test fun `screen order wins across chapter indexes that restart at zero`() {
        val previous = Page("a", 37)
        val next = Page("b", 0)
        assertEquals(next, WebGpuViewportAiHead.head(linkedSetOf(previous, next), previous))
    }

    @Test fun `choosing head preserves the full visible set while reading back`() {
        val visible = linkedSetOf(Page("a", 9), Page("a", 8), Page("a", 7, .02f))
        val original = visible.toSet()
        assertEquals(visible.last(), WebGpuViewportAiHead.head(visible, visible.first()))
        assertEquals(original, visible)
    }
}
