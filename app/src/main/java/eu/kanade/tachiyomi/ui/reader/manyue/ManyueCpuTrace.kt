package eu.kanade.tachiyomi.ui.reader.manyue

import android.os.Build
import android.os.Trace

/** Local numeric investigation only; do not wrap suspending work in a thread-local slice. */
internal inline fun <T> manyueCpuTrace(name: String, block: () -> T): T {
    if (Build.VERSION.SDK_INT < 29 || !Trace.isEnabled()) return block()
    Trace.beginSection(name)
    return try { block() } finally { Trace.endSection() }
}
