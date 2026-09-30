package eu.kanade.tachiyomi.ui.library

/** Small, deterministic helper for the shelf's derived genre/tag filter. */
object LibraryTagFilter {
    fun available(genres: List<List<String>>): List<String> {
        return genres
            .asSequence()
            .flatten()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
            .toList()
    }

    fun matches(genres: List<String>, selected: String): Boolean {
        val normalized = selected.trim()
        return normalized.isNotEmpty() && genres.any { it.trim().equals(normalized, ignoreCase = true) }
    }
}
