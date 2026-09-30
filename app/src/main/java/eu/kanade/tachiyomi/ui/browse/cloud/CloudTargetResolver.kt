package eu.kanade.tachiyomi.ui.browse.cloud

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.cloud.CloudFavoritesSource
import eu.kanade.tachiyomi.source.cloud.CloudTargetSource

sealed interface CloudTargetResolution {
    data class Available(val source: Source) : CloudTargetResolution
    data class Missing(val target: CloudTargetSource) : CloudTargetResolution
}

class CloudTargetResolver(private val sourceLookup: suspend (Long) -> Source?) {
    suspend fun resolve(target: CloudTargetSource): CloudTargetResolution {
        val source = sourceLookup(target.sourceId)
        return if (source == null || source is CloudFavoritesSource) {
            CloudTargetResolution.Missing(target)
        } else CloudTargetResolution.Available(source)
    }
}
