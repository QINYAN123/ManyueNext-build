package eu.kanade.tachiyomi.ui.reader.manyue

/** Stable numeric phases; no source URLs, chapter identifiers or exception text are exported. */
internal enum class ManyuePrefetchPhase(val code: Long) {
    RECONCILE(1),
    DISABLED(2),
    MISSING_PAGES(3),
    CURSOR(4),
    CURRENT_DECISION(5),
    READER_GATE(6),
    WORKER_SLOT(7),
    CACHE_RESERVATION(8),
    SOURCE(9),
    ENQUEUE(10),
    CANDIDATE(11),
}

internal fun prefetchBoundary(
    phase: ManyuePrefetchPhase,
    page: Int,
    generation: Long,
    exit: Boolean = false,
    outcome: Long = 0,
) {
    if (!ManyuePerformanceDiagnostics.enabled) return
    ManyuePerformanceDiagnostics.event(
        if (exit) ManyuePerformanceRecorder.Stage.PREFETCH_EXIT else ManyuePerformanceRecorder.Stage.PREFETCH_ENTER,
        page,
        null,
        ManyuePerformanceRecorder.Metric.STATUS to phase.code,
        ManyuePerformanceRecorder.Metric.CAPTURED_GENERATION to generation,
        ManyuePerformanceRecorder.Metric.WAIT_OUTCOME to outcome,
    )
}

internal suspend fun <T> tracePrefetch(
    phase: ManyuePrefetchPhase,
    page: Int,
    generation: Long,
    block: suspend () -> T,
): T {
    if (!ManyuePerformanceDiagnostics.enabled) return block()
    prefetchBoundary(phase, page, generation)
    var outcome = 3L
    return try {
        block().also { outcome = 1L }
    } catch (error: kotlinx.coroutines.CancellationException) {
        outcome = 2L
        throw error
    } finally {
        prefetchBoundary(phase, page, generation, exit = true, outcome = outcome)
    }
}
