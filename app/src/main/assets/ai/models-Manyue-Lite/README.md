# Model asset package

Manyue-Lite is the small scale-conditioned comic repair model used by the Android runtime. The runtime loads `trunk.param`, `trunk.bin`, `head.param`, `head.bin`, and `head.f32`; `model-manifest.json` pins their hashes and defines the numerical contract. The remaining files provide license, attribution, card, and quality evidence for this exact model build.

The weights were trained from the step-22,000 checkpoint in `native/manyue-lite/training/runs/continuous-lite-best.pt` (SHA-256 `b1ca49b6064a8943985cdbca9725096e353a3b58f9a161bb202edfbbff2f2202`). `quality-report-test.json` is the independent held-out-from-training test report; its `quality_gate.passed` value is `true`, and its file hash is pinned in `model-manifest.json`.

Weight distribution uses Apache-2.0; see `LICENSE`. Training data source, chapter split, and CC BY 4.0 attribution are documented in `DATA_PROVENANCE.md` and the manifest in `native/manyue-lite/training/data/peppercarrot/manifest.json`. Original comic pages are not included.
