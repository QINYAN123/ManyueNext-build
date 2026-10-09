package eu.kanade.tachiyomi.ui.reader.manyue

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import java.lang.ref.WeakReference

/** Only the benchmark manifest exposes the shell-only receiver. No user data is cleared. */
object ManyueBenchmarkControl {
    @Volatile var cacheNamespace: Int = 0
        internal set
    internal var reader = WeakReference<ReaderActivity>(null)
}
