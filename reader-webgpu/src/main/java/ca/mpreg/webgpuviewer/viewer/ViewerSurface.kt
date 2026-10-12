package ca.mpreg.webgpuviewer.viewer

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.hardware.display.DisplayManager
import android.content.Context
import android.view.Surface
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.SurfaceCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import ca.mpreg.webgpuviewer.renderer.Hdr

/**
 * A [SurfaceView] from API 34, where HDR needs a `SurfaceControl` and only a [SurfaceView] has
 * one; a TextureView below that, where there is no HDR to lose.
 *
 * The TextureView is not just the older default: [AndroidExternalSurface] renders behind the
 * activity window and relies on the HWUI hole-punch, which silently fails on some OEM builds -
 * every frame renders with no errors while the screen stays black (mihonapp/mihon#3773).
 */
@Composable
internal fun ViewerSurface(
    modifier: Modifier,
    onSurfaceReady: suspend SurfaceCoroutineScope.(Surface, Int, Int) -> Unit,
) {
    val view = LocalView.current
    val ready: suspend SurfaceCoroutineScope.(Surface, Int, Int) -> Unit = { surface, width, height ->
        val refreshRequest = SurfaceRefreshRequest(view, surface)
        try {
            onSurfaceReady(surface, width, height)
        } finally {
            refreshRequest.close()
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        AndroidExternalSurface(modifier = modifier, isOpaque = false) {
            onSurface(ready)
        }
    } else {
        AndroidEmbeddedExternalSurface(modifier = modifier, isOpaque = false) {
            onSurface(ready)
        }
    }
}

/** The independent drawing Surface needs a rate vote too; a window mode alone is insufficient. */
private class SurfaceRefreshRequest(private val view: View, private val surface: Surface) : AutoCloseable {
    private val manager = view.context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private var lastDisplay = -1
    private var lastRate = -1f
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    init {
        if (Build.VERSION.SDK_INT >= 30) {
            manager?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            refresh()
        }
    }

    private fun refresh() {
        if (Build.VERSION.SDK_INT < 30 || !surface.isValid) return
        val display = view.display ?: return
        val mode = display.mode
        val rate = display.supportedModes.filter {
            it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight
        }.maxOfOrNull { it.refreshRate } ?: return
        if (display.displayId == lastDisplay && rate == lastRate) return
        // A hint, not a forced device setting. Respect system power/thermal decisions and
        // forbid a disruptive mode switch. Update only on a real display/rate change.
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                surface.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        }.onSuccess { lastDisplay = display.displayId; lastRate = rate }
    }

    override fun close() {
        if (Build.VERSION.SDK_INT < 30) return
        manager?.unregisterDisplayListener(listener)
        if (surface.isValid) runCatching { surface.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
    }
}

internal fun attachHdrDisplay(view: View) {
    Hdr.attachDisplay(view.display)
}

internal fun attachHdrSurface(view: View?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    Hdr.attachSurfaceControl(view?.findSurfaceView()?.surfaceControl)
}

/**
 * Compose builds it inside an `AndroidView` and does not hand it out. Null on the TextureView
 * path, and null if Compose ever stops backing [AndroidExternalSurface] with one - in which case
 * HDR stays off rather than breaking.
 */
private fun View.findSurfaceView(): SurfaceView? = when (this) {
    is SurfaceView -> this
    is ViewGroup -> (0 until childCount).asSequence()
        .mapNotNull { getChildAt(it).findSurfaceView() }
        .firstOrNull()

    else -> null
}
