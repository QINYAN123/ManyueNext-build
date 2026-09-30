package eu.kanade.tachiyomi.ui.adult

import mihon.domain.extension.model.ContentWarning
import java.text.Normalizer
import java.util.Locale

class AdultContentClassifier {
    fun isAdult(sourceId: Long, warning: ContentWarning, genres: List<String>?): Boolean {
        if (sourceId in KNOWN_ADULT_SOURCES || warning == ContentWarning.NSFW) return true
        return genres.orEmpty().any { normalize(it) in ADULT_TAGS }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s_-]+"), "")

    companion object {
        val KNOWN_ADULT_SOURCES = setOf(5768337869316468367L, 6286738698187452081L)
        private val ADULT_TAGS = setOf("r18", "18+", "成人", "成年", "nsfw", "hentai", "色情")
    }
}
