package eu.kanade.tachiyomi.ui.adult

import mihon.domain.extension.model.ContentWarning
import mihon.domain.content.model.ContentRatingOverride
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdultContentPolicyTest {
    private val classifier = AdultContentClassifier()

    @Test
    fun `new session starts hidden and is not persisted`() {
        val first = AdultContentSessionState().also { it.setShown(true) }
        assertTrue(first.isShown.value)
        assertFalse(AdultContentSessionState().isShown.value)
    }

    @Test
    fun `known adult sources and normalized tags are hidden`() {
        assertTrue(classifier.isAdult(5768337869316468367L, ContentWarning.SAFE, null))
        assertTrue(classifier.isAdult(6286738698187452081L, ContentWarning.SAFE, null))
        listOf("R-18", "r 18", "18＋", "成人", "NSFW", "Hentai").forEach {
            assertTrue(classifier.isAdult(1L, ContentWarning.SAFE, listOf(it)), it)
        }
    }

    @Test
    fun `mixed source only hides tagged titles and unknown stays visible`() {
        assertFalse(classifier.isAdult(1L, ContentWarning.MIXED, listOf("Action")))
        assertFalse(classifier.isAdult(1L, ContentWarning.SAFE, null))
        assertTrue(classifier.isAdult(1L, ContentWarning.NSFW, null))
    }

    @Test
    fun `shown mode preserves every item`() {
        val items = listOf("safe", "adult")
        assertEquals(
            items,
            AdultContentPolicy.filter(
                items = items,
                showAdult = true,
                warningFor = { ContentWarning.SAFE },
                sourceId = { if (it == "adult") 5768337869316468367L else 1L },
                genres = { null },
            ),
        )
    }

    @Test
    fun `manual normal wins over adult detection`() {
        assertTrue(
            AdultContentPolicy.shouldShow(
                showAdult = false,
                autoDetectionEnabled = true,
                override = ContentRatingOverride.NORMAL,
                detectedAdult = true,
            ),
        )
    }

    @Test
    fun `manual adult stays hidden until the session reveals it`() {
        assertFalse(
            AdultContentPolicy.shouldShow(
                showAdult = false,
                autoDetectionEnabled = false,
                override = ContentRatingOverride.ADULT,
                detectedAdult = false,
            ),
        )
        assertTrue(
            AdultContentPolicy.shouldShow(
                showAdult = true,
                autoDetectionEnabled = false,
                override = ContentRatingOverride.ADULT,
                detectedAdult = false,
            ),
        )
    }

    @Test
    fun `auto content is shown when automatic detection is disabled`() {
        assertTrue(
            AdultContentPolicy.shouldShow(
                showAdult = false,
                autoDetectionEnabled = false,
                override = ContentRatingOverride.AUTO,
                detectedAdult = true,
            ),
        )
    }
}
