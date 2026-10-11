package eu.kanade.tachiyomi.ui.reader.manyue

import kotlin.math.ceil
import kotlin.math.abs

/** Predicts a useful start position when arriving pages outrun one resident worker.
 * It never changes reading progress, visibility, or the five-page cancellation window.
 */
internal class ManyueReadAheadPolicy(private val nanoTime: () -> Long = System::nanoTime) {
    private var chapter = Long.MIN_VALUE
    private var generation = Long.MIN_VALUE
    private var head = -1
    private var direction = 1
    private var selectedAt = 0L
    private var intervalMs = 0.0
    private var samples = 0
    private var msPerPixel = .0025

    @Synchronized fun reset() {
        chapter = Long.MIN_VALUE
        generation = Long.MIN_VALUE
        head = -1
        direction = 1
        selectedAt = 0L
        intervalMs = 0.0
        samples = 0
    }

    @Synchronized fun selected(chapterId: Long, page: Int, requestGeneration: Long) {
        val now = nanoTime()
        if (chapter != chapterId || generation != requestGeneration) {
            reset()
            chapter = chapterId
            generation = requestGeneration
        } else if (page == head) {
            return // repeated geometry callbacks are not new reading-speed samples
        } else if (head >= 0) {
            val delta = page - head
            val step = if (delta > 0) 1 else -1
            val elapsed = (now - selectedAt) / 1_000_000.0
            val interval = elapsed / abs(delta.toDouble())
            if (step != direction || abs(delta.toLong()) > 5 || interval !in 40.0..2_000.0) {
                samples = 0
                intervalMs = 0.0
            } else {
                intervalMs = if (samples == 0) interval else .65 * intervalMs + .35 * interval
                samples++
            }
            direction = step
        }
        head = page
        selectedAt = now
    }

    @Synchronized fun completed(width: Int, height: Int, elapsedMs: Long) {
        val pixels = width.toLong() * height
        if (pixels <= 0 || elapsedMs !in 100L..30_000L) return
        val measured = ((elapsedMs - 100).coerceAtLeast(1L).toDouble() / pixels).coerceIn(.0002, .01)
        msPerPixel = .75 * msPerPixel + .25 * measured
    }

    @Synchronized fun distance(chapterId: Long, page: Int, requestGeneration: Long): Int? =
        if (chapter == chapterId && generation == requestGeneration && head >= 0) (page - head) * direction else null

    @Synchronized fun preferredLead(chapterId: Long, requestGeneration: Long, width: Int, height: Int): Int {
        if (chapter != chapterId || generation != requestGeneration || samples < 2 || intervalMs <= 0 ||
            nanoTime() - selectedAt > (intervalMs * 2.5).coerceIn(800.0, 1_500.0) * 1_000_000.0
        ) return 0
        val costMs = 100 + width.toLong().coerceAtLeast(0) * height.coerceAtLeast(0) * msPerPixel
        if (costMs <= intervalMs * 1.1) return 0
        return (ceil(costMs / intervalMs).toInt() - 1).coerceIn(1, ManyueAiSafetyPolicy.PREFETCH_WINDOW_PAGES)
    }
}

internal object ManyueReadAheadScheduler {
    val policy = ManyueReadAheadPolicy()
}
