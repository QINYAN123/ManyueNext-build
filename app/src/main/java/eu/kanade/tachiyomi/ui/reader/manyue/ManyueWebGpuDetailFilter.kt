package eu.kanade.tachiyomi.ui.reader.manyue

import androidx.webgpu.BufferUsage
import androidx.webgpu.GPUBindGroupEntry
import androidx.webgpu.GPUBuffer
import androidx.webgpu.GPUBufferDescriptor
import androidx.webgpu.GPUCommandEncoder
import androidx.webgpu.GPUColorTargetState
import androidx.webgpu.GPUFragmentState
import androidx.webgpu.GPUPrimitiveState
import androidx.webgpu.GPURenderPipeline
import androidx.webgpu.GPURenderPipelineDescriptor
import androidx.webgpu.GPUShaderModuleDescriptor
import androidx.webgpu.GPUShaderSourceWGSL
import androidx.webgpu.GPUTextureView
import androidx.webgpu.GPUVertexState
import androidx.webgpu.PrimitiveTopology
import ca.mpreg.webgpuviewer.filter.FilterFullscreen
import ca.mpreg.webgpuviewer.filter.FilterChain
import ca.mpreg.webgpuviewer.renderer.Fullscreen
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The existing bounded luminance detail effect, applied only to the finished viewport. */
internal class ManyueWebGpuDetailFilter : FilterFullscreen() {
    @Volatile var strength: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }
    @Volatile var submittedStrength: Int = 0
        private set
    @Volatile private var failed = false
    var onFailure: (() -> Unit)? = null

    override val active: Boolean get() = enabled && strength > 0 && !failed
    override val label: String get() = "Manyue viewport detail"
    override val code: String get() = SOURCE
    private var uniforms: GPUBuffer? = null
    private var uploadedStrength = -1
    private val bytes = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
    private val pipelines = mutableMapOf<Int, GPURenderPipeline>()
    private var boundFormat = -1

    override val pipeline: GPURenderPipeline
        get() {
            val format = outputFormat
            if (boundFormat != format) {
                rebind()
                boundFormat = format
            }
            return pipelines.getOrPut(format) {
                // Pipeline retains the module; release our wrapper immediately after creation.
                device.createShaderModule(
                    GPUShaderModuleDescriptor(shaderSourceWGSL = GPUShaderSourceWGSL(Fullscreen.VERTEX + code)),
                ).use { module ->
                    device.createRenderPipeline(
                        GPURenderPipelineDescriptor(
                            label = label,
                            vertex = GPUVertexState(module, entryPoint = "vs_main"),
                            fragment = GPUFragmentState(
                                module, entryPoint = "fs_main", targets = arrayOf(GPUColorTargetState(format = format)),
                            ),
                            primitive = GPUPrimitiveState(topology = PrimitiveTopology.TriangleList),
                        ),
                    )
                }
            }
        }

    override fun prepare(srcWidth: Int, srcHeight: Int) {
        val value = strength
        val buffer = uniforms ?: device.createBuffer(
            GPUBufferDescriptor(label = label, size = 16, usage = BufferUsage.Uniform or BufferUsage.CopyDst),
        ).also { uniforms = it }
        if (uploadedStrength != value) {
            bytes.clear()
            bytes.putFloat(value / 100f).putFloat(0f).putFloat(0f).putFloat(0f)
            bytes.flip()
            device.queue.writeBuffer(buffer, 0, bytes)
            uploadedStrength = value
        }
        submittedStrength = value
    }

    override fun entries(src: GPUTextureView): Array<GPUBindGroupEntry> = arrayOf(
        GPUBindGroupEntry(0, buffer = checkNotNull(uniforms)),
        GPUBindGroupEntry(1, textureView = src),
    )

    fun retryAfterSettingsChange() {
        failed = false
        invalidate()
    }

    override fun run(
        chain: FilterChain, encoder: GPUCommandEncoder,
        src: GPUTextureView, srcWidth: Int, srcHeight: Int,
        dst: GPUTextureView, dstWidth: Int, dstHeight: Int,
    ) {
        try {
            super.run(chain, encoder, src, srcWidth, srcHeight, dst, dstWidth, dstHeight)
        } catch (error: Throwable) {
            // The renderer drops this frame. A following unfiltered frame keeps the reader usable.
            failed = true
            invalidate()
            onFailure?.invoke()
            throw error
        }
    }

    override fun cleanup() {
        rebind()
        pipelines.values.forEach { it.close() }
        pipelines.clear()
        uniforms?.let { it.destroy(); it.close() }
        uniforms = null
        uploadedStrength = -1
        boundFormat = -1
    }

    companion object {
        val SOURCE = """
struct Params { values: vec4<f32>, }
@group(0) @binding(0) var<uniform> params: Params;
@group(0) @binding(1) var src: texture_2d<f32>;

fn pixel(p: vec2<i32>) -> vec4<f32> {
    let last = vec2<i32>(textureDimensions(src)) - vec2<i32>(1);
    return textureLoad(src, clamp(p, vec2<i32>(0), last), 0);
}
fn luminance(rgb: vec3<f32>) -> f32 {
    return dot(rgb, vec3<f32>(0.2126, 0.7152, 0.0722));
}
fn sampleLuma(p: vec2<i32>, center: f32) -> f32 {
    let s = pixel(p);
    return mix(center, luminance(s.rgb / max(s.a, 0.0001)), clamp(s.a, 0.0, 1.0));
}
@fragment
fn fs_main(in: VertexOutput) -> @location(0) vec4<f32> {
    let p = vec2<i32>(in.position.xy);
    let original = pixel(p);
    let strength = params.values.x;
    let a = original.a;
    if (a <= 0.0001 || strength <= 0.0) { return original; }
    let rgb = original.rgb / a;
    let center = luminance(rgb);
    let maxRgb = max(rgb.r, max(rgb.g, rgb.b));
    // Preserve HDR highlights; the SDR effect must never clip extended-range content.
    if (center <= 0.0001 || maxRgb <= 0.0001 || maxRgb > 1.0) { return original; }
    let n = sampleLuma(p + vec2<i32>(0, -1), center);
    let s = sampleLuma(p + vec2<i32>(0, 1), center);
    let w = sampleLuma(p + vec2<i32>(-1, 0), center);
    let e = sampleLuma(p + vec2<i32>(1, 0), center);
    let low = min(center, min(min(n, s), min(w, e)));
    let high = max(center, max(max(n, s), max(w, e)));
    let fine = center - (n + s + w + e) * 0.25;
    let broad = center - (
        sampleLuma(p + vec2<i32>(0, -3), center) + sampleLuma(p + vec2<i32>(0, 3), center) +
        sampleLuma(p + vec2<i32>(-3, 0), center) + sampleLuma(p + vec2<i32>(3, 0), center)
    ) * 0.25;
    let edgeGain = 1.0 - 0.75 * smoothstep(0.08, 0.40, high - low);
    let noiseGate = smoothstep(0.003, 0.020, abs(fine));
    let limit = 0.06 * strength;
    var shift = clamp(strength * edgeGain * (0.60 * fine * noiseGate + 0.12 * broad), -limit, limit);
    shift = clamp(shift, -min(rgb.r, min(rgb.g, rgb.b)), 1.0 - maxRgb);
    let gainDeltaLimit = limit / maxRgb;
    let gainDelta = clamp(shift / center, -gainDeltaLimit, gainDeltaLimit);
    let gain = clamp(1.0 + gainDelta, 0.0, 1.0 / maxRgb);
    return vec4<f32>(rgb * gain * a, a);
}
        """.trimIndent()
    }
}
