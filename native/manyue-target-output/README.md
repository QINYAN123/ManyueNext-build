# Manyue native output-size workers

This directory contains the phase-one native output-size change for the RealSR and Real-CUGAN command-line workers. The AI networks still infer at fixed `-s 2`; `-w <targetWidth>` changes only the image dimensions saved afterward. The requested height is `(sourceHeight * targetWidth + sourceWidth / 2) / sourceWidth`, using checked 64-bit arithmetic. Widths outside the inclusive range `[sourceWidth, 2 * sourceWidth]` are rejected.

For 1× through less than 2× outputs, the worker area-resamples the model's packed 8-bit RGB/RGBA output directly into a smaller packed buffer immediately before encoding. This replaces the former Android-side Skia bilinear resize, so pixel values may differ slightly. RGBA uses alpha-weighted color averaging. At exactly 2×, it skips the resize and allocation entirely. The worker prints source, model, and final dimensions with `resize=area` or `resize=skipped` for each processed image.

The native workers now fail fast if `Net::load_param`, `Net::load_model`, Real-CUGAN's auxiliary bicubic layer `load_param`/`create_pipeline`, `Extractor::input`, `Extractor::extract`, Vulkan `submit_and_wait`, or a top-level model `load`/`process` call reports an error. A failed inference terminates the worker before encoding, instead of returning a success-shaped output buffer. This makes the process result a stronger check that every requested page passed through the model, though dimensions alone still do not prove image quality.

Per-page input read/allocation, decode, target-size validation, resize allocation, and resize failures also terminate the worker with a nonzero exit. PNG, JPEG, and both WebP output paths check encoder status plus file writes, flush, and close; a failed output is deleted before the worker exits. This prevents a partial or preallocated image from being reported as a successful enhancement. These checks do not claim that the model output is visually good; they verify that the configured inference and file-writing steps reported success.

## Reproducible Android build

The build helper patches an extracted source archive in the scratch directory, builds both ARM64 workers, and copies the resulting executables to a scratch output directory. It never modifies app Kotlin, Gradle, workflows, or `jniLibs`.

Pinned inputs:

- `tumuyan/RealSR-NCNN-Android` tag `1.11.1` (commit `5eb6e3d`), source ZIP SHA-256 `595B505EB41D84ABB80D7EAB49886F485B2FC70E218CE9632B72F9B2747ED736`.
- Tencent ncnn Android Vulkan shared SDK tag `20241226`. The exact `arm64-v8a/lib/libncnn.so` SHA-256 is `87D150E735157B09AA20F26F5E57F72468C548E7CE98CE407EC50EE7E14A52DD`, byte-identical to the current Mihon `libncnn.so`; the matching generated headers come from the same SDK archive.
- `webmproject/libwebp` release `v1.5.0`, source archive SHA-256 `668C9ABA45565E24C27E17F7AAF7060A399F7F31DBA6C97A044E1FEACB930F37`.

Example PowerShell invocation (replace paths with the dependency locations on the build host):

```powershell
& .\native\manyue-target-output\build-android.ps1 `
  -UpstreamZip .\work\native-output-phase1\upstream-1.11.1-full.zip `
  -NcnnRoot .\work\native-deps\ncnn-sdk\ncnn-20241226-android-vulkan-shared `
  -WebpRoot .\work\native-deps\libwebp-source\libwebp-1.5.0 `
  -NdkRoot .\work\toolchains\android-sdk\ndk\29.0.14206865 `
  -WorkRoot .\work\native-output-phase1\build-run `
  -OutputDir .\work\native-output-phase1\outputs
```

The helper uses CMake and Ninja from PATH when available, then checks the local `work\toolchains\native-build-tools` directory. Pass `-CMakeExe` or `-NinjaExe` to override either path. It verifies the pinned source ZIP and ncnn runtime hashes, checks the libwebp version, configures `arm64-v8a` for Android API 26 with the static C++ runtime, builds both workers, prints their hashes, and reports ELF architecture and dynamic dependencies. OpenMP is statically linked when supported so the new executable does not require a separately versioned `libomp.so`.

The upstream Android build used NDK `25.2.9519653`, while the supplied ncnn 20241226 package was built with NDK r27c. The current build host uses NDK 29.0.14206865. The shared ncnn library is byte-identical to the packaged runtime, but the NDK difference remains a build/runtime validation point; an Android device run is still required before making a performance claim.

## Encoding and scope

OpenCV is removed from these new workers because it was used only in the output encoder and its Android SDK is a large additional dependency. PNG output uses the existing stb PNG encoder. Resized WebP output (below 2×) uses pinned libwebp 1.5.0 at quality 95, matching the old Android-side resized-WebP quality. Exact 2× output skips resizing and uses the upstream lossless WebP writer. JPEG continues through stb at quality 100 for CLI compatibility. The normal Mihon AI image path uses PNG/WebP; encoder byte output may differ from the earlier OpenCV path, so compare decoded pixels and file sizes if the format behavior changes in the app.

This phase preserves the original comic file and the existing 2× model weights/inference. It removes Android's full-resolution bitmap resize/re-encode round trip and avoids a float-planar resize buffer. It does not reduce neural inference work and does not establish an FPS improvement. The upstream MIT notice is already retained at `app/src/main/assets/licenses/RealSR-NCNN-Android-LICENSE.txt`.

## Resize helper tests

`test-host.ps1` compiles and runs the host checks with the Windows Zig C++ driver installed for this workspace. `output_resize_test.cpp` exercises target-size rounding and validation, RGB area averages, premultiplied-alpha behavior, identity handling, invalid strides, and unsupported channel counts. The status test invokes the actual `MANYUE_NCNN_CHECK` macro twice in separate processes: status 0 must exit successfully with `ENCODE_REACHED`, while the mocked nonzero status must exit nonzero without that marker. `file_output_test.cpp` checks successful output bytes, injected short-write/flush/close/encoder failures and cleanup; it also performs a real temporary-file success and verifies an injected short write removes the partial file. Flush and close failures are mocked because they are difficult to force portably with a host `FILE*`.

```powershell
& .\work\mihon-source\native\manyue-target-output\test-host.ps1
```

The status test mocks ncnn return values; it does not run ncnn or Vulkan inference. A Zig host benchmark can measure the representative RGB area resize separately:

```powershell
& .\work\toolchains\native-build-tools\ziglang\zig.exe c++ `
  -std=c++11 -O2 -Wall -Wextra `
  -I .\work\mihon-source\native\manyue-target-output `
  .\work\mihon-source\native\manyue-target-output\output_resize_bench.cpp `
  -o .\work\native-output-phase1\output-resize-bench.exe
& .\work\native-output-phase1\output-resize-bench.exe
```

On the current Windows host this measured 42.62 ms per RGB resize for 1380×2842 → 863×1777 (five iterations, Zig `-O2`). This is a host-only timing and does not predict Android latency or frame rate.
