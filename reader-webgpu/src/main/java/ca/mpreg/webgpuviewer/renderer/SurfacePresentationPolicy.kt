package ca.mpreg.webgpuviewer.renderer

import androidx.webgpu.PresentMode

/** Opt-in measurement only. Never select a presentation mode the surface did not advertise. */
object SurfacePresentationPolicy {
    fun choose(latestFrameRequested: Boolean, supportedModes: IntArray): Int =
        if (latestFrameRequested && PresentMode.Mailbox in supportedModes) PresentMode.Mailbox else PresentMode.Fifo
}
