import androidx.webgpu.*
import ca.mpreg.webgpuviewer.renderer.*
import java.nio.ByteBuffer
import kotlinx.coroutines.*

// Exercise the complete unmodified production Mipmap.kt against a fault-injecting driver.
// This tests resource ownership and real coroutine cancellation, not a GPU performance model.
fun main() = runBlocking {
    data class Case(val name: String, val cancel: Int = -1, val write: Int = -1,
        val allocation: Int = -1, val view: Int = -1, val format: Int = TextureFormat.RGBA8Unorm)
    val cases = listOf(Case("successful_multi_tile"), Case("cancel_first_chunk", cancel=1),
        Case("cancel_second_chunk", cancel=2), Case("cancel_after_completed_tile", cancel=11),
        Case("write_failure_first", write=1), Case("write_failure_later_tile", write=11),
        Case("allocation_failure_later_tile", allocation=2), Case("view_failure_first", view=1),
        Case("view_failure_second", view=2), Case("hdr_cancel", cancel=2, format=TextureFormat.RGBA16Float))
    for (test in cases) {
        val device = GPUDevice().apply {
            cancelAt=test.cancel; failWriteAt=test.write; failAllocationAt=test.allocation; failViewAt=test.view
        }
        WebGpuRenderer.device = device
        var outcome = "completed"
        val child = launch {
            device.cancel = { coroutineContext.cancel() }
            try {
                val image = Mipmap.create(ByteBuffer.allocateDirect(1), 3072, 768, 1f, 2048, test.format)
                image.cleanup()
            } catch (_: CancellationException) { outcome = "cancelled" }
            catch (_: IllegalStateException) { outcome = "driver_failure" }
        }
        child.join()
        val leaked = device.textures.count { it.destroys != 1 || it.closes != 1 }
        val viewLeaks = device.views.count { it.closes != 1 }
        check(test.cancel < 0 || outcome == "cancelled") { "Cancellation did not fire: ${test.name}" }
        check(test.write < 0 && test.allocation < 0 && test.view < 0 || outcome == "driver_failure")
        println("${test.name}|$outcome|${device.textures.size}|$leaked|$viewLeaks")
    }
}
