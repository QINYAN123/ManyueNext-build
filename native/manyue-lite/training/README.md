# Manyue-Lite training and export bundle

This directory contains the scripts and checkpoint needed to reproduce the Manyue-Lite FP32 model export. The runtime release weights are in `app/src/main/assets/ai/models-Manyue-Lite/`; those are the five files loaded by the Android runtime. The checkpoint here is for retraining and audit. The original Pepper&Carrot page images are deliberately not included.

## What is included

- `scripts/`: model, CUDA training, official data fetcher, independent evaluator, and diagnostics.
- `data/peppercarrot/manifest.json`: official source URLs, chapter-level split, attribution, dimensions, and expected SHA-256 for each page. Original image files are not bundled.
- `runs/continuous-lite-best.pt`: selected step-22,000 checkpoint from the 30,000-step run; SHA-256 is recorded in the model manifest and reports.
- `runs/training-final.jsonl`: training configuration and validation history.
- `quality-report-test.json` and `quality-report-validation.json`: fixed held-out reports with every measured sample, including worst per-crop PSNR changes.
- `CHECKSUMS.sha256`: checksums for the source bundle and the app's model package; `python verify_checksums.py` checks the list from the repository root.
- `golden/contract-random/`: deterministic nonzero-head contract model and small input fixture.
- `golden/trained/`: trained model export and a small synthetic PNG/JPEG golden fixture at 1.37x. Full six-scale goldens are indexed at `golden/trained-index.json` and were generated in the work training directory.

## Reproduce training

Use Python 3.10+ and install dependencies. A CUDA GPU is recommended; CPU is supported but substantially slower.

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python scripts/fetch_peppercarrot.py
python scripts/gradient_smoke.py
python scripts/train.py --minutes 18 --max-steps 30000 --seed 20261002
python scripts/evaluate_quality.py --checkpoint runs/continuous-lite-best.pt --output runs/quality-report-test.json --split test
```

The fetcher downloads official English compiled pages into `data/peppercarrot/` and checks each downloaded page against the recorded checksum. Episodes 1–6 are training, 7–8 validation, and 9–10 held-out test; no episode crosses a split. Do not package the fetched pages with the model. The release checkpoint was trained for 30,000 steps with four equally sampled batch categories (clean 1x, degraded 1x, low-scale reconstruction, and high-scale reconstruction); the selected validation checkpoint is step 22,000. The recorded run used a GeForce RTX 4080 Laptop GPU and PyTorch 2.5.1+cu124. Validation and test chapters were excluded from gradient updates, but the release residual cap was selected after diagnostic review that included clean text from test chapters E09/E10, so the results are held out from training rather than a blind evaluation.

To regenerate a release export, first run the evaluator and then require its passing report:

```powershell
python export_ncnn.py --training-root . --checkpoint runs/continuous-lite-best.pt --quality-report runs/quality-report-test.json --release --output-dir ..\..\..\app\src\main\assets\ai\models-Manyue-Lite
```

To recreate the deterministic golden artifacts:

```powershell
python export_golden.py --training-root . --checkpoint runs/continuous-lite-best.pt --output-dir golden/trained-generated --index-out golden/trained-generated-index.json
python export_golden.py --training-root . --output-dir golden/contract-random-generated --index-out golden/contract-random-generated-index.json
```

For CPU/GPU native numerical regression, a 690×985 RGB crop can be regenerated from a locally fetched held-out page. The generated page crop is kept outside the source package because it contains comic artwork:

```powershell
python scripts/export_page_golden.py --training-root . --data-root <downloaded-data-root> --page-id E09P02 --split test --output-dir <work-output>/native-page-690x985
```

The exporter accepts `--training-root`, `--checkpoint`, and `--output-dir`; paths can be relative to that root or absolute. The cap contract is implemented in the PyTorch reference and exported model manifest. The NCNN graphs return the raw residual; callers apply the cap, strength, base addition, and output clamp.

## Runtime contract

The encoder is 3x3 Conv 3→16→16→16 with Hardtanh `[0,1]` after each convolution. The 1x1 head consumes 23 channels (`features16`, `baseRGB3`, `sx`, `sy`, `phaseX`, `phaseY`), maps 23→32 with ReLU, then 32→3 raw RGB residual. NCNN inputs and outputs are named `in0` and `out0`. The fused `head.f32` contains 867 little-endian FP32 values in OIHW order, without NCNN tags. NCNN `.bin` files include a four-byte FP32 tag before each convolution tensor.

Output dimensions use positive half-up rounding. Sampling uses the FP32 ratio-first coordinate recipe `((float32(global_output)+0.5) * float32(input_size/output_size)) - 0.5`, rounding each operation to FP32 and prohibiting FMA; phase uses round-to-even. The base uses edge extension and bicubic `a=-0.75`; features use bilinear half-pixel resize. Before strength is applied, raw residual is clamped to `±(0.02 + 0.02 * (actual_output_width / input_width - 1))`. The final output is `clamp(base + capped_residual * strength/100, 0, 1)`. At 1x the cap is 0.02 (5.1 gray levels at strength 100); at 2x it is 0.04 (10.2 gray levels). The default app strength is 60.

## Licensing and attribution

The model weights are distributed under Apache-2.0, matching the source repository's license. This is the project's distribution choice; it does not claim that the training-data license automatically determines the weight license.

Training pages are by David Revoy from the official English compiled Pepper&Carrot comics, licensed CC BY 4.0. Episode titles, source URLs, and page SHA-256 values are in `data/peppercarrot/manifest.json`; episode pages link to the official source pages for translation and proofreading credits. See [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) and the [official Pepper&Carrot attribution guide](https://www.peppercarrot.com/en/documentation/120_License_best_practices.html). No original comic pages are included in this model or training source bundle.
