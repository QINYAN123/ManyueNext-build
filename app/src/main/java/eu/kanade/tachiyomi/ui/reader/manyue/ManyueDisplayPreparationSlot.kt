package eu.kanade.tachiyomi.ui.reader.manyue

/** One speculative decoded holder; visible holders never need this slot. Owned by the manager. */
internal class ManyueDisplayPreparationSlot {
    private var owner: Any? = null

    fun acquire(candidate: Any, eligible: Boolean): Boolean {
        if (!eligible || (owner != null && owner !== candidate)) return false
        owner = candidate
        return true
    }

    fun owns(candidate: Any): Boolean = owner === candidate

    fun release(candidate: Any) {
        if (owner === candidate) owner = null
    }

    fun reset() {
        owner = null
    }
}
