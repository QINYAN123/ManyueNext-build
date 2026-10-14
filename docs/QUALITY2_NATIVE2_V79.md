# v79 native2× experimental candidate

Status: CONDITIONAL PASS for a buildable candidate only. This is not an accepted
performance/energy or manga-quality upgrade. Stable control is v78,
3b1259f3034bf2fb4639d52b158af75d96bb8c89 on codex/mihon-webgpu-ai. v78 APK
SHA256:7ebffa6cfeed31a765e50cb4a3321ccef4019cfc31647660c764f732b1f88528.

User requires direct internal2×, replacing the historical4× reconstruction plus
half-size interpolation. This branch contains a separately identified model
candidate; it does not replace the stable branch or any published Release.

## What changes

The original64-channel SRVGG-style backbone is byte-identical. The last3×3
convolution changes from48 to12 channels, PixelShuffle4 becomes PixelShuffle2,
the nearest-neighbor residual becomes2× and the final Interp becomes identity1×.
There is no4× image in this candidate and no final downscale. The identity tail
retains the verified41-layer graph names/types for the existing segmented worker.
The ARM64 worker, codecs, Vulkan runtime, submission/cancellation logic, display
sampling and touch physics are unchanged. v78 thermal hooks are inherited.

The12-channel head was distilled for1000 steps on32 licensed Pepper&Carrot
pages. This is head conversion, not full manga fine-tuning. It does not prove
restoration of real details or independent manga generalization. Original and
derived BSD-3-Clause model provenance and CC BY4.0 material attribution are in
assets/THIRD_PARTY_NOTICES.md and the model-manifest.json beside x2.bin.

The model runtime directory and cache identity both include the new revision.
Old resident files and old4×→2× results cannot satisfy this candidate's model
checks/cache keys. User preference IDs, progress, per-book borders, Quality2×,
Lite and OFF remain compatible. A regression test checks old cache isolation.

## Current evidence

The exact v78 packaged worker ran24 isolated requests on Magic V2 with both
graphs. Inputs were320×448 procedural color/gray drawings, output640×896.
Startup loaded once; subsequent request modelLoads=0 confirms resident reuse.
Tile400 had three alternating baseline/candidate rounds; tile80 was a separate
seam probe. Reader was stopped to isolate the worker. Files are in
docs/quality-native2-evidence/runtime-probe.json.

Native2 head arithmetic was checked against its exported quantized weights in
host FP32, using both reflect and replicate contexts and reporting boundaries.
With48 output pixels excluded, candidate RGB mean error0.46/0.79 and P99 1/3
levels on color/gray, respectively; original controls also pass the prospective
mean≤2/P99≤8 conversion check. This is numerical compatibility, not a subjective
quality gate. Candidate-versus-original host differences on two private viewing
QA crops were mean0.125/0.134 RGB levels, P99 1. Private manga pixels are excluded.
Those few crops are not independent work-level model validation.

Tile80 versus400 differs by max4(color)/9(gray) levels for candidate, versus3/10
for original. P99 is1/2 for both; output is not claimed tile-byte-identical.
The small tile was substantially slower. No global tile reduction is made.

Isolated tile400 inference milliseconds, original→candidate per round:

| Case | Round0 | Round1 | Round2 |
| --- | --- | --- | --- |
| Procedural color |337→599|342→342|345→528|
| Procedural gray |580→602|293→611|585→534|

These short rounds vary greatly and do NOT prove improvement; some are slower.
Thermal status was0 before/after, but frequencies/power and sustained warm-reader
conditions were not measured. Conv MAC619200→598464 per input pixel is only a
3.35% theoretical reduction, not proportional4×→2× acceleration. Bulk backbone
computation remains. Do not market this candidate as solving long-reading heat.
Native resize telemetry also includes output validation/neutral projection;
nonzero resizeUs does not imply that this graph downscales its2× tensor.

## Installed device and missing acceptance

v78 upgraded v77 successfully and opened WebGPUContinuous with Quality2×,
GPU detail OFF, 0.847 retained border. USB disconnected during the subsequent
thermal lifecycle test. The observer enabled recording before disconnect;
disable observation at the next available connection. No listener removal,
moderate/severe gating, cooling or long-reading benefit is declared passed.

v79 is an experimental benchmark APK. Installation, application model-directory
copy/hash checks, correct current-page drawing, continuous reading, scroll input
latency and actual Surface presentation need v79 validation. Native isolated
results do not substitute for those checks. Do not upload a formal PASS Release.

## Next decision

First validate the v79 package/model provenance and exact2× runtime/display path.
Then matched mixed-cache/warm20+page Quality/OFF/cached/background-inference
reading, including inertia and immediate next chapter. Keep display details OFF.
Advance model weights only if normal reading quality/stability is acceptable;
claim performance benefit only after a matched sustained comparison demonstrates
it. If head-only changes yield no energy benefit, record that result rather than
inventing a speedup or blindly changing the backbone/submission topology.
