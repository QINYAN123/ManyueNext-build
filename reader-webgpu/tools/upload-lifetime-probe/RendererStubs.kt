package ca.mpreg.webgpuviewer.renderer

import androidx.webgpu.*

object WebGpuRenderer { lateinit var device: GPUDevice }
fun GPUTexture.destroyAndRelease() { destroys++; closes++ }
fun GPUBuffer.destroyAndRelease() { destroys++; closes++ }
