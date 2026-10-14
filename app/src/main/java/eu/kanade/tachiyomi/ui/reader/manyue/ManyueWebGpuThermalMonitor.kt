package eu.kanade.tachiyomi.ui.reader.manyue

import android.os.Build
import android.os.PowerManager
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** The independent WebGPU Surface does not go through WebtoonRecyclerView's thermal hooks. */
internal class ManyueWebGpuThermalMonitor(
    private val view: View,
    private val lifecycle: Lifecycle,
) : DefaultLifecycleObserver, View.OnAttachStateChangeListener {
    private val powerManager = view.context.applicationContext.getSystemService(PowerManager::class.java)
    private var closed = false
    private val session = ManyueReaderThermalSession(
        acquireOwner = { ManyueReaderWorkGate.update(this, active = false) },
        releaseOwner = { ManyueReaderWorkGate.release(this) },
        reportStatus = { ManyueReaderWorkGate.reportThermalStatus(this, it) },
    )

    init {
        view.addOnAttachStateChangeListener(this)
        lifecycle.addObserver(this)
        reconcile()
    }

    private fun reconcile() {
        if (closed || !view.isAttachedToWindow || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            session.stop()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = powerManager ?: return
        session.start(
            currentStatus = { manager.currentThermalStatus },
            subscribe = { callback ->
                val listener = PowerManager.OnThermalStatusChangedListener(callback)
                manager.addThermalStatusListener(view.context.mainExecutor, listener)
                val remove: () -> Unit = { manager.removeThermalStatusListener(listener) }
                remove
            },
        )
    }

    override fun onResume(owner: LifecycleOwner) = reconcile()
    override fun onPause(owner: LifecycleOwner) = session.stop()
    override fun onViewAttachedToWindow(v: View) = reconcile()
    override fun onViewDetachedFromWindow(v: View) = session.stop()
    override fun onDestroy(owner: LifecycleOwner) = close()

    fun close() {
        if (closed) return
        closed = true
        session.stop()
        view.removeOnAttachStateChangeListener(this)
        lifecycle.removeObserver(this)
    }
}
