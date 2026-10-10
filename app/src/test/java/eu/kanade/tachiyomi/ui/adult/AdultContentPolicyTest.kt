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

    @Test
    fun `partition keeps normal overrides in regular and detected adult items in adult section`() {
        val items = listOf(
            TestItem("normal", 1L, ContentRatingOverride.AUTO, listOf("Action")),
            TestItem("tagged", 1L, ContentRatingOverride.AUTO, listOf("R-18")),
            TestItem("manual normal", 1L, ContentRatingOverride.NORMAL, listOf("Hentai")),
            TestItem("manual adult", 1L, ContentRatingOverride.ADULT, listOf("Action")),
        )

        val partition = AdultContentPolicy.partition(
            items = items,
            autoDetectionEnabled = true,
            overrideFor = { it.override },
            warningFor = { ContentWarning.SAFE },
            sourceId = { it.sourceId },
            genres = { it.genres },
        )

        assertEquals(listOf("normal", "manual normal"), partition.regular.map { it.name })
        assertEquals(listOf("tagged", "manual adult"), partition.adult.map { it.name })
    }

    @Test
    fun `partition follows auto detection setting and exposes hidden only state`() {
        val items = listOf(
            TestItem("detected", 1L, ContentRatingOverride.AUTO, listOf("NSFW")),
            TestItem("manual adult", 1L, ContentRatingOverride.ADULT, listOf("Action")),
        )

        val hidden = AdultContentPolicy.partition(
            items = items,
            autoDetectionEnabled = true,
            overrideFor = { it.override },
            warningFor = { ContentWarning.SAFE },
            sourceId = { it.sourceId },
            genres = { it.genres },
        )
        assertTrue(hidden.regular.isEmpty())
        assertEquals(items, hidden.adult)

        val autoDetectionOff = AdultContentPolicy.partition(
            items = items,
            autoDetectionEnabled = false,
            overrideFor = { it.override },
            warningFor = { ContentWarning.SAFE },
            sourceId = { it.sourceId },
            genres = { it.genres },
        )
        assertEquals(listOf("detected"), autoDetectionOff.regular.map { it.name })
        assertEquals(listOf("manual adult"), autoDetectionOff.adult.map { it.name })
    }

    @Test
    fun `resume visibility follows the current session gate`() {
        val detectedAdult = true
        assertFalse(
            AdultContentPolicy.shouldShow(
                showAdult = false,
                autoDetectionEnabled = true,
                override = ContentRatingOverride.AUTO,
                detectedAdult = detectedAdult,
            ),
        )
        assertTrue(
            AdultContentPolicy.shouldShow(
                showAdult = true,
                autoDetectionEnabled = true,
                override = ContentRatingOverride.AUTO,
                detectedAdult = detectedAdult,
            ),
        )
    }

    private data class TestItem(
        val name: String,
        val sourceId: Long,
        val override: ContentRatingOverride,
        val genres: List<String>,
    )
}
