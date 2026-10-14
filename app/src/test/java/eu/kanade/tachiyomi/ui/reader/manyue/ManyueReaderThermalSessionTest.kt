package eu.kanade.tachiyomi.ui.reader.manyue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManyueReaderThermalSessionTest {
    private class Fixture {
        var acquired = 0
        var released = 0
        var removed = 0
        val statuses = mutableListOf<Int>()
        val callbacks = mutableListOf<(Int) -> Unit>()
        val session = ManyueReaderThermalSession({ acquired++ }, { released++ }, { statuses += it })
        fun start() = session.start({ 1 }) { callback ->
            callbacks += callback
            val remove: () -> Unit = { removed++ }
            remove
        }
    }

    @Test fun `attach reads current status and only registers once`() {
        val f = Fixture()
        f.start()
        f.start()
        f.callbacks.single()(3)
        assertEquals(1, f.acquired)
        assertEquals(listOf(1, 3), f.statuses)
    }

    @Test fun `detach drops already queued thermal callbacks`() {
        val f = Fixture()
        f.start()
        f.session.stop()
        f.callbacks.single()(3)
        assertEquals(listOf(1), f.statuses)
        assertEquals(1, f.removed)
        assertEquals(1, f.released)
    }

    @Test fun `resume ignores callbacks from its previous subscription`() {
        val f = Fixture()
        f.start()
        f.session.stop()
        f.start()
        f.callbacks[0](3)
        f.callbacks[1](2)
        assertEquals(listOf(1, 1, 2), f.statuses)
    }

    @Test fun `unsupported subscription still uses a valid initial status`() {
        val f = Fixture()
        f.session.start({ 2 }) { throw UnsupportedOperationException() }
        assertEquals(listOf(2), f.statuses)
        f.session.stop()
        assertEquals(1, f.released)
    }

    @Test fun `failed removal cannot retain a reader gate or accept callbacks`() {
        val f = Fixture()
        f.session.start({ 1 }) { callback ->
            f.callbacks += callback
            val remove: () -> Unit = { throw IllegalStateException() }
            remove
        }
        f.session.stop()
        f.callbacks.single()(3)
        assertEquals(1, f.released)
        assertEquals(listOf(1), f.statuses)
    }

    @Test fun `stop and destroy release subscription exactly once`() {
        val f = Fixture()
        f.session.stop()
        f.start()
        f.session.stop()
        f.session.stop()
        assertEquals(1, f.removed)
        assertEquals(1, f.released)
    }
}
