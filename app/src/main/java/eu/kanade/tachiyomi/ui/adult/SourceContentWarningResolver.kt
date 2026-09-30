package eu.kanade.tachiyomi.ui.adult

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mihon.domain.extension.model.ContentWarning

@Inject
@SingleIn(AppScope::class)
class SourceContentWarningResolver(extensionManager: ExtensionManager) {
    val warnings = extensionManager.loadedExtensionsFlow
        .map(::buildMap)
        .stateIn(extensionManager.scope, SharingStarted.Eagerly, emptyMap())

    fun warningFor(sourceId: Long): ContentWarning = warningFor(warnings.value, sourceId)

    companion object {
        fun buildMap(extensions: List<Extension.Loaded>): Map<Long, ContentWarning> = extensions
            .flatMap { extension -> extension.sources.map { source -> source.id to extension.contentWarning } }
            .toMap()

        fun warningFor(warnings: Map<Long, ContentWarning>, sourceId: Long): ContentWarning =
            warnings[sourceId] ?: ContentWarning.SAFE
    }
}
