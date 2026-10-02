package eu.kanade.tachiyomi.ui.reader.manyue

/** What the reader is actually doing or displaying for one page. */
enum class ManyueEnhancementState {
    ORIGINAL,
    CLASSIC_PROCESSING,
    CLASSIC_READY,
    AI_QUEUED,
    AI_PREPARING,
    AI_WAITING_RESOURCES,
    AI_PROCESSING,
    AI_WAITING_DISPLAY,
    AI_READY,
    AI_CLASSIC_READY,
    INTERPOLATED_READY,
    SKIPPED,
    FAILED,
}
