package eu.kanade.tachiyomi.ui.reader.manyue

import android.app.Activity
import android.app.Application
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], manifest = Config.NONE)
class ManyueGpuDisplayControllerTest {
    private class Renderer : ManyueGpuDisplayController.Backend {
        val calls = mutableListOf<Triple<Int, Int, Int>>()
        var clears = 0
        var fail = false
        override fun apply(view: View, width: Int, height: Int, strength: Int) {
            calls += Triple(width, height, strength)
            if (fail) throw IllegalArgumentException("Shader failure")
        }
        override fun clear(view: View) {
            clears++
        }
    }

    private fun withViewport(block: (FrameLayout) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val viewport = FrameLayout(activity.get())
            activity.get().setContentView(viewport)
            viewport.layout(0, 0, 690, 1421)
            assertTrue(viewport.isAttachedToWindow)
            block(viewport)
        } finally {
            activity.pause().stop().destroy()
        }
    }

    @Test fun strengthAndFoldSizeChangesReuseRendererWithoutInvalidatingAi() = withViewport { viewport ->
        val renderer = Renderer()
        var factories = 0
        val generation = ManyueRuntimeState.generation
        val filter = ManyueGpuDisplayController(viewport, 34, { true }, {
            factories++
            renderer
        })
        try {
            filter.configure(true, 25)
            filter.configure(true, 25)
            viewport.layout(0, 0, 690, 1421)
            assertEquals(1, renderer.calls.size)
            filter.configure(true, 65)
            viewport.layout(0, 0, 2344, 2156)
            assertEquals(listOf(Triple(690, 1421, 25), Triple(690, 1421, 65), Triple(2344, 2156, 65)), renderer.calls)
            assertEquals(1, factories)
            assertEquals(generation, ManyueRuntimeState.generation)
            assertEquals(ManyueGpuDisplayStatus.State.ACTIVE, filter.status.value.state)
            assertTrue(filter.status.value.label!!.contains("非 AI"))
        } finally {
            filter.close()
        }
    }

    @Test fun zeroAndOffClearTheEffectAndNeverCreateAnIdleRenderer() = withViewport { viewport ->
        val renderer = Renderer()
        var factories = 0
        val filter = ManyueGpuDisplayController(viewport, 34, { true }, {
            factories++
            renderer
        })
        try {
            filter.configure(false, 25)
            filter.configure(true, -7)
            assertEquals(0, factories)
            assertEquals(ManyueGpuDisplayStatus.State.ZERO_STRENGTH, filter.status.value.state)
            filter.configure(true, 200)
            assertEquals(100, renderer.calls.single().third)
            filter.configure(false, 25)
            assertEquals(1, renderer.clears)
            assertNull(filter.status.value.label)
        } finally {
            filter.close()
        }
    }

    @Test fun unsupportedSdkAndSoftwareViewsCannotReportActive() = withViewport { viewport ->
        val factory = { error("Unsupported display must never create a shader") }
        val old = ManyueGpuDisplayController(viewport, 32, { true }, factory)
        val software = ManyueGpuDisplayController(viewport, 34, { false }, factory)
        try {
            old.configure(true, 25)
            software.configure(true, 25)
            assertEquals(ManyueGpuDisplayStatus.State.UNSUPPORTED, old.status.value.state)
            assertEquals(ManyueGpuDisplayStatus.State.UNSUPPORTED, software.status.value.state)
        } finally {
            old.close()
            software.close()
        }
    }

    @Test fun failedShaderIsClearedAndDoesNotRetryEveryLayout() = withViewport { viewport ->
        val renderer = Renderer().apply { fail = true }
        val filter = ManyueGpuDisplayController(viewport, 34, { true }, { renderer })
        try {
            filter.configure(true, 25)
            repeat(4) { viewport.layout(0, 0, 700 + it, 1500 + it) }
            assertEquals(1, renderer.calls.size)
            assertEquals(ManyueGpuDisplayStatus.State.FAILED, filter.status.value.state)
            assertTrue(renderer.clears > 0)
            renderer.fail = false
            filter.configure(true, 30)
            assertEquals(2, renderer.calls.size)
            assertEquals(ManyueGpuDisplayStatus.State.ACTIVE, filter.status.value.state)
        } finally {
            filter.close()
        }
    }

    @Test fun detachedViewDropsEffectAndReattachesWithoutHoldingADeadView() = withViewport { viewport ->
        val renderer = Renderer()
        val filter = ManyueGpuDisplayController(viewport, 34, { true }, { renderer })
        val parent = viewport.parent as android.view.ViewGroup
        filter.configure(true, 25)
        parent.removeView(viewport)
        assertEquals(ManyueGpuDisplayStatus.State.WAITING, filter.status.value.state)
        assertEquals(1, renderer.clears)
        parent.addView(viewport)
        viewport.layout(0, 0, 690, 1421)
        assertEquals(2, renderer.calls.size)
        filter.close()
        filter.configure(true, 90)
        viewport.layout(0, 0, 2344, 2156)
        assertEquals(2, renderer.calls.size)
        assertEquals(ManyueGpuDisplayStatus.State.OFF, filter.status.value.state)
    }
}
