package eu.kanade.tachiyomi.ui.reader.manyue

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Encapsulates Manyue enhancement preferences on top of Mihon's PreferenceStore.
 */
class ManyueEnhancementPreferences(private val store: PreferenceStore) {

    /** 0=OFF,1=CLASSIC,2=AI_2X,3=AI_2X_CLASSIC,4=AUTO */
    val enhancementMode: Preference<Int> =
        store.getInt("manyueEnhancementMode", ManyueEnhancementMode.OFF.value)

    /** 0..100, default 25 */
    val classicStrength: Preference<Int> = store.getInt("manyueClassicStrength", 25)

    /** Fixed native x2 model selected for AI requests. */
    val aiScalePercent: Preference<Int> = store.getInt("manyueAiScalePercent", 200)

    val aiModel: Preference<String> = store.getString("manyueAiModel", ManyueAiModel.DEFAULT.id)

    /** Optional Anime4KCPP post-filter overlay, kept off by default for lowest latency. */
    val anime4kOverlay: Preference<Boolean> = store.getBoolean("manyueAnime4kOverlay", false)

    /** Independent screen-space filter; it does not schedule AI or re-encode images. */
    val gpuDisplayFilter: Preference<Boolean> = store.getBoolean("manyueGpuDisplayFilter", false)
    val gpuDisplayStrength: Preference<Int> = store.getInt("manyueGpuDisplayStrength", 25)

    /** -1=AUTO, 0=FULL, >0=manual px */
    val foldableMode: Preference<Int> = store.getInt("manyueFoldableMode", FOLD_AUTO)

    val foldableTargetWidth: Preference<Int> = store.getInt("manyueFoldableTargetWidth", 2344)

    fun mode(): ManyueEnhancementMode = ManyueEnhancementMode.fromInt(enhancementMode.get())

    companion object {
        const val FOLD_AUTO = -1
        const val FOLD_FULL = 0
        const val FOLD_MANUAL = 1
    }
}
