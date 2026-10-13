# v76: truthful viewport diagnostics, not a motion fix

Baseline: 34a688ee1ea1e8044707c6aa09e9d16e7f0e0159, installed v75 APK SHA256
8050ef72e030d5744f7c54a308422f7f13853e9e27d6138e360af5bf1eaa1836.

The benchmark snapshot reused the persistence getter. That getter returns null
for scale >=1; falling back to 1 therefore hid actual zoom-in. Add actualScale
and homeScale without changing the old comfortScale field or saved preferences.
Enabled ATrace also records scale per capture, so a future trace can locate a
viewport change rather than assuming a start/end persistence value is exact.
No image, neural, sampling, tile, inertia, prefetch or fold behavior changes.

Local host tools now preserve original video PTS and measure subpixel content
translation with texture/occlusion/correspondence rejection. Procedural translation
tests include 0.2/0.5/+-10/+-150px. The empirical spatial error envelope is a
heuristic, not a recording-level confidence interval. Winscope metadata provides
elapsed-clock encoded-frame timestamps; neither it nor correct-image submission
proves physical panel scanout. Raw recordings/comic pixels remain private.

v75 observations, not v76 benefit evidence:

- Two stable long-page controls: native 2156x2344 videos, source690x1894,
  Quality1380x3788, OFF/Quality. Both have 103 captures, all fully tiled,
  zero fallback, no current-page decode/upload. Quality still completed four
  background Native jobs; this is not isolated inference-free performance.
- Original ROI101/101 frame pairs accepted; Quality99/101. No accepted content
  step >+0.1px downwards in the analyzed forward path. This does not rule out
  other regions, physical judder, or rejected pairs. Integer-phase versus
  fractional-fallback causality was not established in these controls.
- Immediate next-chapter recorded stress: previous chapter2 eligible exposures
  plus next chapter19 unique exposures. Next chapter timely3/19 at delta1s,
  ever5/19, first1/19, late2, missed14. Do not inflate next-chapter success by
  including the two already prepared old-chapter pages. Recorded callbacks
  were approximately60Hz; video QA is not a120Hz performance verdict.
- Next-chapter unrecorded mixed load55.923s: next chapter34 unique exposures,
  timely27/34, ever28/34. Post-capture comfort readout changed1->0.86101836;
  cause unresolved, fixed-condition gate failed. Keep raw results as unmatched
  diagnostic observations, not a matched improvement or controlled PASS.
- Unrecorded CPU stage P95 acquire1.5151ms, render dispatch0.2831ms; no >1px
  cumulative camera reversal or height correction. Missing physical timestamps
  and limited GPU instrumentation still prevent a perceived-motion conclusion.

Keep GPU detail OFF, normal color/BW Quality2x pixels unchanged, and rejected
Mailbox/drag-replay/extra-next-preparation OFF. Do not ship global blur, grayscale,
new sharpening or altered inertia on these findings. Next: verify actualScale
retention and per-frame scale on v76, then isolate presentation/Native competition
using a no-recorder workload; any rendering candidate needs benefit beyond baseline
noise, static-quality preservation and matched pairs. Full Quality2 acceptance
remains incomplete; a build or diagnostic improvement is not a smoothness cure.

## Follow-up v77: viewport retention correctness candidate

v76 was installed at the exact packaged hash. New actualScale telemetry confirmed
that the same comic reopened at1.0 after leaving at0.86101836; the retention gate
failed. Do not mark that installation as settings-retention PASS. Source inspection
found every generic imagePropertyChanged callback unconditionally assigned both
homeScale and scale from continuousMinWidth. Initial preference emissions are
asynchronous, so they can overwrite the Activity's per-book restoration.

Initialize the global width synchronously before per-book restoration. During
later generic image callbacks, reset zoom only if that preference itself changed.
Keep explicit width-setting changes effective. Four state-transition regressions
cover initial/nondefault/repeated emissions and explicit changes back and forth.
Device cold-reopen/setting-refresh retention still requires the current v77 APK.
No shader, model, Native, sampling, inertia, fold-size or chapter-progress changes.
This fixes a confirmed startup/configuration bug, not all reported scroll judder.
