package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManyueReadingDirectionTest {
    private val forward = ManyueAiSafetyPolicy.PrefetchDirection.FORWARD
    private val backward = ManyueAiSafetyPolicy.PrefetchDirection.BACKWARD

    @Test fun `repeated one-page glances cannot confirm a reversal in either direction`() {
        val tracker = ManyueReadingDirection()
        tracker.reset(10)
        listOf(11, 10, 10, 11, 10, 11).forEach { assertEquals(forward, tracker.selected(it)) }
        assertEquals(backward, tracker.selected(9))
        assertEquals(backward, tracker.selected(8))
        listOf(9, 8, 9, 9, 8).forEach { assertEquals(backward, tracker.selected(it)) }
        assertEquals(forward, tracker.selected(10))
    }

    @Test fun `jumps confirm direction immediately and reset forgets an old reverse frontier`() {
        val tracker = ManyueReadingDirection()
        tracker.reset(40)
        assertEquals(backward, tracker.selected(2))
        assertEquals(forward, tracker.selected(30))
        assertEquals(backward, tracker.selected(0))
        assertEquals(forward, tracker.reset(0))
        assertEquals(forward, tracker.selected(1))
        assertEquals(forward, tracker.selected(0))
        assertEquals(forward, tracker.reset())
        assertEquals(forward, tracker.selected(Int.MAX_VALUE))
        assertEquals(backward, tracker.selected(0))
    }
}
