package app.manyuenext.cloud.jm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class JmApiTest {
    @Test
    fun `parse ids and empty cover`() {
        val page = JmApi.parseFavorites("""{"data":{"list":[{"aid":"123","title":"作品"}]}}""", 1)
        assertEquals("123", page.items.single().remoteId)
        assertFalse(page.hasNextPage)
    }

    @Test
    fun `token vector has md5 shape`() {
        assertEquals(32, JmApi.token(1700000000).length)
    }
}
