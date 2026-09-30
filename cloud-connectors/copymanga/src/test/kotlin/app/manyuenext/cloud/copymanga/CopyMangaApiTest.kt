package app.manyuenext.cloud.copymanga

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CopyMangaApiTest {
    @Test
    fun `parse nested results and exact last page`() {
        val page = CopyMangaApi.parseFavorites("""{"data":{"total":21,"results":[{"path_word":"a","name":"A"}]}}""", 1)
        assertEquals("/comic/a", page.items.single().canonicalUrl)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `signature is stable`() {
        assertEquals(CopyMangaApi.signature("1700000000"), CopyMangaApi.signature("1700000000"))
        assertTrue(CopyMangaApi.signature("1700000000").length == 64)
    }
}
