package eu.kanade.tachiyomi.ui.reader

import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.model.History

data class ResumeTarget(
    val chapter: Chapter,
    val page: Int,
)

/** Resolves the last chapter opened by the reader, preserving its exact page when available. */
object ResumeTargetResolver {
    fun resolve(
        history: List<History>,
        chapters: List<Chapter>,
        fallback: Chapter?,
    ): ResumeTarget? {
        val latestHistory = history
            .asSequence()
            .filter { it.readAt != null }
            .maxWithOrNull(compareBy<History> { it.readAt!!.time }.thenBy { it.id })
        val chapter = latestHistory
            ?.let { historyEntry -> chapters.firstOrNull { it.id == historyEntry.chapterId } }
            ?: fallback
            ?: return null
        return ResumeTarget(
            chapter = chapter,
            page = chapter.lastPageRead.toInt().coerceAtLeast(0),
        )
    }
}
