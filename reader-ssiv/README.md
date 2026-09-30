# Reader SSIV module

This module vendors `library/src/main` from [mihonapp/subsampling-scale-image-view](https://github.com/mihonapp/subsampling-scale-image-view), commit `94915e6f73b3d13187d3c794079e75eb6fd4974f`. The fixed source archive SHA-256 is `1ec46a4fac70584575015305aad28f00ea4479a8fa6faf1b52a0c0e994da0ced`. The upstream root `LICENSE` is included here and is Apache License 2.0.

The module builds `src/main/cpp/crop/CMakeLists.txt` directly as the `ssiv_crop` Android native target. The upstream `src/main/cpp/CMakeLists.txt` is retained as part of the vendored source tree but is not the Gradle native build entry point.

Local SSIV integration adds `setRegionDecoderFactory`, a zero-argument factory for tiled image decoders. Without a configured factory, SSIV continues to create the upstream `Decoder`. Initialization snapshots the source region, crop setting, factory, and image generation; it transfers an initialized decoder only to the matching image and recycles it on errors, cancellation, or stale completion. Tile loads check their decoder and generation before decoding and before publishing; stale bitmaps are recycled and stale errors are ignored.

`CropBorders.findCropBordersGray` accepts one grayscale byte per source pixel and validates positive dimensions and an exact `width * height` array length before calling the existing native border finder. The existing RGBA JNI method remains unchanged. `consumer-rules.pro` keeps the JNI class and native method names stable under shrinking.

The AI reader path can provide its own `ImageRegionDecoder` through the factory, so encoded AI output can be read by regions. Other paths retain the default decoder and its existing RGBA-buffer behavior.
