package eu.kanade.tachiyomi.ui.reader.manyue

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

data class ManyueGpuDisplayStatus(val state: State = State.OFF, val strength: Int = 25) {
    enum class State { OFF, ZERO_STRENGTH, WAITING, ACTIVE, UNSUPPORTED, FAILED }

    val label: String?
        get() = when (state) {
            State.OFF -> null
            State.ZERO_STRENGTH -> "GPU 滤镜：强度为 0"
            State.WAITING -> "GPU 滤镜等待显示"
            State.ACTIVE -> "GPU 显示滤镜 $strength% · 非 AI"
            State.UNSUPPORTED -> "GPU 滤镜不可用（需 Android 13 和硬件绘制）"
            State.FAILED -> "GPU 滤镜启动失败，已保留原显示"
        }
}

/**
 * Owns one effect on the screen-sized viewer container. Never attach this to page holders:
 * a webtoon page can be much taller than the screen. HUD/menu views are outside this container.
 * Only a toggle, strength or viewport change updates the effect; scrolling does not rebuild it.
 */
internal class ManyueGpuDisplayController(
    private val viewport: View,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val hardwareAvailable: () -> Boolean = { viewport.isHardwareAccelerated },
    private val backendFactory: () -> Backend = {
        if (Build.VERSION.SDK_INT >= 33) Api33Backend() else error("AGSL requires Android 13")
    },
) : View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
    internal interface Backend {
        fun apply(view: View, width: Int, height: Int, strength: Int)
        fun clear(view: View)
    }

    private data class Parameters(val width: Int, val height: Int, val strength: Int)

    private val mutableStatus = MutableStateFlow(ManyueGpuDisplayStatus())
    val status = mutableStatus.asStateFlow()
    private var enabled = false
    private var strength = 25
    private var backend: Backend? = null
    private var applied: Parameters? = null
    private var failed = false
    private var closed = false

    init {
        viewport.addOnAttachStateChangeListener(this)
        viewport.addOnLayoutChangeListener(this)
    }

    fun configure(enabled: Boolean, strength: Int) {
        if (closed) return
        val safeStrength = strength.coerceIn(0, 100)
        if (this.enabled != enabled || this.strength != safeStrength) failed = false
        this.enabled = enabled
        this.strength = safeStrength
        applyIfReady()
    }

    private fun applyIfReady() {
        if (closed) return
        val state = when {
            !enabled -> ManyueGpuDisplayStatus.State.OFF
            strength == 0 -> ManyueGpuDisplayStatus.State.ZERO_STRENGTH
            sdkInt < 33 -> ManyueGpuDisplayStatus.State.UNSUPPORTED
            failed -> ManyueGpuDisplayStatus.State.FAILED
            !viewport.isAttachedToWindow || viewport.width <= 0 || viewport.height <= 0 ->
                ManyueGpuDisplayStatus.State.WAITING
            !hardwareAvailable() -> ManyueGpuDisplayStatus.State.UNSUPPORTED
            else -> null
        }
        if (state != null) {
            clearEffect()
            mutableStatus.value = ManyueGpuDisplayStatus(state, strength)
            return
        }
        val parameters = Parameters(viewport.width, viewport.height, strength)
        if (applied == parameters) return
        try {
            val renderer = backend ?: backendFactory().also { backend = it }
            renderer.apply(viewport, parameters.width, parameters.height, parameters.strength)
            applied = parameters
            mutableStatus.value = ManyueGpuDisplayStatus(ManyueGpuDisplayStatus.State.ACTIVE, strength)
        } catch (error: Throwable) {
            // Retry only after an explicit settings change, never on every layout/frame.
            failed = true
            clearEffect()
            mutableStatus.value = ManyueGpuDisplayStatus(ManyueGpuDisplayStatus.State.FAILED, strength)
            logcat(LogPriority.WARN, error) { "Manyue GPU display filter unavailable; original display retained" }
        }
    }

    private fun clearEffect() {
        if (applied != null || failed) runCatching { backend?.clear(viewport) }
        applied = null
    }

    fun close() {
        if (closed) return
        clearEffect()
        closed = true
        viewport.removeOnAttachStateChangeListener(this)
        viewport.removeOnLayoutChangeListener(this)
        backend = null
        mutableStatus.value = ManyueGpuDisplayStatus()
    }

    override fun onViewAttachedToWindow(v: View) = applyIfReady()

    override fun onViewDetachedFromWindow(v: View) {
        clearEffect()
        if (!closed && enabled && strength > 0) {
            mutableStatus.value = ManyueGpuDisplayStatus(ManyueGpuDisplayStatus.State.WAITING, strength)
        }
    }

    override fun onLayoutChange(
        v: View,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        oldLeft: Int,
        oldTop: Int,
        oldRight: Int,
        oldBottom: Int,
    ) = applyIfReady()

    @RequiresApi(33)
    private class Api33Backend : Backend {
        private val shader = RuntimeShader(ManyueGpuDisplayShader.SOURCE)

        override fun apply(view: View, width: Int, height: Int, strength: Int) {
            shader.setFloatUniform("viewportSize", width.toFloat(), height.toFloat())
            shader.setFloatUniform("strength", strength / 100f)
            // RenderEffect snapshots uniforms. Recreate the effect after changing parameters,
            // while reusing the compiled shader. This is not done on every scroll frame.
            view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
            view.invalidate()
        }

        override fun clear(view: View) {
            view.setRenderEffect(null)
        }
    }
}
