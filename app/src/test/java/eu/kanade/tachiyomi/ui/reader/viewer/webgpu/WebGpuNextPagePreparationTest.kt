package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebGpuNextPagePreparationTest {
    private data class Call(val page: Int, val visible: Boolean, val allowPreparation: Boolean)

    private var wanted = 4
    private var neighborReads = 0
    private val preparations = mutableListOf<Call>()
    private val window = WebGpuNextPagePreparation<Int>(
        neighbors = { neighborReads++; listOf(it - 1, it + 1) },
        eligible = { it == wanted },
        prepare = { page, visible, allow -> preparations += Call(page, visible, allow) },
    )

    @Test fun `short strips prepare beyond the entire visible frontier when allowed`() {
        window.update(setOf(1, 2, 3), 1, allowPreparation = true)
        assertEquals(listOf(Call(4, visible = false, allowPreparation = true)), preparations)
    }

    @Test fun `new visible page without submitted AI blocks admission until all visible pages are ready`() {
        val visible = setOf(1, 2, 3)
        var submitted = setOf(1, 2)
        fun reconcile() {
            window.update(visible, 1, allowPreparation = visible.all { it in submitted })
        }

        reconcile()
        reconcile()
        assertEquals(listOf(Call(4, visible = false, allowPreparation = false)), preparations)

        submitted = visible
        reconcile()
        assertEquals(
            listOf(
                Call(4, visible = false, allowPreparation = false),
                Call(4, visible = false, allowPreparation = true),
            ),
            preparations,
        )
    }

    @Test fun `entering prewarmed page keeps real visibility while transferring to next page`() {
        window.update(setOf(1, 2, 3), 1, allowPreparation = true)
        wanted = 5
        window.update(setOf(2, 3, 4), 2, allowPreparation = false)

        assertEquals(
            listOf(
                Call(4, visible = false, allowPreparation = true),
                Call(4, visible = true, allowPreparation = false),
                Call(5, visible = false, allowPreparation = false),
            ),
            preparations,
        )
        assertTrue(preparations.any { it.page == 4 && it.visible })
        assertFalse(preparations.any { it.page == 4 && !it.visible && !it.allowPreparation })
    }

    @Test fun `direction reversal retires future preparation and chooses previous neighbor`() {
        window.update(setOf(1, 2, 3), 1, allowPreparation = true)
        wanted = 0
        window.update(setOf(1, 2, 3), 2, allowPreparation = true)
        assertEquals(
            listOf(
                Call(4, visible = false, allowPreparation = true),
                Call(4, visible = false, allowPreparation = false),
                Call(0, visible = false, allowPreparation = true),
            ),
            preparations,
        )
    }

    @Test fun `stable allowed frames retry readiness without rebuilding neighbor window`() {
        repeat(120) { window.update(setOf(1, 2, 3), 1, allowPreparation = true) }
        assertEquals(3, neighborReads)
        assertEquals(120, preparations.count { it == Call(4, visible = false, allowPreparation = true) })
    }

    @Test fun `stable blocked frames do not repeatedly cancel offscreen work`() {
        repeat(120) { window.update(setOf(1, 2, 3), 1, allowPreparation = false) }
        assertEquals(3, neighborReads)
        assertEquals(listOf(Call(4, visible = false, allowPreparation = false)), preparations)
    }

    @Test fun `withdrawing admission stops once and later readiness resumes preparation`() {
        val visible = setOf(1, 2, 3)
        window.update(visible, 1, allowPreparation = true)
        window.update(visible, 1, allowPreparation = false)
        window.update(visible, 1, allowPreparation = false)
        assertEquals(
            listOf(
                Call(4, visible = false, allowPreparation = true),
                Call(4, visible = false, allowPreparation = false),
            ),
            preparations,
        )

        window.update(visible, 1, allowPreparation = true)
        assertEquals(
            listOf(
                Call(4, visible = false, allowPreparation = true),
                Call(4, visible = false, allowPreparation = false),
                Call(4, visible = false, allowPreparation = true),
            ),
            preparations,
        )
    }

    @Test fun `background with no visible exposure releases next preparation`() {
        window.update(setOf(1, 2, 3), 1, allowPreparation = true)
        window.update(emptySet(), 1, allowPreparation = false)
        assertEquals(
            listOf(
                Call(4, visible = false, allowPreparation = true),
                Call(4, visible = false, allowPreparation = false),
            ),
            preparations,
        )
    }

    @Test fun `reset uses current visibility and protects an ahead page that entered the viewport`() {
        window.update(setOf(1, 2, 3), 1, allowPreparation = true)
        window.reset(visible = setOf(4))
        assertEquals(
            listOf(
                Call(4, visible = false, allowPreparation = true),
                Call(4, visible = true, allowPreparation = false),
            ),
            preparations,
        )
    }

    @Test fun `reset and new configuration generation release stale preparation before reacquiring`() {
        window.update(setOf(1, 2, 3), 1, allowPreparation = true)
        window.update(setOf(1, 2, 3), 2, allowPreparation = false)
        window.update(setOf(1, 2, 3), 2, allowPreparation = false)
        window.reset()
        window.update(setOf(1, 2, 3), 3, allowPreparation = true)
        assertEquals(
            listOf(
                Call(4, visible = false, allowPreparation = true),
                Call(4, visible = false, allowPreparation = false),
                Call(4, visible = false, allowPreparation = false),
                Call(4, visible = false, allowPreparation = true),
            ),
            preparations,
        )
    }

    private data class ChapterPage(val chapter: Int, val index: Int)

    @Test fun `chapter boundary excludes cross chapter neighbor and new chapter revision selects local page`() {
        val end = ChapterPage(chapter = 1, index = 9)
        val nextChapter = ChapterPage(chapter = 2, index = 0)
        val nextPage = ChapterPage(chapter = 2, index = 1)
        var activeChapter = 1
        val pages = mapOf(end to nextChapter, nextChapter to nextPage)
        val calls = mutableListOf<Pair<ChapterPage, Boolean>>()
        val chapterWindow = WebGpuNextPagePreparation<ChapterPage>(
            neighbors = { listOfNotNull(pages[it]) },
            eligible = { it.chapter == activeChapter },
            prepare = { page, visible, allowed ->
                assertFalse(visible)
                calls += page to allowed
            },
        )

        chapterWindow.update(setOf(end), revision = 1 to 1, allowPreparation = true)
        assertTrue(calls.isEmpty())

        activeChapter = 2
        chapterWindow.update(setOf(nextChapter), revision = 2 to 2, allowPreparation = true)
        assertEquals(listOf(nextPage to true), calls)
    }
}
