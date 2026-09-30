package eu.kanade.tachiyomi.ui.reader

import java.util.Date
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History

class ResumeTargetResolverTest {
    @Test
    fun `latest history resolves its chapter and saved page`() {
        val old = Chapter.create().copy(id = 1, lastPageRead = 2)
        val latest = Chapter.create().copy(id = 2, lastPageRead = 17)
        val target = ResumeTargetResolver.resolve(
            history = listOf(
                History(1, 1, Date(100), 0),
                History(2, 2, Date(200), 0),
            ),
            chapters = listOf(old, latest),
            fallback = null,
        )

        assertEquals(2L, target?.chapter?.id)
        assertEquals(17, target?.page)
    }

    @Test
    fun `missing history falls back to the next unread chapter`() {
        val fallback = Chapter.create().copy(id = 8, lastPageRead = 4)

        val target = ResumeTargetResolver.resolve(emptyList(), emptyList(), fallback)

        assertEquals(8L, target?.chapter?.id)
        assertEquals(4, target?.page)
    }
}
