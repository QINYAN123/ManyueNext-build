package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import androidx.webgpu.PresentMode
import ca.mpreg.webgpuviewer.renderer.SurfacePresentationPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SurfacePresentationPolicyTest {
    @Test fun `normal readers retain FIFO even when mailbox is available`() {
        assertEquals(PresentMode.Fifo, SurfacePresentationPolicy.choose(false, intArrayOf(PresentMode.Fifo, PresentMode.Mailbox)))
    }
    @Test fun `experiment selects advertised mailbox independent of capability order`() {
        assertEquals(PresentMode.Mailbox, SurfacePresentationPolicy.choose(true, intArrayOf(PresentMode.Immediate, PresentMode.Mailbox, PresentMode.Fifo)))
    }
    @Test fun `unsupported mailbox falls back to FIFO rather than immediate tearing`() {
        assertEquals(PresentMode.Fifo, SurfacePresentationPolicy.choose(true, intArrayOf(PresentMode.Fifo, PresentMode.Immediate)))
    }
    @Test fun `missing capabilities retain guaranteed FIFO`() {
        assertEquals(PresentMode.Fifo, SurfacePresentationPolicy.choose(true, intArrayOf()))
    }
}
