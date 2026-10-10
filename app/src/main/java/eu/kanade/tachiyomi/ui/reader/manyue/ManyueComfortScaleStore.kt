package eu.kanade.tachiyomi.ui.reader.manyue

import tachiyomi.core.common.preference.Preference
import kotlin.math.roundToInt

/** Per-book, per-window comfort shrink. No chapter progress or magnified crop is stored. */
class ManyueComfortScaleStore(private val preference: Preference<Set<String>>) {
    fun get(mangaId: Long, innerScreen: Boolean): Float? {
        if (mangaId <= 0) return null
        val prefix = prefix(mangaId, innerScreen)
        return preference.get().asSequence()
            .filter { it.startsWith(prefix) }
            .mapNotNull { it.removePrefix(prefix).toIntOrNull()?.takeIf { value -> value in 500..999 } }
            .firstOrNull()?.div(1000f)
    }

    fun save(mangaId: Long, innerScreen: Boolean, scale: Float?) {
        if (mangaId <= 0) return
        val prefix = prefix(mangaId, innerScreen)
        val old = preference.get()
        val updated = old.filterNot { it.startsWith(prefix) }.toMutableSet()
        scale?.takeIf { it.isFinite() && it >= 0.5f && it < 1f }?.let {
            updated += prefix + (it * 1000).roundToInt().coerceIn(500, 999)
        }
        if (updated != old) preference.set(updated)
    }

    private fun prefix(mangaId: Long, innerScreen: Boolean) = "$mangaId:${if (innerScreen) "i" else "o"}:"
}
