package eu.kanade.tachiyomi.ui.browse.cloud

import eu.kanade.tachiyomi.source.cloud.CloudFavoritesSource
import eu.kanade.tachiyomi.source.cloud.CloudProviderKey

sealed interface CloudConnectorState {
    data object Missing : CloudConnectorState
    data class Available(val source: CloudFavoritesSource, val packageName: String) : CloudConnectorState
    data class Duplicate(val packageNames: List<String>) : CloudConnectorState
}

data class CloudConnectorCandidate(
    val source: CloudFavoritesSource,
    val packageName: String,
    val builtIn: Boolean = false,
)

object CloudConnectorRegistry {
    fun resolve(candidates: List<CloudConnectorCandidate>, provider: CloudProviderKey): CloudConnectorState {
        val matches = candidates.filter { it.source.providerKey == provider }
        val builtIns = matches.filter { it.builtIn }
        val effective = when {
            builtIns.size == 1 -> builtIns
            builtIns.size > 1 -> builtIns
            else -> matches
        }
        return when (effective.size) {
            0 -> CloudConnectorState.Missing
            1 -> CloudConnectorState.Available(effective.single().source, effective.single().packageName)
            else -> CloudConnectorState.Duplicate(effective.map { it.packageName }.sorted())
        }
    }
}
