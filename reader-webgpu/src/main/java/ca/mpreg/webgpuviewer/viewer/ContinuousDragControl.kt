package ca.mpreg.webgpuviewer.viewer

/** Benchmark-only override allows a same-build comparison against the old event handoff. */
object ContinuousDragControl {
    @Volatile
    var replayFirstDragEvent = true
}
