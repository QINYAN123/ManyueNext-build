# Third-party notices

漫阅 HD v0.3.6-foldable includes an optional, on-device fixed-2× AI path. The following
components are redistributed only for that feature. Their original license
texts are preserved in `third_party_licenses/` and in the APK under
`assets/licenses/`.

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
- Release asset used: `1.11.1/assets.zip`
- License: MIT; refer to the upstream project and release asset for the complete text
- Bundled executable SHA-256: `d74e2ff5366a3b548118d78d72a4e8e197c764dfda4222a1718c10f50cdece2b`

The executable is renamed to `libmanyue_realesr.so` only so Android installs it
inside the app's executable native-library directory. Its contents are
byte-for-byte identical to `assets/realsr/realsr-ncnn` from the release asset.

## Real-CUGAN NCNN Vulkan command-line integration

- Android integration: https://github.com/tumuyan/RealSR-NCNN-Android
- Upstream inference project: https://github.com/bilibili/ailab/tree/main/Real-CUGAN
- Release asset used: `1.11.1/assets.zip`
- License: MIT; refer to the upstream project and release asset for the complete text
- Bundled executable SHA-256: `19baf9fa336570c38686c3f14c5a295f1a2ee3642d37bb25a029d7802d620403`
- Bundled model: `models-se/up2x-no-denoise`, selected for the fixed native 2× fast path.

The executable is renamed to `libmanyue_realcugan.so` only so Android installs it
inside the app's executable native-library directory. Its contents are byte-for-byte
identical to `assets/realsr/realcugan-ncnn` from the release asset.

## Anime4KCPP optional overlay

- Upstream project: https://github.com/TianZerL/Anime4KCPP
- Source release: `v3.2.0` at commit `50c5d1b99965d804eeecfab9b48acfcaffddee3d`
- Build: Android NDK, ARM64, CPU/NEON backend, `ACNetB4` model
- License: MIT (see the preserved upstream license file)

The Anime4KCPP worker is built from the pinned upstream source by
`scripts/build_anime4k_android.sh`. It runs at factor 1 after AI output, so it does not alter
the fixed 2× dimensions. This CPU refinement is optional and disabled by default; it is not a
GPU shader and may increase result latency. A failed overlay keeps the valid AI image.

## NCNN

- Project: https://github.com/Tencent/ncnn
- Copyright: Tencent and contributors
- License: BSD 3-Clause, with additional notices for bundled components
- Bundled library SHA-256: `87d150e735157b09aa20f26f5e57f72468c548e7ce98ce407ec50ee7e14a52dd`

## Image codecs and supporting components

The inference executable was built upstream with OpenCV and image codec
components. The distribution therefore also preserves notices for OpenCV,
libjpeg-turbo, libpng, libtiff, OpenEXR, OpenJPEG, libwebp, zlib, stb,
cpu-features, ittnotify and protobuf. See the corresponding files in
`third_party_licenses/` or `assets/licenses/` for copyright, redistribution
conditions and warranty disclaimers.

No upstream project or contributor endorses this application. All third-party
software is provided under its respective license and without warranty.
