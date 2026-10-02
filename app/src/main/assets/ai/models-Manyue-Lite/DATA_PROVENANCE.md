# Data provenance and attribution

The model was trained with official English compiled comic pages from **Pepper&Carrot**, created by **David Revoy** and published under **Creative Commons Attribution 4.0 International (CC BY 4.0)**.

- Dataset source and creator: [Pepper&Carrot](https://www.peppercarrot.com/) by David Revoy.
- License: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).
- Official attribution guidance: [Pepper&Carrot license best practices](https://www.peppercarrot.com/en/documentation/120_License_best_practices.html).
- Chapter split: episodes 1–6 training (32 pages), episodes 7–8 validation (11 pages), episodes 9–10 test (12 pages); no episode crosses splits.
- Individual page URLs, checksums, episode titles, and source-page credits: `native/manyue-lite/training/data/peppercarrot/manifest.json`.
- Original page images are omitted from this asset package and from the source training bundle.

The weights are distributed under Apache-2.0 as a project licensing choice matching the source repository. That choice does not assert that the CC BY 4.0 data license automatically determines the weight license.

The held-out chapters were not used for gradient updates. The final residual-cap threshold was selected after reviewing diagnostics that included clean text in test chapters E09 and E10, so quality results are held out from training rather than blind.
