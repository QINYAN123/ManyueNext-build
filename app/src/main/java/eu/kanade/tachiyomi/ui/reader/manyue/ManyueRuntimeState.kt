package eu.kanade.tachiyomi.ui.reader.manyue

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

/**
 * Process-wide snapshot of the currently selected Manyue enhancement settings.
 * Written by the reader settings UI and by [syncFrom] on page load; read by the pipeline.
 * Defaults keep the original fast path (mode=OFF).
 */
object ManyueRuntimeState {
    @Volatile var modeInt: Int = 0          // ManyueEnhancementMode.OFF
        private set
    @Volatile var classicStrength: Int = 25
        private set
    @Volatile var aiModel: ManyueAiModel = ManyueAiModel.DEFAULT
        private set
    @Volatile var aiScalePercent: Int = 200
        private set
    @Volatile var anime4kOverlay: Boolean = false
        private set
    @Volatile var foldableMode: Int = -1
    @Volatile var foldableTargetWidth: Int = 2344
    @Volatile var generation: Long = 0L
        private set

    /**
     * Changes the enhancement mode and invalidates every request created for the old mode.
     * The generation is intentionally checked again immediately before a completed bitmap is
     * installed into a Reader view.
     */
    @Synchronized
    fun updateMode(newMode: Int) {
        if (modeInt == newMode) return
        modeInt = newMode
        generation++
        ManyuePrefetchManager.reset()
    }

    @Synchronized
    fun updateClassicStrength(newStrength: Int) {
        if (classicStrength == newStrength) return
        classicStrength = newStrength.coerceIn(0, 100)
        if (modeInt == ManyueEnhancementMode.CLASSIC.value ||
            modeInt == ManyueEnhancementMode.AI_2X_CLASSIC.value
        ) {
            generation++
            ManyuePrefetchManager.reset()
        }
    }

    @Synchronized
    fun updateAiModel(newModel: ManyueAiModel) {
        if (aiModel == newModel) return
        aiModel = newModel
        generation++
        ManyuePrefetchManager.reset()
    }

    @Synchronized
    fun updateAiScale(percent: Int) {
        val safePercent = percent.coerceIn(100, 200)
        if (aiScalePercent == safePercent) return
        aiScalePercent = safePercent
        generation++
        ManyuePrefetchManager.reset()
    }

    @Synchronized
    fun updateAnime4kOverlay(enabled: Boolean) {
        if (anime4kOverlay == enabled) return
        anime4kOverlay = enabled
        generation++
        ManyuePrefetchManager.reset()
    }

    /** Invalidates tasks when a chapter/viewer session changes, without changing preferences. */
    @Synchronized
    fun invalidateSession() {
        generation++
        ManyuePrefetchManager.reset()
    }

    /** Read current values from the injected ReaderPreferences. Cheap; call on each page load. */
    fun syncFrom(prefs: ReaderPreferences) {
        updateMode(prefs.manyueEnhancementMode.get())
        updateClassicStrength(prefs.manyueClassicStrength.get())
        updateAiModel(ManyueAiModel.fromId(prefs.manyueAiModel.get()))
        updateAnime4kOverlay(prefs.manyueAnime4kOverlay.get())
        updateAiScale(prefs.manyueAiScalePercent.get())
        foldableMode = prefs.manyueFoldableMode.get()
        foldableTargetWidth = prefs.manyueFoldableTargetWidth.get()
    }
}
