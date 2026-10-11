package eu.kanade.tachiyomi.ui.reader.manyue

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences

/**
 * Process-wide snapshot of the currently selected Manyue enhancement settings.
 * Written by the reader settings UI and by [syncFrom] on page load; read by the pipeline.
 * Defaults keep the original fast path (mode=OFF).
 */
object ManyueRuntimeState {
    @Volatile var modeInt: Int = 0 // ManyueEnhancementMode.OFF
        private set

    @Volatile var classicStrength: Int = 25
        private set

    @Volatile var aiModel: ManyueAiModel = ManyueAiModel.DEFAULT
        private set

    @Volatile var aiScalePercent: Int = 200
        private set

    @Volatile var aiDetailStrength: Int = 60
        private set

    @Volatile var anime4kOverlay: Boolean = false
        private set

    @Volatile var displayWidthPx: Int = 0
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
        val canonicalMode = ManyueEnhancementMode.fromInt(newMode).value
        if (modeInt == canonicalMode) return
        modeInt = canonicalMode
        generation++
        ManyuePrefetchManager.reset()
    }

    @Synchronized
    fun updateClassicStrength(newStrength: Int) {
        val safeStrength = newStrength.coerceIn(0, 100)
        if (classicStrength == safeStrength) return
        classicStrength = safeStrength
        if (ManyueEnhancementMode.fromInt(modeInt).usesClassic(aiModel)) {
            generation++
            ManyuePrefetchManager.reset()
        }
    }

    /** A window/fold width change invalidates automatic targets once, never on every scroll. */
    @Synchronized
    fun updateDisplayWidth(width: Int): Boolean {
        if (width <= 0 || displayWidthPx == width) return false
        displayWidthPx = width
        if (modeInt != ManyueEnhancementMode.AUTO.value) return false
        generation++
        ManyuePrefetchManager.reset()
        return true
    }

    @Synchronized
    fun updateAiModel(newModel: ManyueAiModel) {
        if (aiModel == newModel) return
        aiModel = newModel
        aiScalePercent = newModel.outputScalePercent(aiScalePercent)
        generation++
        ManyuePrefetchManager.reset()
    }

    @Synchronized
    fun updateAiScale(percent: Int) {
        val safePercent = aiModel.outputScalePercent(percent)
        if (aiScalePercent == safePercent) return
        aiScalePercent = safePercent
        generation++
        ManyuePrefetchManager.reset()
    }

    @Synchronized
    fun updateAiDetailStrength(strength: Int) {
        val safe = strength.coerceIn(0, 100)
        if (aiDetailStrength == safe) return
        aiDetailStrength = safe
        if (aiModel.continuousScale) {
            generation++
            ManyuePrefetchManager.reset()
        }
    }

    @Synchronized
    fun updateAnime4kOverlay(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        if (!anime4kOverlay) return
        anime4kOverlay = false
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
        ManyuePerformanceDiagnostics.setEnabled(prefs.manyuePerformanceDiagnostics.get())
        val savedModelId = prefs.manyueAiModel.get()
        val canonicalModelId = ManyueAiModel.canonicalPreferenceId(savedModelId)
        if (canonicalModelId != savedModelId && canonicalModelId != null) {
            prefs.manyueAiModel.set(canonicalModelId)
        }
        // Keep this historical marker so installs that already ran the Lite migration remain compatible.
        if (!prefs.manyueLiteModelMigrationDone.get()) {
            prefs.manyueLiteModelMigrationDone.set(true)
        }
        val savedMode = prefs.manyueEnhancementMode.get()
        val canonicalMode = ManyueEnhancementMode.fromInt(savedMode).value
        if (savedMode != canonicalMode) prefs.manyueEnhancementMode.set(canonicalMode)
        updateMode(canonicalMode)
        updateClassicStrength(prefs.manyueClassicStrength.get())
        updateAiModel(ManyueAiModel.fromId(canonicalModelId))
        if (prefs.manyueAnime4kOverlay.get()) prefs.manyueAnime4kOverlay.set(false)
        updateAnime4kOverlay(false)
        updateAiScale(prefs.manyueAiScalePercent.get())
        updateAiDetailStrength(prefs.manyueAiDetailStrength.get())
        foldableMode = prefs.manyueFoldableMode.get()
        foldableTargetWidth = prefs.manyueFoldableTargetWidth.get()
    }
}
