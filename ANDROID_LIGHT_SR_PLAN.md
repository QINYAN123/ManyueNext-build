# Lightweight Android SR with continuous target dimensions

The intended behavior is retained originals, subtle restoration near 1x for sufficiently clear color comics, and learned reconstruction at a user-selected target width for blurry images. A width heuristic can propose a scale; manual output size and detail strength remain independent controls. Width alone cannot detect JPEG artifacts or blur.

## Verified pretrained baseline

[Qualcomm QuickSRNet Small](https://github.com/quic/aimet-model-zoo/blob/develop/aimet_zoo_torch/quicksrnet/model/models.py) provides official separately trained [1.5x](https://raw.githubusercontent.com/quic/aimet-model-zoo/develop/aimet_zoo_torch/quicksrnet/model/model_cards/quicksrnet_small_1.5x_w8a8.json) and [2x](https://raw.githubusercontent.com/quic/aimet-model-zoo/develop/aimet_zoo_torch/quicksrnet/model/model_cards/quicksrnet_small_2x_w8a8.json) weights. Preserve upstream attribution and the [BSD-3-Clause license](https://github.com/quic/aimet-model-zoo/blob/develop/LICENSE) when integrating it.

| Baseline | Parameters | MAC/input pixel | Official checkpoint SHA-256 |
| --- | ---: | ---: | --- |
| Small 1.5x | 22,875 | 20,160 | d82f213361f4adc4e85d47728f0a59a3be3014f8013cc6219b0f20507b75487a |
| Small 2x | 22,860 | 22,752 | d95d70f1d2366cb9c28d99f8c7aa5bb07e1ffeaf5d7e30d9c66ab0fa28c6d0f8 |

Both official checkpoints were strictly loaded in an isolated Torch 2.5.1 CPU environment. Three different input shapes produced the expected dimensions, finite clamped RGB values, and full 690x986-page runs succeeded. A screenshot crop with synthetic bicubic degradation showed an improvement over bicubic in the measured PSNR. This is a limited pipeline check: the screenshot is not original manga ground truth, the sample is not a quality benchmark, and desktop timings do not predict phone speed.

The 1.5x model computes a separately trained 1.5x head, not 2x followed by downscaling. Its convolutional count is only about 11.4% below the 2x model, because both still perform most feature work at the input resolution. Neither checkpoint is a continuous-scale or 1x-restoration model.

## Continuous-scale direction

Use a small convolutional encoder, low-dimensional latent features and a learned output head conditioned on pixel position and scale. Generate only the requested target dimensions. Train over 1–2x with separate identity/light-restoration examples at 1x, synthetic compression/blur degradation and multi-scale reconstruction targets. Preserve text and lines; avoid adversarially hallucinated texture as the default.

Possible Android graph: RGB → small low-resolution encoder → low-dimensional features → actual-target interpolation/coordinate-conditioned small head → RGB residual plus directly resized original. A prototype must establish quality before choosing channel count, quantization or weights. A modified output head cannot use the old checkpoint unchanged; it needs training or distillation.

[LMF](https://github.com/HeZongyao/LMF) provides a useful continuous representation idea and pretrained research weights, but its available EDSR-baseline encoder alone costs about 1,218,240 MAC/input pixel, roughly 54x the complete QuickSR Small 2x convolutional count. Its sampling/unfold operations also require mobile implementation checks. Do not bundle the whole research network as a fast-reader default.

At inference, feature extraction retains an input-resolution cost floor. Only output-dependent work scales with requested output pixels unless the encoder itself is made adaptive. Continuous scale does not guarantee that 1.25x costs only 39% of 2x; the 39% ratio applies to output pixel area, not total inference. The genuine improvement must come from a smaller encoder, an efficient target-size head, and a resident Android runtime.

## Android implementation and acceptance

- Keep weights, Vulkan pipelines and allocator pools resident across pages. The current CLI starts a fresh process per page; the new engine must avoid that cold start.
- Benchmark ncnn Vulkan FP16 first. A Qualcomm NPU backend is an optional measured alternative, not a prerequisite or an assumed speedup.
- Verify every operator stays on the intended device; avoid repeated CPU/GPU transfers. Tile with validated halo/alignment and bounded memory; choose tile sizes using device measurements.
- Prioritize the page being read and near-future pages. Cancel stale requests after source/model/scale changes; reuse encoded cached results.
- Record queue, initialization, inference, encode, staged decode and commit times separately. Actual installation and continuous reading on Magic V2 must validate sustained throughput and frame time.
- Compare held-out color comic crops, text/edges and JPEG artifacts at 1x, 1.25x, 1.5x and 2x. Reject an experimental weight if it fails to beat simple interpolation or adds visible halos/texture.

Perf1 includes display-chain fixes only. No new learned weights are enabled in the APK; experimental checkpoints and reports remain separate from reader releases.
