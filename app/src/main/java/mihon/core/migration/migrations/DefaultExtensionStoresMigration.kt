package mihon.core.migration.migrations

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext

/** Adds the two recommended repositories once without silently installing anything. */
@Inject
@ContributesIntoSet(AppScope::class)
class DefaultExtensionStoresMigration(
    private val preferenceStore: PreferenceStore,
    private val repository: ExtensionStoreRepository,
) : Migration {

    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val initialized = preferenceStore.getBoolean(INITIALIZED_KEY, false)
        if (initialized.get()) return@withIOContext true

        val existing = repository.getAll().mapTo(mutableSetOf()) { it.indexUrl }
        DEFAULTS.filterNot { it.indexUrl in existing }.forEach { store ->
            repository.insertFromPreference(
                indexUrl = store.indexUrl,
                name = store.name,
                signingKey = store.signingKey,
                isLegacy = store.isLegacy,
            )
        }
        initialized.set(true)
        true
    }

    private data class DefaultStore(
        val indexUrl: String,
        val name: String,
        val signingKey: String,
        val isLegacy: Boolean,
    )

    companion object {
        private val INITIALIZED_KEY = Preference.appStateKey("manyue_default_extension_stores")
        private val DEFAULTS = listOf(
            DefaultStore(
                indexUrl = "https://github.com/keiyoushi/extensions/raw/repo/index.pb",
                name = "Keiyoushi",
                signingKey = "9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
                isLegacy = false,
            ),
            DefaultStore(
                indexUrl = "https://raw.githubusercontent.com/LittleSurvival/copymanga-copy20/repo/repo.json",
                name = "TheNano",
                signingKey = "896d1ca35175c42baf61b1a157e4354df9568beed4ccc82a03762d1167005e79",
                isLegacy = true,
            ),
        )
    }
}
