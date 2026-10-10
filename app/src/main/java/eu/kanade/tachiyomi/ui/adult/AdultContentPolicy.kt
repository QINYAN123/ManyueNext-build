package eu.kanade.tachiyomi.ui.adult

import mihon.domain.content.model.ContentRatingOverride
import mihon.domain.extension.model.ContentWarning

object AdultContentPolicy {
    private val classifier = AdultContentClassifier()

    data class ContentPartition<T>(
        val regular: List<T>,
        val adult: List<T>,
    )

    fun isAdult(sourceId: Long, warning: ContentWarning, genres: List<String>?): Boolean =
        classifier.isAdult(sourceId, warning, genres)

    fun shouldShow(
        showAdult: Boolean,
        sourceId: Long,
        warning: ContentWarning,
        genres: List<String>?,
    ): Boolean = shouldShow(
        showAdult = showAdult,
        autoDetectionEnabled = true,
        override = ContentRatingOverride.AUTO,
        detectedAdult = classifier.isAdult(sourceId, warning, genres),
    )

    fun shouldShow(
        showAdult: Boolean,
        autoDetectionEnabled: Boolean,
        override: ContentRatingOverride,
        detectedAdult: Boolean,
    ): Boolean {
        if (showAdult) return true
        return when (override) {
            ContentRatingOverride.NORMAL -> true
            ContentRatingOverride.ADULT -> false
            ContentRatingOverride.AUTO -> !autoDetectionEnabled || !detectedAdult
        }
    }

    fun <T> filter(
        items: List<T>,
        showAdult: Boolean,
        warningFor: (Long) -> ContentWarning,
        sourceId: (T) -> Long,
        genres: (T) -> List<String>?,
    ): List<T> = if (showAdult) {
        items
    } else {
        items.filter { item ->
            val id = sourceId(item)
            shouldShow(false, id, warningFor(id), genres(item))
        }
    }

    fun <T> filter(
        items: List<T>,
        showAdult: Boolean,
        autoDetectionEnabled: Boolean,
        overrideFor: (T) -> ContentRatingOverride,
        warningFor: (Long) -> ContentWarning,
        sourceId: (T) -> Long,
        genres: (T) -> List<String>?,
    ): List<T> = items.filter { item ->
        val id = sourceId(item)
        shouldShow(
            showAdult = showAdult,
            autoDetectionEnabled = autoDetectionEnabled,
            override = overrideFor(item),
            detectedAdult = classifier.isAdult(id, warningFor(id), genres(item)),
        )
    }

    /** Splits items according to the same policy as the default hidden state. */
    fun <T> partition(
        items: List<T>,
        autoDetectionEnabled: Boolean,
        overrideFor: (T) -> ContentRatingOverride,
        warningFor: (Long) -> ContentWarning,
        sourceId: (T) -> Long,
        genres: (T) -> List<String>?,
    ): ContentPartition<T> {
        val regular = mutableListOf<T>()
        val adult = mutableListOf<T>()
        items.forEach { item ->
            val id = sourceId(item)
            val visibleByDefault = shouldShow(
                showAdult = false,
                autoDetectionEnabled = autoDetectionEnabled,
                override = overrideFor(item),
                detectedAdult = classifier.isAdult(id, warningFor(id), genres(item)),
            )
            if (visibleByDefault) regular += item else adult += item
        }
        return ContentPartition(regular = regular, adult = adult)
    }
}
