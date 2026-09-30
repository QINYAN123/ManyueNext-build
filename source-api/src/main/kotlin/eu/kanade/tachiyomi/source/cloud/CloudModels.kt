package eu.kanade.tachiyomi.source.cloud

enum class CloudProviderKey { COPY_MANGA, JM, PICACG }

data class CloudTargetSource(val sourceId: Long, val packageHint: String, val displayName: String)

sealed interface CloudAccountState {
    data class Ready(val displayName: String?) : CloudAccountState
    data object LoginRequired : CloudAccountState
    data object SessionExpired : CloudAccountState
}

data class CloudFavorite(
    val remoteId: String,
    val canonicalUrl: String,
    val title: String,
    val thumbnailUrl: String?,
    val author: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
) {
    init {
        require(remoteId.isNotBlank())
        require(canonicalUrl.isNotBlank())
        require(title.isNotBlank())
    }
}

data class CloudFavoritesPage(val page: Int, val items: List<CloudFavorite>, val hasNextPage: Boolean) {
    init {
        require(page > 0)
    }
}

enum class CloudFailure { Authentication, RateLimited, Network, Remote, Parsing }

class CloudFavoritesException(
    val failure: CloudFailure,
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)
