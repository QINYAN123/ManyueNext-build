package eu.kanade.tachiyomi.ui.reader.manyue

/**
 * Enhancement modes for Manyue reader. CLASSIC and AI_2X_CLASSIC remain as legacy names only;
 * old stored values are canonicalized to OFF and AI_2X by [fromInt].
 */
enum class ManyueEnhancementMode(val value: Int) {
    OFF(0),
    CLASSIC(1),
    AI_2X(2),
    AI_2X_CLASSIC(3),
    AUTO(4),
    ;

    fun usesAi(): Boolean = this == AI_2X || this == AI_2X_CLASSIC || this == AUTO

    /** Classic processing is retired; keeping the legacy enum values preserves source compatibility. */
    @Suppress("UNUSED_PARAMETER")
    fun usesClassic(model: ManyueAiModel? = null): Boolean = false

    companion object {
        fun fromInt(v: Int): ManyueEnhancementMode = when (v) {
            2, 3 -> AI_2X
            4 -> AUTO
            else -> OFF
        }
    }
}
