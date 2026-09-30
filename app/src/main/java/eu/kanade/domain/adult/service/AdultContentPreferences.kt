package eu.kanade.domain.adult.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class AdultContentPreferences(
    preferenceStore: PreferenceStore,
) {
    val autoDetectionEnabled: Preference<Boolean> = preferenceStore.getBoolean(
        "adult_content_auto_detection_enabled",
        true,
    )
}
