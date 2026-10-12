package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/** Keeps the first page beyond the visible frontier ready for its first drawn frame. */
internal class WebGpuNextPagePreparation<T>(
    private val neighbors: (T) -> List<T>,
    private val eligible: (T) -> Boolean,
    private val prepare: (T, Boolean) -> Unit,
) {
    private var previousVisible = emptySet<T>()
    private var previousRevision: Any? = null
    private var ahead: T? = null

    fun update(visible: Set<T>, revision: Any?) {
        if (visible != previousVisible || revision != previousRevision) {
            val next = visible.asSequence().flatMap { neighbors(it).asSequence() }
                .firstOrNull { it !in visible && eligible(it) }
            // The entering page's visible owner takes over without cancelling an upload.
            if (ahead != next && ahead !in visible) ahead?.let { prepare(it, false) }
            ahead = next
            previousVisible = visible.toSet()
            previousRevision = revision
        }
        // Original decode and AI completion can change without a viewport crossing.
        ahead?.let { prepare(it, true) }
    }

    fun reset() {
        ahead?.let { prepare(it, false) }
        ahead = null
        previousVisible = emptySet()
        previousRevision = null
    }
}
