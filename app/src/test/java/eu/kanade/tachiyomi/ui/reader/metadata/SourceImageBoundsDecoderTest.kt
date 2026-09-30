package eu.kanade.tachiyomi.ui.reader.metadata

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceImageBoundsDecoderTest {
    @Test
    fun `valid bounds are available`() {
        assertEquals(SourceImageInfo.Available(3, 2), SourceImageBoundsDecoder.fromBounds(3, 2))
    }

    @Test
    fun `invalid bounds are unavailable`() {
        assertEquals(SourceImageInfo.Unavailable, SourceImageBoundsDecoder.fromBounds(0, -1))
    }

    @Test
    fun `available page metadata is never downgraded by a later failure`() {
        val page = ReaderPage(index = 0)
        page.updateSourceImageInfo(SourceImageInfo.Available(3, 2))
        page.updateSourceImageInfo(SourceImageInfo.Unavailable)

        assertEquals(SourceImageInfo.Available(3, 2), page.sourceImageInfo.value)
    }
}
