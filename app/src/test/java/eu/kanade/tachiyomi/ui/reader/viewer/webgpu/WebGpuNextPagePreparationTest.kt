package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WebGpuNextPagePreparationTest {
    private var wanted = 4
    private var neighborReads = 0
    private val preparations = mutableListOf<Pair<Int, Boolean>>()
    private val window = WebGpuNextPagePreparation<Int>(
        neighbors = { neighborReads++; listOf(it - 1, it + 1) },
        eligible = { it == wanted },
        prepare = { page, enabled -> preparations += page to enabled },
    )

    @Test fun `short strips prepare beyond the entire visible frontier`() {
        window.update(setOf(1, 2, 3), 1)
        assertEquals(listOf(4 to true), preparations)
    }

    @Test fun `entering prewarmed page transfers without cancelling its upload`() {
        window.update(setOf(1, 2, 3), 1)
        wanted = 5
        window.update(setOf(2, 3, 4), 2)
        assertEquals(listOf(4 to true, 5 to true), preparations)
    }

    @Test fun `direction reversal retires future preparation and chooses previous neighbor`() {
        window.update(setOf(1, 2, 3), 1)
        wanted = 0
        window.update(setOf(1, 2, 3), 2)
        assertEquals(listOf(4 to true, 4 to false, 0 to true), preparations)
    }

    @Test fun `stable frames retry newly decoded input without rebuilding neighbor window`() {
        repeat(120) { window.update(setOf(1, 2, 3), 1) }
        assertEquals(3, neighborReads)
        assertEquals(120, preparations.count { it == (4 to true) })
    }

    @Test fun `background with no visible exposure releases next preparation`() {
        window.update(setOf(1, 2, 3), 1)
        window.update(emptySet(), 1)
        assertEquals(listOf(4 to true, 4 to false), preparations)
    }

    @Test fun `settings or reader reset reacquires the same next page`() {
        window.update(setOf(1, 2, 3), 1)
        window.reset()
        window.update(setOf(1, 2, 3), 1)
        assertEquals(listOf(4 to true, 4 to false, 4 to true), preparations)
    }
}
