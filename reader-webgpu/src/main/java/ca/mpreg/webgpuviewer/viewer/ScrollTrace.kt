package ca.mpreg.webgpuviewer.viewer

import android.os.Build
import android.os.Trace

/** Numeric, local ATrace observations only; inactive outside an enabled app trace. */
internal object ScrollTrace {
    val enabled: Boolean get() = Build.VERSION.SDK_INT >= 29 && Trace.isEnabled()

    // Only wrap non-suspending calls: thread-local ATrace slices must not span a yield.
    inline fun <T> section(name: String, block: () -> T): T {
        if (!enabled) return block()
        Trace.beginSection(name)
        return try { block() } finally { Trace.endSection() }
    }

    fun counter(name: String, value: Long) {
        if (enabled) Trace.setCounter(name, value)
    }

    fun input(ageMs: Long, panY: Float) {
        if (!enabled) return
        Trace.setCounter("manyue.input.ageMs", ageMs)
        Trace.setCounter("manyue.input.panY100", (panY * 100).toLong())
    }

    fun scroll(documentY: Double) {
        if (enabled) Trace.setCounter("manyue.scroll.documentY100", (documentY * 100).toLong())
    }

    fun capture(cameraY: Float, pageHeight: Float, page: ImagePage?, motion: Int) {
        if (!enabled) return
        Trace.setCounter("manyue.capture.cameraY100", (cameraY * 100).toLong())
        Trace.setCounter("manyue.capture.pageHeight100", (pageHeight * 100).toLong())
        Trace.setCounter("manyue.capture.pageObject", System.identityHashCode(page).toLong())
        Trace.setCounter("manyue.capture.motion", motion.toLong())
    }

    fun heightCorrection(ratio: Double) {
        if (enabled) Trace.setCounter("manyue.capture.heightRatioPpm", (ratio * 1_000_000).toLong())
    }

    fun submitted(cameraY: Float) {
        if (enabled) Trace.setCounter("manyue.submitted.cameraY100", (cameraY * 100).toLong())
    }
}
