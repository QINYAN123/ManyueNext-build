package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.IntSize
import ca.mpreg.webgpuviewer.waitForCleanUp
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContinuousDragStartTest {
    private fun change(x: Float, y: Float = 0f, previous: Offset = Offset.Zero, pressed: Boolean = true, id: Long = 1) =
        PointerInputChange(
            id = PointerId(id), uptimeMillis = 20, position = Offset(x, y), pressed = pressed,
            previousUptimeMillis = 10, previousPosition = previous, previousPressed = true,
            isInitiallyConsumed = false,
        )

    private class Events(private val events: List<PointerEvent>) : AwaitPointerEventScope {
        override val density = 1f
        override val fontScale = 1f
        override val size = IntSize(100, 100)
        override var currentEvent = PointerEvent(emptyList())
        override val viewConfiguration = object : ViewConfiguration {
            override val longPressTimeoutMillis = 500L
            override val doubleTapTimeoutMillis = 300L
            override val doubleTapMinTimeMillis = 40L
            override val touchSlop = 10f
        }
        var reads = 0
        override suspend fun awaitPointerEvent(pass: PointerEventPass): PointerEvent {
            check(reads < events.size) { "Recognizer waited for another event after crossing slop" }
            return events[reads++].also { currentEvent = it }
        }
        fun <T> run(block: suspend AwaitPointerEventScope.() -> T): T {
            var result: Result<T>? = null
            block.startCoroutine(this, object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(value: Result<T>) { result = value }
            })
            return requireNotNull(result) { "Deterministic event coroutine did not complete" }.getOrThrow()
        }
    }

    @Test fun `crossing event and only excess movement arrive before another await`() {
        val first = PointerEvent(listOf(change(8f)))
        val crossing = PointerEvent(listOf(change(25f, previous = Offset(8f, 0f))))
        val events = Events(listOf(first, crossing))
        var delivered: PointerEvent? = null
        var delta = Offset.Zero
        val tap = events.run { waitForCleanUp(PointerId(1), 300, 10f) { event, pan ->
            delivered = event; delta = pan
        } }
        assertNull(tap)
        assertSame(crossing, delivered)
        assertEquals(15f, delta.x, 0.0001f)
        assertEquals(0f, delta.y, 0.0001f)
        assertEquals(2, events.reads)
    }

    @Test fun `diagonal crossing retains direction and subtracts radial touch slop`() {
        val crossing = PointerEvent(listOf(change(16f, 12f)))
        var delta = Offset.Zero
        Events(listOf(crossing)).run { waitForCleanUp(PointerId(1), 300, 10f) { _, pan -> delta = pan } }
        assertEquals(Offset(8f, 6f), delta)
    }

    @Test fun `movement below slop ending in up remains a tap`() {
        val up = PointerEvent(listOf(change(5f, pressed = false, previous = Offset(5f, 0f))))
        var drag = false
        val result = Events(listOf(PointerEvent(listOf(change(5f))), up))
            .run { waitForCleanUp(PointerId(1), 300, 10f) { _, _ -> drag = true } }
        assertSame(up, result)
        assertTrue(!drag)
    }

    @Test fun `upward crossing preserves negative direction`() {
        var delta = Offset.Zero
        Events(listOf(PointerEvent(listOf(change(0f, -25f)))))
            .run { waitForCleanUp(PointerId(1), 300, 10f) { _, pan -> delta = pan } }
        assertEquals(0f, delta.x, 0.0001f)
        assertEquals(-15f, delta.y, 0.0001f)
    }

    @Test fun `consumed crossing is cancelled without delivering movement`() {
        val consumed = change(25f).also { it.consume() }
        var drag = false
        val result = Events(listOf(PointerEvent(listOf(consumed))))
            .run { waitForCleanUp(PointerId(1), 300, 10f) { _, _ -> drag = true } }
        assertNull(result)
        assertTrue(!drag)
    }

    @Test fun `missing tracked pointer does not replay a different finger`() {
        var drag = false
        val result = Events(listOf(PointerEvent(listOf(change(25f, id = 2)))))
            .run { waitForCleanUp(PointerId(1), 300, 10f) { _, _ -> drag = true } }
        assertNull(result)
        assertTrue(!drag)
    }

    @Test fun `second finger starts transform immediately without injecting accumulated pan`() {
        val event = PointerEvent(listOf(change(0f), change(30f, id = 2)))
        var delivered: PointerEvent? = null
        var delta = Offset(99f, 99f)
        val result = Events(listOf(event)).run { waitForCleanUp(PointerId(1), 300, 10f) { e, pan -> delivered = e; delta = pan } }
        assertNull(result)
        assertSame(event, delivered)
        assertEquals(Offset.Zero, delta)
    }
}
