package eu.kanade.tachiyomi.ui.reader.manyue

/**
 * A one-page glance must not reverse the whole chapter's background work. Keep the farthest
 * head in the committed direction; moving two pages against it confirms deliberate reversal.
 * Visibility and current-page priority are independent and always update immediately.
 * Callers own synchronization and reset this tracker when their chapter/session key changes.
 */
internal class ManyueReadingDirection {
    private var frontier = -1
    private var direction = ManyueAiSafetyPolicy.PrefetchDirection.FORWARD

    fun reset(
        page: Int = -1,
        initialDirection: ManyueAiSafetyPolicy.PrefetchDirection = ManyueAiSafetyPolicy.PrefetchDirection.FORWARD,
    ): ManyueAiSafetyPolicy.PrefetchDirection {
        frontier = page
        direction = initialDirection
        return direction
    }

    fun selected(page: Int): ManyueAiSafetyPolicy.PrefetchDirection {
        if (frontier < 0) {
            frontier = page
            return direction
        }
        val distance = (page.toLong() - frontier) * direction.step
        if (distance >= 0) {
            frontier = page
        } else if (distance <= -2) {
            direction = if (direction == ManyueAiSafetyPolicy.PrefetchDirection.FORWARD) {
                ManyueAiSafetyPolicy.PrefetchDirection.BACKWARD
            } else {
                ManyueAiSafetyPolicy.PrefetchDirection.FORWARD
            }
            frontier = page
        }
        return direction
    }
}
