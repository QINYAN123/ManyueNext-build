package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebGpuDecodeDemandTest {
    private val windows = mutableListOf<String>()
    private val priorities = mutableListOf<String>()
    private val demand = WebGpuDecodeDemand<String>(windows::add, priorities::add)
    private fun frame(width: Int = 2156, height: Int = 2344, below: Int = 2, pages: List<String> = listOf("head", "bottom")) =
        demand.update("head", width, height, 0, below, pages)

    @Test fun `outer screen expansion loads beyond old window without a page change`() {
        frame()
        frame(1060, 2376, 5, listOf("head", "bottom", "new-bottom"))
        assertEquals(listOf("head", "head"), windows)
        assertEquals("head", priorities[priorities.size - 3])
        assertEquals("new-bottom", priorities.last())
    }

    @Test fun `decode revealing short pages refreshes reach at the same screen size`() {
        frame()
        frame(below = 5)
        assertEquals(2, windows.size)
    }

    @Test fun `bottom entering view gets priority before distant preloads`() {
        frame()
        priorities.clear()
        frame(pages = listOf("head", "bottom", "entering"))
        assertEquals(1, windows.size)
        assertEquals(listOf("head", "bottom", "entering"), priorities)
    }

    @Test fun `unchanged frames do not repeatedly rebuild the original queue`() {
        repeat(120) { frame() }
        assertEquals(listOf("head"), windows)
        assertEquals(listOf("head", "bottom"), priorities)
    }

    @Test fun `reader rebuilding at the same location admits original pages again`() {
        frame()
        demand.reset()
        frame()
        assertEquals(2, windows.size)
    }

    @Test fun `not yet measured surface does not claim a decode window`() {
        frame(width = 0)
        frame(height = 0)
        demand.update(null, 1060, 2376, 0, 5, listOf("bottom"))
        assertEquals(emptyList<String>(), windows)
        frame()
        assertEquals(listOf("head"), windows)
    }

    @Test fun `three visible strips decode bottom first while preserving middle and top demand`() {
        // Model the production worker's LIFO take, rather than just callback ordering.
        frame(pages = listOf("top", "middle", "bottom"))
        val queue = ArrayDeque(priorities)
        assertEquals("bottom", queue.removeLast())
        assertEquals("middle", queue.removeLast())
        assertEquals("top", queue.removeLast())
    }
}
