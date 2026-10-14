# Third-party notices

This experimental Mihon-based Manyue build includes the original4×→2× quality
model, a separate native2× quality candidate, and experimental hybrid routing
between those two graphs. Lite is retired and is not packaged in the APK. The following
components are redistributed only for that feature. Their original license
texts are preserved in the source at `app/src/main/assets/licenses/` and in the
APK under `assets/licenses/`.

## Archived Manyue Lite training data and runtime (source only)

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
  `native/manyue-lite/retired/models-Manyue-Lite/` and `native/manyue-lite/training/` in the source archive.
- Runtime: NCNN 20241226 Vulkan (BSD-3-Clause), stb_image and stb_image_write (MIT/public-domain dual license),
  libwebp 1.5.0 (BSD-style license and patent grant). Preserved texts appear in
  `assets/licenses/`; the archived `libmanyue_lite.so` uses these codecs and resident NCNN inference.

The illustrations' creators do not endorse or participate in this application.

## Original Real-ESRGAN animevideo-v3 quality model

- Project: https://github.com/xinntao/Real-ESRGAN
- Copyright: Xintao Wang and contributors; BSD3-Clause.
- Asset folder: ai/models-Real-ESRGANv3-anime
- x2.bin SHA256:548a36f9c3f4ab8da56cd3b13badf23968bee207b396dad14d04b830e5f2ab2d
- x2.param SHA256:b88ff4f00ebf019a7fdac17fdd45a7fd3665d37509efc5baf2e4da2e24420a04
- Original graph reconstructs4×, then interpolates to final2×.
- Preserved as default and independent control; no new weight training.

## Experimental native2× derivative of Real-ESRGAN animevideo-v3

- Project: https://github.com/xinntao/Real-ESRGAN
- Copyright: Xintao Wang and contributors
- License: BSD 3-Clause
- Candidate asset folder: `ai/models-Real-ESRGANv3-anime-native2`
- Bundled files: `x2.bin`, `x2.param`
- SHA-256 (`x2.bin`): `a3a99b7bcc6a8dd094821af2cf8df8d0c1b5ae262571eb465bb9359ce28f8015`
- SHA-256 (`x2.param`): `44f0e77ab0c793d42a2b2858b20969959d8ab985c575a8578b15e857bb518ac1`

The original backbone is retained; the output head was distilled for1000 steps
on32 licensed Pepper&Carrot pages (CC BY4.0, David Revoy and contributors).
The candidate uses PixelShuffle2 and a2× residual, followed by an identity
Interp1; it has no internal4× image or downscale. No independent manga
validation or sustained energy benefit is claimed. The original weights remain
also packaged as the separately selectable original model. See model-manifest.json and
docs/QUALITY2_NATIVE2_V79.md for provenance and incomplete acceptance.

## RealSR / Real-ESRGAN NCNN Vulkan command-line integration

- Android integration: https://github.com/tumuyan/RealSR-NCNN-Android
- Upstream inference project: https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan
- Source used: tag `1.11.1`, commit `5eb6e3d`, with the local target-output and error-propagation patches
- License: MIT; refer to the upstream project and release asset for the complete text
- Bundled executable SHA-256: `a4c5581ef5b0d6e0f96e3b7c6d607d7a6f97565f80cc1f6760b1f3953c3f2698`

The executable is named `libmanyue_realesr.so` so Android installs it inside the
app's executable native-library directory. It is rebuilt from the pinned source;
it differs from the upstream release executable. The patch and build recipe are
in `native/manyue-target-output/`. The bundled worker and ncnn runtime are
unchanged from v78; only the quality model graph and output-head weights change.

## Real-CUGAN NCNN Vulkan command-line integration

- Android integration: https://github.com/tumuyan/RealSR-NCNN-Android
- Upstream inference project: https://github.com/bilibili/ailab/tree/main/Real-CUGAN
- Source used: tag `1.11.1`, commit `5eb6e3d`, with the local target-output and error-propagation patches
- License: MIT; refer to the upstream project and release asset for the complete text
- Bundled executable SHA-256: `d4ab907cb698b621f429b09ec5757318b23a1e05f8a7f77575c9af8ff24cb464`
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
libwebp 1.5.0 for image codecs. All WebP target sizes use fast lossless compression (quality=0 is compression effort,
method=0, exact=1); PNG output is also lossless.
OpenCV is no longer linked into these two executables. The distribution retains
the earlier codec notices for compatibility, together with the libwebp COPYING
and PATENTS texts and stb license. See `assets/licenses/` for copyright,
redistribution conditions and warranty disclaimers.

The Lite JNI runtime also uses stb and statically linked libwebp 1.5.0. Its
direct target-size output uses lossless WebP, or stb_image_write PNG when either
output axis exceeds WebP's 16,383-pixel maximum. The PNG writer checks cancellation
before compression, on the writer callback and before atomic publication; it cannot
interrupt stb compression internally.

No upstream project or contributor endorses this application. All third-party
software is provided under its respective license and without warranty.
