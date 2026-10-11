package eu.kanade.tachiyomi.ui.reader.manyue

import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import java.lang.ref.WeakReference

/** Only the benchmark manifest exposes the shell-only receiver. No user data is cleared. */
object ManyueBenchmarkControl {
    // Shell-only reversible sampling experiment. No user preference or cache identity changes.
    @Volatile internal var aiTileMipmaps: Boolean = false

    /** Temporary layer selection for the benchmark-only rendering comparison. */
    enum class ViewerLayerOverride(val wireName: String) {
        NONE("none"),
        HARDWARE("hardware"),
    }

    // Null preserves ReaderActivity's production hardware-layer behavior.
    @Volatile internal var viewerLayerOverride: ViewerLayerOverride? = null
    @Volatile var cacheNamespace: Int = 0
        internal set
    internal var reader = WeakReference<ReaderActivity>(null)
}
