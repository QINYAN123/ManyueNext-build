package eu.kanade.tachiyomi.ui.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryTagFilterTest {
    @Test
    fun `available tags are trimmed deduplicated and sorted`() {
        assertEquals(
            listOf("Action", "Romance"),
            LibraryTagFilter.available(listOf(listOf(" Romance ", "Action"), listOf("action", ""))),
        )
    }

    @Test
    fun `matching is case insensitive and ignores surrounding whitespace`() {
        assertTrue(LibraryTagFilter.matches(listOf("  Romance "), "romance"))
    }
}
