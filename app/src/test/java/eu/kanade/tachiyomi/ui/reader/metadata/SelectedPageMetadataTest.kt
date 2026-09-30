package eu.kanade.tachiyomi.ui.reader.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SelectedPageMetadataTest {
    @Test
    fun `new selection clears dimensions immediately`() {
        val state = SelectedPageMetadata(selectedKey = "old", info = SourceImageInfo.Available(3, 2))
        assertEquals(SourceImageInfo.Loading, state.select("new", SourceImageInfo.Loading).info)
    }

    @Test
    fun `late update from old page is ignored`() {
        val state = SelectedPageMetadata("new", SourceImageInfo.Loading)
        assertEquals(state, state.update("old", SourceImageInfo.Available(99, 99)))
    }
}
