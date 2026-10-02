# Manyue-Lite model card

## Model

Manyue-Lite is a small 5,955-parameter, scale-conditioned convolutional model for comic-page repair and continuous 1.0x–2.0x enlargement. It returns a learned RGB residual over a bicubic base. The model has no GAN or fixed 2x downsample stage. Intended uses are gentle 1x repair of light JPEG blur/artifacts and direct low-resolution comic reconstruction at intermediate scales. Clean 1x input stays close to bicubic identity.

The encoder is three padded 3x3 convolutions, `3→16→16→16`, each followed by Hardtanh `[0,1]`. The 1x1 head consumes 23 channels, maps `23→32` with ReLU, then `32→3` as the raw RGB residual. Native inference uses FP32 NCNN trunk/head graphs plus a fused FP32 head buffer. Source coordinates use the FP32 ratio-first recipe `((float32(global_output)+0.5) * float32(input_size/output_size)) - 0.5`, each operation rounded to FP32 without FMA; phase uses ties-to-even. See `model-manifest.json` beside the app assets for exact tensor names, shapes, file hashes, sampling, and rounding rules.

## Training data and method

The model was trained on 55 official English compiled pages from Pepper&Carrot by David Revoy. Images remain outside the distributed source bundle and app assets. `data/peppercarrot/manifest.json` records source URLs, page checksums, credits, and a chapter-level split: episodes 1–6 training (32 pages), episodes 7–8 validation (11 pages), and episodes 9–10 independent test (12 pages). All page inputs are clamped and quantized to 8-bit RGB before comparisons; evaluation uses the same source tensor for the bicubic baseline and model.

The run used seed 20261002, 30,000 steps, batch size 8, 160px crops, and a four-category cycle with 25% clean identity examples. The best validation checkpoint is step 22,000. There is no adversarial loss. The exported release applies the documented scale-aware residual cap `limit = 0.02 + 0.02 * (actual_output_width/input_width - 1)` before applying strength.

## Independent quality results

The release quality gate passed on held-out episodes 9–10 and was independently checked on validation episodes 7–8. Reports preserve every evaluated sample, including the worst per-crop result.

On the 12-page test split, aggregate PSNR change against bicubic was positive at every tested scale and strength:

| Requested scale | Strength 60 aggregate ΔPSNR | Worst sample ΔPSNR | Strength 100 aggregate ΔPSNR | Worst sample ΔPSNR |
|---|---:|---:|---:|---:|
| 1.25x | +0.884 dB | −0.037 dB (`E09P07:bottom_right`) | +1.468 dB | −0.276 dB (`E10P01:bottom_right`) |
| 1.37x | +0.820 dB | −0.065 dB (`E10P01:bottom_right`) | +1.343 dB | −0.391 dB (`E10P01:bottom_right`) |
| 1.50x | +0.851 dB | −0.208 dB (`E10P01:bottom_right`) | +1.393 dB | −0.639 dB (`E10P01:bottom_right`) |
| 1.75x | +0.767 dB | −0.217 dB (`E10P01:bottom_right`) | +1.228 dB | −0.609 dB (`E10P01:bottom_right`) |
| 2.00x | +1.088 dB | −0.664 dB (`E10P01:bottom_right`) | +1.752 dB | −1.427 dB (`E10P01:bottom_right`) |

Every negative per-crop PSNR result remains listed in `quality-report-test.json`. The aggregate degraded-1x improvement was +0.111 dB at strength 60 and +0.165 dB at strength 100. Clean 1x input changed by at most 5.169 gray levels at strength 100 and 3.129 at strength 60. The combined-pixel p95 change was 0.01 and 0.00 gray levels respectively; the largest per-page p95 was 0.15 and 0.09 gray levels. The scale cap itself limits any AI-vs-bicubic change to 5.1 gray levels at 1x and 10.2 at 2x at full strength (3.06 and 6.12 at strength 60).

Whole-page 1x and tiled 2x outputs matched exactly in the PyTorch reference check. Validation aggregates were also positive at all scales; its worst per-crop drops are retained in `quality-report-validation.json` rather than hidden. The validation/test chapters were held out from gradient updates, but the final residual-cap threshold was selected after reviewing diagnostics that included clean text on test chapters E09 and E10. Treat these as held-out-from-training results, not a blind evaluation. They are offline image metrics, not a claim about every comic, device, or native backend. Native CPU/GPU verification is recorded separately by the host/runtime tests.

## Limits

The model is intentionally compact, and the scale-aware cap trades some possible sharpness for bounded local edits. Some individual near-perfect crops can show a negative relative ΔPSNR despite small absolute pixel changes. It is not a general photographic super-resolution model and should not be interpreted as a faithful recovery of information absent from the source.

## License and credits

The weights use Apache-2.0, matching the source repository's license as a project distribution choice. Pepper&Carrot training pages are by David Revoy and licensed CC BY 4.0; per-episode source and credit details are recorded in `data/peppercarrot/manifest.json`. The dataset's license does not automatically dictate the weight license. Original comic pages are not distributed with the model.
