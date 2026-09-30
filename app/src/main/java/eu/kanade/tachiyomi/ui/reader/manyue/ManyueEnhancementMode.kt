package eu.kanade.tachiyomi.ui.reader.manyue

/**
 * Enhancement modes for Manyue reader.
 * OFF: original fast path, zero overhead.
 */
enum class ManyueEnhancementMode(val value: Int) {
    OFF(0),
    CLASSIC(1),
    AI_2X(2),
    AI_2X_CLASSIC(3);

    companion object {
        fun fromInt(v: Int): ManyueEnhancementMode = entries.firstOrNull { it.value == v } ?: OFF
    }
}
