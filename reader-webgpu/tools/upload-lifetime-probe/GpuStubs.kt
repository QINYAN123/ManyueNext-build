package androidx.webgpu

import java.nio.ByteBuffer

object BufferUsage { const val Uniform = 1; const val CopyDst = 2 }
object TextureUsage { const val TextureBinding = 1; const val CopyDst = 2; const val RenderAttachment = 4; const val StorageBinding = 8 }
object TextureFormat { const val RGBA8Unorm = 1; const val RGBA16Float = 2 }
data class GPUExtent3D(val width: Int, val height: Int)
data class GPUOrigin3D(val y: Int = 0)
data class GPUTextureDescriptor(val size: GPUExtent3D, val format: Int, val usage: Int)
data class GPUBufferDescriptor(val size: Long, val usage: Int)
data class GPUTexelCopyBufferLayout(val offset: Long, val bytesPerRow: Int, val rowsPerImage: Int)
data class GPUTexelCopyTextureInfo(val texture: GPUTexture, val origin: GPUOrigin3D = GPUOrigin3D())
class GPUBuffer { var destroys = 0; var closes = 0 }
class GPUTextureView { var closes = 0; fun close() { closes++ } }
class GPUTexture(val owner: GPUDevice, val width: Int, val height: Int, val format: Int) {
    var destroys = 0; var closes = 0
    fun createView(): GPUTextureView {
        owner.viewCalls++
        check(owner.failViewAt != owner.viewCalls) { "Injected view failure" }
        return GPUTextureView().also { owner.views.add(it) }
    }
}
class GPUDevice {
    val textures = mutableListOf<GPUTexture>()
    val views = mutableListOf<GPUTextureView>()
    val queue = GPUQueue(this)
    var viewCalls = 0; var writes = 0; var allocations = 0
    var cancelAt = -1; var failWriteAt = -1; var failAllocationAt = -1; var failViewAt = -1
    var cancel: () -> Unit = {}
    fun createTexture(d: GPUTextureDescriptor): GPUTexture {
        allocations++
        check(allocations != failAllocationAt) { "Injected allocation failure" }
        return GPUTexture(this, d.size.width, d.size.height, d.format).also { textures.add(it) }
    }
    fun createBuffer(d: GPUBufferDescriptor) = GPUBuffer()
}
class GPUQueue(val owner: GPUDevice) {
    fun writeTexture(dataLayout: GPUTexelCopyBufferLayout, data: ByteBuffer,
        destination: GPUTexelCopyTextureInfo, writeSize: GPUExtent3D) {
        owner.writes++
        check(owner.writes != owner.failWriteAt) { "Injected write failure" }
        if (owner.writes == owner.cancelAt) owner.cancel()
    }
}
