# Quality2 v92: independent cache-hit completion and resident phase timing

Control: v91, bd52aac150d4a07e11668721a0b63c1572bcf85a. Control ARM64 APK SHA-256:
520678b4f30a88971c2bb3c51e1ddde7e08f183acd9fa03bac125bb6d2e0514d.

## Problem and change

The single Native worker also resolved disk-cache hits. A cached page arriving while another
page was in Native could wait for that entire call. Earlier v85 evidence reported cache-hit
queue waits around 3.3–3.5 seconds during an active call; v91 fixed recent same-session token
reuse but a newly registered request could still wait for disk reuse.

The existing request-factory IO path now exclusively claims an **unqueued**, unprocessed
token with a fixed physical model, pins its exact cache key outside the scheduler monitor,
and completes a hit through the existing cache branch. It never replaces activeRequest,
starts Native, or yields/cancels unrelated Native work. A miss releases the claim and enters
the unchanged serial queue and foreground/pressure gates. Already queued/processing requests
keep their current owner. Unresolved hybrid requests stay on the serial worker.

Concurrent callers cannot own the same probe. Priority and cancellation paths distinguish
cache-only ownership from Native ownership. Source fingerprint, width, selected/physical
model, namespace, generation and reader-session checks retain their existing semantics.
Cancellation or a generation/session change during the probe cannot publish a valid result.
A pinned lease is retained through cache completion and released on every path.

This removes a cache-hit queue dependency, **not** neural MACs or the cost of an uncached page.
It does not by itself prove lower device power/temperature or solve new-chapter throughput.

Resident phase telemetry records process start, READY wait, request write and result wait
using monotonic durations only when diagnostics installs a callback. Warm resident requests
omit startup phases. RESULT_WAIT success means a framed read returned; later response ID,
status, output and dimension validation may still fail. It includes decode/inference/encode
and communication, and must not be labelled pure GPU time. Phase records are bounded by the
existing recorder, not emitted on each polling iteration. Observer exceptions cannot change
protocol/cancellation behavior.

## Unchanged image computation and evidence limits

No weights, model graph, Native binary, ncnn library, precision, tile/chunk configuration,
output codec, shader, sampling, thermal policy or enhancement setting changes. Package
verification must compare those assets byte for byte with v91. This is not new training.

Host tests cover a hit with another Native owner, concurrent queue/probe callers, memoized
miss fallback, ineligible queued/processing/hybrid/stale tokens, cancellation/setting changes
during pin, the real disk-cache entrypoint, and deterministic resident phase semantics.
Actual test/build results and current device scope belong in the packaged verification and
device report; this source document does not pre-claim their outcomes.

For device acceptance retain mixed caches, the user's per-book margin and known progress;
use bounded swipes and distinguish timely visible drawing from AI_READY. A hit completing
before another Native ends proves dependency removal only. Sustained thermal/energy gains
need matched longer experiments and reliable power data; main-window FPS is insufficient
for the independent reader Surface.

## Upstream assessment

- https://github.com/tumuyan/RealSR-NCNN-Android/issues/84 reports phone manga GPU saturation,
  but has no verified solution to copy.
- https://github.com/Tencent/ncnn/wiki/openmp-best-practice recommends avoiding busy waits;
  OMP_NUM_THREADS=1 and OMP_WAIT_POLICY=PASSIVE are already used here.
- https://github.com/Tencent/ncnn/wiki/use-ncnnoptimize-to-optimize-model documents specific
  graph fusions; they cannot be assumed applicable to every per-channel PReLU layer.
- https://github.com/Tencent/ncnn/wiki/vulkan-pipeline-cache addresses pipeline/shader startup,
  not resident per-page neural work. New APIs must not be mixed into the pinned 20241226 ABI.

Previous failed FP16-arithmetic, runtime-upgrade, tile/chunk and large-PNG experiments remain
excluded. A non-WebP compressed-input staging reduction is only a possible small candidate;
decode contribution and exact-pixel equality must be established before any Native change.
