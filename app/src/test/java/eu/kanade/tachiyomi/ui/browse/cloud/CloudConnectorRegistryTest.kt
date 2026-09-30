package eu.kanade.tachiyomi.ui.browse.cloud

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.cloud.BuiltInCloudConnectors
import eu.kanade.tachiyomi.source.cloud.CloudAccountState
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesPage
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesSource
import eu.kanade.tachiyomi.source.cloud.CloudProviderKey
import eu.kanade.tachiyomi.source.cloud.CloudTargetSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudConnectorRegistryTest {
    @Test
    fun `built in connector wins over an installed compatibility apk`() {
        val builtIn = BuiltInCloudConnectors.sources.first { it.providerKey == CloudProviderKey.COPY_MANGA }
        val external = FakeCloudSource(CloudProviderKey.COPY_MANGA)

        val result = CloudConnectorRegistry.resolve(
            candidates = listOf(
                CloudConnectorCandidate(external, "app.manyuenext.cloud.copymanga"),
                CloudConnectorCandidate(builtIn, "内置", builtIn = true),
            ),
            provider = CloudProviderKey.COPY_MANGA,
        )

        assertTrue(result is CloudConnectorState.Available)
        val available = result as CloudConnectorState.Available
        assertEquals(builtIn, available.source)
        assertEquals("内置", available.packageName)
    }

    private class FakeCloudSource(
        override val providerKey: CloudProviderKey,
    ) : CloudFavoritesSource {
        override val id = 9001L
        override val name = "Fake cloud source"
        override val targetSource = CloudTargetSource(9002L, "fake", "Fake")

        override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

        override suspend fun accountState() = CloudAccountState.LoginRequired

        override suspend fun refreshSession() = CloudAccountState.LoginRequired

        override suspend fun getCloudFavorites(page: Int) = CloudFavoritesPage(page, emptyList(), false)
    }
}
