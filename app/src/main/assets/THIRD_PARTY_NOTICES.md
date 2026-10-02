# Third-party notices

This Mihon-based Manyue build includes an optional, on-device continuously
scaled Manyue Lite model and legacy fixed-2× AI paths with configurable 1×–2×
output dimensions. The following
components are redistributed only for that feature. Their original license
texts are preserved in the source at `app/src/main/assets/licenses/` and in the
APK under `assets/licenses/`.

## Manyue Lite training data and runtime

- Model: project-created 5,955-parameter continuous-scale residual network,
  trained from scratch; model files and training scripts use the project's Apache-2.0 license.
- Training illustrations: *Pepper&Carrot*, David Revoy and contributors.
- Official source and credits: https://www.peppercarrot.com/ and
  https://www.peppercarrot.com/en/about/index.html
- Illustration license: CC BY 4.0, https://creativecommons.org/licenses/by/4.0/
- Attribution guidance: https://www.peppercarrot.com/en/documentation/120_License_best_practices.html
- Training changes: crops, scaling, synthetic blur/compression and model fitting;
  chapters are separated between train, validation and test. Original training pages
  are not packaged in the APK. The dataset license and project model license are separate.
- Model card, dataset provenance, quality measurements and exact weight digests:
  `assets/ai/models-Manyue-Lite/` and `native/manyue-lite/training/` in the source archive.
- Runtime: NCNN 20241226 Vulkan (BSD-3-Clause), stb_image (MIT/public-domain dual license),
  libwebp 1.5.0 (BSD-style license and patent grant). Preserved texts appear in
  `assets/licenses/`; the new `libmanyue_lite.so` uses these codecs and resident NCNN inference.

The illustrations' creators do not endorse or participate in this application.

## Real-ESRGAN animevideo-v3 model

- Project: https://github.com/xinntao/Real-ESRGAN
- Copyright: Xintao Wang and contributors
- License: BSD 3-Clause
- Bundled files: `x2.bin`, `x2.param`
- SHA-256 (`x2.bin`): `548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d`
- SHA-256 (`x2.param`): `b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04`

## RealSR / Real-ESRGAN NCNN Vulkan command-line integration

- Android integration: https://github.com/tumuyan/RealSR-NCNN-Android
- Upstream inference project: https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan
- Source used: tag `1.11.1`, commit `5eb6e3d`, with the local target-output and error-propagation patches
- License: MIT; refer to the upstream project and release asset for the complete text
- Bundled executable SHA-256: `ce28f19e41175ed0062bd4fbf21b1ee06d98b901036627048cf37f929b784ae8`

The executable is named `libmanyue_realesr.so` so Android installs it inside the
app's executable native-library directory. It is rebuilt from the pinned source;
it differs from the upstream release executable. The patch and build recipe are
in `native/manyue-target-output/`. Model weights and the bundled ncnn runtime
remain unchanged.

## Real-CUGAN NCNN Vulkan command-line integration

- Android integration: https://github.com/tumuyan/RealSR-NCNN-Android
- Upstream inference project: https://github.com/bilibili/ailab/tree/main/Real-CUGAN
- Source used: tag `1.11.1`, commit `5eb6e3d`, with the local target-output and error-propagation patches
- License: MIT; refer to the upstream project and release asset for the complete text
- Bundled executable SHA-256: `9f5b7d43c3074d9ef3d4d1a0c1ed69a7819789ce5dcb52699f4e22298ab873ff`
- Bundled model: `models-se/up2x-no-denoise`, selected for the fixed native 2× fast path.

The executable is named `libmanyue_realcugan.so` so Android installs it inside the
app's executable native-library directory. It is rebuilt from the pinned source;
it differs from the upstream release executable. The patch and build recipe are
in `native/manyue-target-output/`. Model weights remain unchanged.

## Anime4KCPP optional overlay

- Upstream project: https://github.com/TianZerL/Anime4KCPP
- Source release: `v3.2.0` at commit `50c5d1b99965d804eeecfab9b48acfcaffddee3d`
- Build: Android NDK, ARM64, CPU/NEON backend, `ACNetB4` model
- License: MIT (see the preserved upstream license file)

The Anime4KCPP worker is built from the pinned upstream source by
`scripts/build_anime4k_android.sh`. It runs at factor 1 after AI output, so it does not alter
the requested output dimensions. This CPU refinement is optional and disabled by default; it is not a
GPU shader and may increase result latency. A failed overlay keeps the valid AI image.

## NCNN

- Project: https://github.com/Tencent/ncnn
- Copyright: Tencent and contributors
- License: BSD 3-Clause, with additional notices for bundled components
- Bundled library SHA-256: `87d150e735157b09aa20f26f5e57f72468c548e7ce98ce407ec50ee7e14a52dd`

## Image codecs and supporting components

The rebuilt RealSR and Real-CUGAN executables use stb and statically linked
libwebp 1.5.0 for image codecs. WebP output is lossless at the native 2× size and
uses quality 95 when resized to a smaller target; PNG output is lossless.
OpenCV is no longer linked into these two executables. The distribution retains
the earlier codec notices for compatibility, together with the libwebp COPYING
and PATENTS texts and stb license. See `assets/licenses/` for copyright,
redistribution conditions and warranty disclaimers.

The Lite JNI runtime also uses stb and statically linked libwebp 1.5.0. Its
direct target-size output is lossless WebP, independent of the legacy CLI
resizing policy above.

No upstream project or contributor endorses this application. All third-party
software is provided under its respective license and without warranty.
