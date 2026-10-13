package ca.mpreg.webgpuviewer.viewer

/** Keep the rejected timing experiment opt-in, including after a process restart. */
object ContinuousDragControl {
    @Volatile
    var replayFirstDragEvent = false
}
