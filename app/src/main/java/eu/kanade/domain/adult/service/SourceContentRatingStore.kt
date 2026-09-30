package eu.kanade.domain.adult.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import mihon.domain.content.model.ContentRatingOverride
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class SourceContentRatingStore(
    preferenceStore: PreferenceStore,
) {
    private val values: Preference<Set<String>> = preferenceStore.getStringSet(
        "source_content_rating_overrides",
        emptySet(),
    )

    fun get(sourceId: Long): ContentRatingOverride {
        val prefix = "$sourceId:"
        return values.get()
            .firstOrNull { it.startsWith(prefix) }
            ?.substringAfter(prefix)
            ?.toIntOrNull()
            ?.let { code -> ContentRatingOverride.fromCode(code) }
            ?: ContentRatingOverride.AUTO
    }

    fun set(sourceId: Long, override: ContentRatingOverride) {
        val prefix = "$sourceId:"
        values.set(values.get().filterNot { it.startsWith(prefix) }.toSet() + "$prefix${override.code}")
    }

    fun clear(sourceId: Long) {
        val prefix = "$sourceId:"
        values.set(values.get().filterNot { it.startsWith(prefix) }.toSet())
    }
}
