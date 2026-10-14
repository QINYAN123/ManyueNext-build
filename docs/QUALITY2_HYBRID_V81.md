# Quality2 v81: Lite retirement and experimental hybrid

Baseline: v80 `1bd55d12b854cf696a3c9e84f0e755921e928a90`.
APK SHA256 `e0edf649ce0e498c3383ac16d807015fbd336592587b1c670d5698a470fb008e`.
Quality1 ancestry remains `edcd2f2c60e32ac59957c5eb186f04ae4f4958f2`.

## Scope and routing

Lite is no longer selectable. Its saved ID migrates to original Quality, retaining
reading progress, border preferences and the dormant fractional-scale preference.
Lite weights/JNI move to source-only `native/manyue-lite/retired/`, preserving
history, provenance and recovery without packaging them in the APK. Old enum
ordinals remain stable. CUGAN remains retired.

Original Quality is still default. Native2 remains an independent experimental
choice. `智能混轴（性能友好）` is a third, explicitly experimental choice; both
physical graphs produce2x. It does not add inference concurrency or another model.
The same original/native2 weights and resident ARM64 worker remain byte identical.

At the serial worker's next job boundary, after resource waiting, hybrid selects:
- Original4x->2x normally.
- Native2 when Android reports LIGHT or higher thermal status, or at least two
  active/queued visible requests in the same chapter/generation include a job
  waiting at least2seconds. Far prefetch count alone is not this signal.
- Return after30seconds without observed pressure and at least20seconds in native2.
  These experimental debounce values limit reloads; they are not measured optimal
  settings. Missing thermal data is recorded as unknown, not a measured cool state.

The system's existing severe-heat protection remains. The change does not stop all
inference during scrolling, alter display refresh/brightness, or cancel a running
job merely to switch weights. Generation changes reset routing. No completed image
is re-inferred or re-swapped just because routing changes.

## Identity and cache

Selected hybrid identity/generation is separate from the physical model pinned to
each dispatched request. Request deduplication uses logical choice and generation;
exact cache keys use physical weight identity. Chapter retention uses the logical
hybrid profile to retain both variants. Original/native2 never overwrite each
other's exact cache entries. Both WebGPU and legacy bridge refresh the final
physical cache key at READY; this prevents a provisional queued key being used
to read the completed image. Result provenance exports physical model/asset
identity alongside logical selection. A manual switch invalidates old generations.

AI_READY still does not prove visible drawing. WebGPU coverage represents draw
submission, not physical scanout. Current candidate device presentation, mixed
cache20+page reading, thermal gains, switches/reload cost and subjective differences
remain untested: the user has disconnected the phone for this stage.

## Performance limits

Native2 removes internal4x output reconstruction but keeps the dominant pretrained
64-channel backbone. The theoretical convolution MAC reduction is only3.35%.
v79/v80 isolated Android timings varied342–611ms vs293–585ms on two small images;
some native2 requests were slower. Neither mobile speed nor energy improvement
has been proved. Hybrid is a feasibility candidate, not a promised thermal fix.

Next device comparison: matched ordinary color/BW chapter path and border ratio,
original/native2/hybrid, three alternating rounds with mixed caches and heat;
record actual physical model per page, reloads, timely visible coverage, independent
Surface deadlines, input/presentation timing and thermal status. If routing lacks
benefit or regresses, keep original and disable/revise hybrid rather than declaring
success from passing host tests.

## New local fine-tuning experiment

Explicit user authorization now permits a bounded local CUDA experiment. Start
from the original Quality pretrained backbone and analytic native2 head conversion,
not a new random network and not the previous Pepper-trained head. Warm up the
new head then fine-tune at low learning rate; output remains directly2x.

Use separately licensed works with color-main/BW-aux sampling and work-separated
validation/test. A creator's2026-10-08 AI-derivation restriction warrants excluding
Pepper from fresh training; it does not assert older CC rights were retroactively
revoked. Free commercial-platform viewing is not assumed a training license.
Current available licensed starter styles do not establish Korean webtoon coverage.

Trainer adds hash-bound authorization/resume identity, token-bound STOP, no export
for incomplete training, paired degraded/clean/color-neutral evaluation, per-work
regression guards against original Quality and independent post-selection test.
Weights remain outside app assets pending visual/numerical/Android verification.
Training alone cannot reduce unchanged-backbone computation or prove energy gains.
