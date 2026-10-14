package eu.kanade.tachiyomi.ui.reader.manyue

/** Experimental job-boundary routing, not a claim of measured native2 energy savings. */
internal class ManyueHybridPolicy(private val nanoTime: () -> Long = System::nanoTime) {
    private var generation: Long? = null
    private var selected = ManyueAiModel.QUALITY_REAL_ESRGAN
    private var enteredNativeNs = 0L
    private var lastPressureNs = 0L

    @Synchronized
    fun choose(generation: Long, thermalStatus: Int, visibleDeadlineMissed: Boolean): ManyueAiModel {
        val now = nanoTime()
        if (this.generation != generation) {
            this.generation = generation
            selected = ManyueAiModel.QUALITY_REAL_ESRGAN
        }
        // Android LIGHT is a system thermal signal, not an invented CPU/memory percentage.
        val pressure = thermalStatus >= 1 || visibleDeadlineMissed
        if (pressure) {
            lastPressureNs = now
            if (selected != ManyueAiModel.QUALITY_NATIVE2) {
                selected = ManyueAiModel.QUALITY_NATIVE2
                enteredNativeNs = now
            }
        } else if (selected == ManyueAiModel.QUALITY_NATIVE2 &&
            now - enteredNativeNs >= MIN_NATIVE_HOLD_NS && now - lastPressureNs >= RECOVERY_NS
        ) {
            selected = ManyueAiModel.QUALITY_REAL_ESRGAN
        }
        return selected
    }

    companion object {
        const val MIN_NATIVE_HOLD_NS = 20_000_000_000L
        const val RECOVERY_NS = 30_000_000_000L
        const val VISIBLE_DEADLINE_MS = 2_000L
    }
}
