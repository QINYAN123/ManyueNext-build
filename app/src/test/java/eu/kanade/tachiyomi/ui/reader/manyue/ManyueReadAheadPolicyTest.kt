package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ManyueReadAheadPolicyTest {
    private var now = 0L
    private val policy = ManyueReadAheadPolicy { now }
    private fun select(page: Int, elapsedMs: Long = 500, chapter: Long = 10, generation: Long = 1) {
        now += elapsedMs * 1_000_000
        policy.selected(chapter, page, generation)
    }

    @Test fun `fast short strips gain lead while pause returns to visible priority`() {
        select(0); select(1); select(2)
        assertEquals(2, policy.preferredLead(10, 1, 720, 562))
        assertEquals(1, policy.distance(10, 3, 1))
        policy.completed(720, 562, 800)
        policy.completed(720, 562, 800)
        assertEquals(1, policy.preferredLead(10, 1, 720, 562))
        now += 1_500_000_000
        assertEquals(0, policy.preferredLead(10, 1, 720, 562))
    }

    @Test fun `reversal jump chapter and generation discard old speed`() {
        select(0); select(1); select(2)
        select(1)
        assertEquals(0, policy.preferredLead(10, 1, 720, 562))
        assertEquals(1, policy.distance(10, 0, 1))
        select(20)
        assertEquals(0, policy.preferredLead(10, 1, 720, 562))
        select(0, chapter = 11)
        assertNull(policy.distance(10, 20, 1))
        select(1, generation = 2, chapter = 11)
        assertNull(policy.distance(11, 1, 1))
        assertEquals(0, policy.preferredLead(11, 2, 720, 562))
    }

    @Test fun `repeated selection does not fabricate speed and lead stays bounded`() {
        select(0); select(0, 10); select(0, 10)
        assertEquals(0, policy.preferredLead(10, 1, 1200, 2160))
        select(1, 100); select(2, 100)
        assertEquals(5, policy.preferredLead(10, 1, 1200, 2160))
        assertEquals(0, policy.preferredLead(10, 1, 720, 1))
        policy.reset()
        assertNull(policy.distance(10, 2, 1))
    }
}
