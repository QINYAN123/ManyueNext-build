package eu.kanade.tachiyomi.source.cloud

import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

interface CloudFavoritesSource : ConfigurableSource {
    val providerKey: CloudProviderKey
    val targetSource: CloudTargetSource

    override val supportsLatest: Boolean get() = false

    suspend fun accountState(): CloudAccountState
    suspend fun refreshSession(): CloudAccountState
    suspend fun getCloudFavorites(page: Int): CloudFavoritesPage

    override suspend fun getPopularManga(page: Int): MangasPage = unsupported()
    override suspend fun getLatestUpdates(page: Int): MangasPage = unsupported()
    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = unsupported()
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = unsupported()
    override suspend fun getPageList(chapter: SChapter): List<Page> = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("Cloud connector is not a reading source")
}
