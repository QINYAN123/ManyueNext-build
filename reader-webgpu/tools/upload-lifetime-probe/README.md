# Upload ownership fault probe

Compiles the exact production `Mipmap.kt`, including its real kotlinx-coroutines yield/cancellation,
with fake Android log/trace and a fault-injecting WebGPU driver. No copied upload algorithm.
Checks each created texture is destroyed and released exactly once; each created view is released once.
Includes successful multi-tile upload, cancellation during first/later chunks, later-tile cancellation,
allocation/write/view failure, and HDR cancellation. It does not measure GPU performance or pixels.
GPU descriptors/writes are stubs: real driver allocation failure, device loss and native-thread teardown
still need device validation. This directory is outside Android source sets and is not packaged in APKs.

Run with a JDK and existing Gradle cache (Kotlin 2.4.20 / coroutines 1.11.0):

```text
python reader-webgpu/tools/upload-lifetime-probe/run.py --java /path/to/java --output /path/to/results
```

To reproduce the old leak, add `--baseline ee3b213`. Baseline is optional so the current-source
checks work from an exported source ZIP without `.git`. Test output stays outside the checkout.
