package app.manyuenext.cloud.picacg

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PicacgApiTest {
    @Test
    fun `parse nested comics docs and relative thumb`() {
        val page = PicacgApi.parseFavorites("""{"data":{"comics":{"pages":2,"docs":[{"_id":"id","title":"Title","thumb":{"fileServer":"/a.jpg"}}]}}}""", 1)
        assertEquals("https://picaapi.picacomic.com/static/a.jpg", page.items.single().thumbnailUrl)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun `signature vector shape`() {
        assertEquals(64, PicacgApi.signature("users/favourite?page=1&s=dd", "1700000000", "abc", "GET").length)
    }
}
