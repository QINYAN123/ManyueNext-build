package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

/** Keeps the first page beyond the visible frontier ready for its first drawn frame. */
internal class WebGpuNextPagePreparation<T>(
    private val neighbors: (T) -> List<T>,
    private val eligible: (T) -> Boolean,
    private val prepare: (T, visible: Boolean, allowPreparation: Boolean) -> Unit,
) {
    private var previousVisible = emptySet<T>()
    private var previousRevision: Any? = null
    private var ahead: T? = null
    private var lastAheadVisible: Boolean? = null
    private var lastPreparationAllowed: Boolean? = null

    fun update(visible: Set<T>, revision: Any?, allowPreparation: Boolean = true) {
        val visibleChanged = visible != previousVisible
        val revisionChanged = revision != previousRevision
        if (visibleChanged || revisionChanged) {
            val next = visible.asSequence().flatMap { neighbors(it).asSequence() }
                .firstOrNull { it !in visible && eligible(it) }
            if (ahead != next) {
                // If the ahead page entered the viewport, preserve its true visible state while
                // retiring its offscreen admission. The visible owner still owns its bridge.
                ahead?.let { prepare(it, it in visible, false) }
                ahead = next
                lastAheadVisible = null
                lastPreparationAllowed = null
            }
            previousVisible = visible.toSet()
            previousRevision = revision
        }

        val target = ahead ?: return
        val isVisible = target in visible
        val isAllowed = allowPreparation && !isVisible
        val stateChanged = isVisible != lastAheadVisible || isAllowed != lastPreparationAllowed
        // Retry allowed work because original decode or AI readiness can change on a stable
        // viewport. A blocked state is applied only once, avoiding cancel/restart churn.
        if (stateChanged || revisionChanged || isAllowed) {
            prepare(target, isVisible, isAllowed)
            lastAheadVisible = isVisible
            lastPreparationAllowed = isAllowed
        }
    }

    fun reset(visible: Set<T> = previousVisible) {
        ahead?.let { prepare(it, it in visible, false) }
        ahead = null
        previousVisible = emptySet()
        previousRevision = null
        lastAheadVisible = null
        lastPreparationAllowed = null
    }
}
