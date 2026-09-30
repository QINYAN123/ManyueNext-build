package eu.kanade.tachiyomi.source.cloud

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CloudModelsTest {
    @Test
    fun `invalid pages and favorites are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { CloudFavoritesPage(0, emptyList(), false) }
        assertThrows(IllegalArgumentException::class.java) { CloudFavorite("1", "", "", null) }
    }

    @Test
    fun `user message never embeds technical cause`() {
        val error = CloudFavoritesException(
            CloudFailure.Authentication,
            "登录已失效",
            IllegalStateException("Bearer secret"),
        )
        assertFalse(error.userMessage.contains("secret"))
    }
}
