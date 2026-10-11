# Manyue native output-size and resident workers

This directory contains the native output-size patch and an optional resident-session source generator for the legacy RealSR/Real-CUGAN workers. The quality route is Real-ESRGAN. The Real-CUGAN generator remains available for historical CLI experiments, while the Android product entry and packaged resource are handled separately.

The AI networks still infer at fixed `-s 2`; `-w <targetWidth>` changes only the image dimensions saved afterward. The requested height is `(sourceHeight * targetWidth + sourceWidth / 2) / sourceWidth`, using checked 64-bit arithmetic. Widths outside the inclusive range `[sourceWidth, 2 * sourceWidth]` are rejected.

The bundled quality model is `models-Real-ESRGANv3-anime/x2.param`. Its graph uses PixelShuffle4 on the learned branch, adds a 4x resized input branch, then applies a final 0.5x resize. The final tensor is 2x; `-s 2` is correct even though an intermediate layer is 4x. The host test checks both scale-defining operators in the packaged graph. The legacy Real-CUGAN CLI generator retains its upstream `up2x-no-denoise`/`-n 0` behavior for historical experiments.

For 1× through less than 2× outputs, the worker area-resamples the model's packed 8-bit RGB/RGBA output directly into a smaller packed buffer immediately before encoding. This replaces the former Android-side Skia bilinear resize, so pixel values may differ slightly. RGBA uses alpha-weighted color averaging. At exactly 2×, it skips the resize and allocation entirely. The worker prints source, model, and final dimensions with `resize=area` or `resize=skipped` for each processed image.

The patched cold CLI workers fail fast if `Net::load_param`, `Net::load_model`, Real-CUGAN's auxiliary bicubic layer `load_param`/`create_pipeline`, `Extractor::input`, `Extractor::extract`, Vulkan `submit_and_wait`, or a top-level model `load`/`process` call reports an error. A failed inference terminates the worker before encoding, instead of returning a success-shaped output buffer. The resident mode reports per-request failures and continues when the failure is recoverable. Dimensions alone do not prove image quality.

Resident RealSR also supports cooperative cancellation through a request-scoped marker at `<outputPath>.cancel`. The worker checks it before/after inference, at successful Vulkan chunk boundaries (including the wait and command-buffer reset), between CPU tiles, after resize, and immediately before encoding. A cancelled request returns `RESULT` status `-22` with zero output bytes and dimensions, releases the request buffers, skips saving, and leaves the resident model loaded. GPU work already submitted finishes before the next check; CPU cancellation waits for the current tile. A marker arriving after encoding has begun cannot interrupt that encoder call. The marker is owned and removed by the caller after the matching response.

Per-page input read/allocation, decode, target-size validation, resize allocation, and resize failures also terminate the worker with a nonzero exit. PNG, JPEG, and both WebP output paths check encoder status plus file writes, flush, and close; a failed output is deleted before the worker exits. This prevents a partial or preallocated image from being reported as a successful enhancement. These checks do not claim that the model output is visually good; they verify that the configured inference and file-writing steps reported success.

## Reproducible Android build

The build helper starts each invocation from the pinned source ZIP, applies the repository's existing `patch_upstream.py`, optionally generates resident-session sources, optionally applies the GPU chunk transformer, then builds the selected ARM64 worker and copies it to the caller's output directory. It does not reuse a prior source/build tree, delete old build directories, modify app Kotlin/Gradle/packaging or `jniLibs`, or add upstream source blobs to this repository. `WorkRoot` and `OutputDir` must be outside the Mihon repository.

Pinned inputs:

- `tumuyan/RealSR-NCNN-Android` tag `1.11.1` (commit `5eb6e3d`), source ZIP SHA-256 `595B505EB41D84ABB80D7EAB49886F485B2FC70E218CE9632B72F9B2747ED736`.
- Tencent ncnn Android Vulkan shared SDK tag `20241226`. The exact `arm64-v8a/lib/libncnn.so` SHA-256 is `87D150E735157B09AA20F26F5E57F72468C548E7CE98CE407EC50EE7E14A52DD`, byte-identical to the current Mihon `libncnn.so`; the matching generated headers come from the same SDK archive.
- `webmproject/libwebp` release `v1.5.0`, source archive SHA-256 `668C9ABA45565E24C27E17F7AAF7060A399F7F31DBA6C97A044E1FEACB930F37`.

The default engine is `realesr` and the default mode is resident. The current quality candidate includes the resident protocol and the GPU chunk source transformer, so build that exact native capability with `-Engine realesr -GpuChunk`. `MANYUE_GPU_CHUNK` selects the Real-ESRGAN trunk checkpoint interval: `1` extracts all 16 main Conv/PReLU outputs (56, 59, 62, 65, 68, 71, 74, 77, 80, 83, 86, 89, 92, 95, 98, 101), `2` retains the existing boundary after every two main Conv/PReLU pairs, and `4` retains the existing boundary after every four pairs. The remaining Conv_32/PRelu_33/Conv_34 and pixel-shuffle/residual/resize tail stays in the final extraction. An unset or unsupported value leaves the original inference path active. Mode 1 passed an ARM64 build and two decoded-pixel comparisons against the shipped cold output (maximum RGB difference 0) on a 1200×2160 source page. A short controlled webtoon run lowered frame P95 to about 32 ms, compared with about 489 ms for mode 0; this isolates GPU contention using an external worker and does not include AI image replacement. Final app-chain validation is reported separately. The RealSR graph check still requires the expected 41-layer graph, all 16 named PReLU checkpoint producers, and the verified Conv_32/PRelu_33/Conv_34/pixel-shuffle/resize tail producers. For historical Real-CUGAN, only modes 2 and 4 are accepted; other values, including 1, are treated as disabled. `-ColdCli` selects the original one-file CLI path with the output-size patch and omits resident mode. `-Engine realcugan` or `-Engine both` is retained for historical native CLI experiments; the default does not build CUGAN.

Example PowerShell invocation from the repository root (replace the external dependency paths with the pinned inputs on the build host):

```powershell
$RepoRoot = (Resolve-Path .).Path
& "$RepoRoot\native\manyue-target-output\build-android.ps1" `
  -UpstreamZip "D:\manyue-native-deps\RealSR-NCNN-Android-1.11.1.zip" `
  -NcnnRoot "D:\manyue-native-deps\ncnn-20241226-android-vulkan-shared" `
  -WebpRoot "D:\manyue-native-deps\libwebp-1.5.0" `
  -NdkRoot "D:\Android\ndk\29.0.14206865" `
  -WorkRoot "D:\manyue-native-build\work" `
  -OutputDir "D:\manyue-native-build\outputs" `
  -Engine realesr -GpuChunk `
  -CMakeExe "D:\tools\cmake\bin\cmake.exe" `
  -NinjaExe "D:\tools\ninja\ninja.exe"
```

The helper uses CMake (3.22.1 or newer) and Ninja from `PATH` unless `-CMakeExe`/`-NinjaExe` are passed. It verifies the upstream ZIP and ncnn runtime hashes, checks the libwebp version, configures `arm64-v8a` for Android API 26 with the static C++ runtime, prints each selected output's SHA-256, and reports ELF architecture and dynamic dependencies when `llvm-readelf` is present. OpenMP is statically linked when supported so the executable does not require a separately versioned `libomp.so`. Python 3 is required; use `-PythonLauncher python` on hosts without the Windows `py` launcher.

The generated stages are retained under `WorkRoot\runs\<unique-id>` for inspection and later comparison. To recreate the patched upstream cold CLI source deliberately, pass `-ColdCli`; source generation alone is not proof of compilation. A fresh-source Real-ESRGAN resident + GPU-chunk build passed on the Windows host with NDK 29.0.14206865. The generator produces the tested source logic (FP32 arithmetic); artifact bytes may vary with build paths/toolchains. On-device protocol and decoded-pixel checks are recorded separately from reader frame measurements.

The upstream Android build used NDK `25.2.9519653`, while the supplied ncnn 20241226 package was built with NDK r27c. The current build host uses NDK 29.0.14206865. The shared ncnn library is byte-identical to the packaged runtime, but the NDK difference remains a build/runtime validation point; an Android device run is still required before making a performance claim.

## Encoding and scope

OpenCV is removed from these workers because it was used only in the output encoder. PNG output uses the existing stb PNG encoder. All Android WebP outputs now use pinned libwebp 1.5.0 with `lossless=1`, compression effort `quality=0`, `method=0`, `exact=1`, and one thread. Compression effort does not reduce image quality in lossless mode. This replaces the expensive convenience lossless encoder at 2× and the former quality-95 lossy encoder below 2×. JPEG continues through stb at quality 100 for CLI compatibility. The app cache version is advanced so earlier lossy resized images are not reused. Files can be larger at lower compression effort; the cache retains its byte budget.

An earlier Magic V2 / Adreno 740 cold-process comparison used a 690×985 comic crop and showed lower total times after changing the output path. It predates the resident-session and GPU chunk source generators described above, so those numbers do not predict their latency or sustained reader jank. The output-path comparison found identical decoded RGBA pixels at 2× and identical new 1.5×/2× WebP and PNG outputs. The GPU arithmetic and automatic tiling are retained; an FP16 arithmetic trial was rejected after local color deviations.

This phase preserves the original comic file and the existing 2× model weights/inference. It removes Android's full-resolution bitmap resize/re-encode round trip and avoids a float-planar resize buffer. It does not reduce neural inference work and does not establish an FPS improvement. The upstream MIT notice is already retained at `app/src/main/assets/licenses/RealSR-NCNN-Android-LICENSE.txt`.

## Resize helper tests

`test-host.ps1` compiles and runs the host checks with the Windows Zig C++ driver installed for this workspace. `output_resize_test.cpp` exercises target-size rounding and validation, RGB area averages, premultiplied-alpha behavior, identity handling, invalid strides, and unsupported channel counts. The status test invokes the actual `MANYUE_NCNN_CHECK` macro twice in separate processes: status 0 must exit successfully with `ENCODE_REACHED`, while the mocked nonzero status must exit nonzero without that marker. `file_output_test.cpp` checks successful output bytes, injected short-write/flush/close/encoder failures and cleanup; it also performs a real temporary-file success and verifies an injected short write removes the partial file. Flush and close failures are mocked because they are difficult to force portably with a host `FILE*`.

```powershell
& .\work\mihon-source\native\manyue-target-output\test-host.ps1
```

The status test mocks ncnn return values; it does not run ncnn or Vulkan inference. A Zig host benchmark can measure the representative RGB area resize separately:

`fast_webp_test.cpp` links to pinned libwebp through the Manyue Lite host-test CMake build. It round-trips RGB and RGBA (including RGB behind zero alpha) exactly and rejects invalid input/unwritable paths. Run `manyue_fast_webp_test <output-prefix>` after configuring `MANYUE_LITE_BUILD_HOST_TESTS=ON`.

```powershell
& .\work\toolchains\native-build-tools\ziglang\zig.exe c++ `
  -std=c++11 -O2 -Wall -Wextra `
  -I .\work\mihon-source\native\manyue-target-output `
  .\work\mihon-source\native\manyue-target-output\output_resize_bench.cpp `
  -o .\work\native-output-phase1\output-resize-bench.exe
& .\work\native-output-phase1\output-resize-bench.exe
```

On the current Windows host this measured 42.62 ms per RGB resize for 1380×2842 → 863×1777 (five iterations, Zig `-O2`). This is a host-only timing and does not predict Android latency or frame rate.
