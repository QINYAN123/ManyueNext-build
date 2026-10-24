package eu.kanade.tachiyomi.ui.reader.manyue

/** Keep AUTO quality on the same continuous GPU bridge as explicit 2x, retaining Pager fallback. */
internal object ManyueReaderBackendPolicy {
    fun requiresCanvas(
        continuous: Boolean,
        model: ManyueAiModel,
        modeInt: Int,
        gpuFilterEnabled: Boolean,
        sdkInt: Int,
    ): Boolean {
        val mode = ManyueEnhancementMode.fromInt(modeInt)
        val qualityContinuous = continuous && model.usesQualityRuntime && mode.usesAi()
        return (mode != ManyueEnhancementMode.OFF && !qualityContinuous) ||
            (!continuous && sdkInt >= 33 && gpuFilterEnabled)
    }
}
