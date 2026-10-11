package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManyueMovingReadPolicyTest {
    @Test fun `short strips prepare first unseen page beyond visible frontier`() {
        ManyuePrefetchManager.reset()
        try {
            ManyuePrefetchManager.onPageChanged(903L, 10, setOf(903L to 10, 903L to 11, 903L to 12))
            assertFalse(ManyuePrefetchManager.isNextDisplayPage(903L, 11))
            assertTrue(ManyuePrefetchManager.isNextDisplayPage(903L, 13))
            ManyuePrefetchManager.updateVisiblePages(setOf(903L to 10, 903L to 11))
            assertTrue(ManyuePrefetchManager.isNextDisplayPage(903L, 12))
        } finally {
            ManyuePrefetchManager.reset()
        }
    }

    @Test fun `one speculative owner cannot be stolen or released by another holder`() {
        val slot = ManyueDisplayPreparationSlot()
        val a = Any()
        val b = Any()
        assertFalse(slot.acquire(a, eligible = false))
        assertTrue(slot.acquire(a, eligible = true))
        assertTrue(slot.acquire(a, eligible = true))
        assertFalse(slot.acquire(b, eligible = true))
        slot.release(b)
        assertTrue(slot.owns(a))
        slot.release(a)
        assertTrue(slot.acquire(b, eligible = true))
        slot.reset()
        assertFalse(slot.owns(b))
    }

    @Test fun `speculative permission follows direction chapter and selected page`() {
        ManyuePrefetchManager.reset()
        try {
            val owner = Any()
            ManyuePrefetchManager.onPageChanged(904L, 10)
            assertTrue(ManyuePrefetchManager.acquireDisplayPreparation(904L, 11, owner))
            assertFalse(ManyuePrefetchManager.isNextDisplayPage(904L, 12))
            assertFalse(ManyuePrefetchManager.isNextDisplayPage(905L, 11))
            ManyuePrefetchManager.onPageChanged(904L, 9)
            assertFalse(ManyuePrefetchManager.ownsDisplayPreparation(904L, 11, owner))
            assertTrue(ManyuePrefetchManager.isNextDisplayPage(904L, 8))
            ManyuePrefetchManager.releaseDisplayPreparation(owner)
            assertTrue(ManyuePrefetchManager.acquireDisplayPreparation(904L, 8, Any()))
            ManyuePrefetchManager.onPageChanged(905L, 0)
            assertFalse(ManyuePrefetchManager.isNextDisplayPage(904L, 8))
            assertTrue(ManyuePrefetchManager.isNextDisplayPage(905L, 1))
        } finally {
            ManyuePrefetchManager.reset()
        }
    }

}
