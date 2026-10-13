# Reader investigation v75: evidence before visual changes

Baseline: clean `ee3b21301d618637ac0f5e8c682e23a4d61ef4b6` (v74). User still reports
sluggish motion and slight backwards judder after flick release. No physical-motion fix is claimed.
Two existing gpt-6-luna/max agents performed bounded read-only input/tile audits; root reviewed
the code, corrected evidence wording, reproduced the ownership bug and owns all changes.

## Actual continuous-reader pipeline

1. Compose pointer recognizer waits for touch slop. VelocityTracker receives movement/UP;
   pan changes document position. At release, spline decay advances once per frame clock.
2. `ImageViewerContinuousState.scrollBy` maintains double-precision page-relative position
   plus document anchor. Capture converts camera geometry to Float and reports visibility.
3. `ImageViewerState.collect` coalesces invalidations, captures at a frame boundary, and allows
   one CPU render job in flight on a single WebGPU dispatcher. A still-active draw skips capture;
   dirty state is retained. Serialized CPU jobs exclude out-of-order CPU submission, not old
   frames retained by the GPU/display pipeline or uneven presentation intervals.
4. Each visible page draws cached quality tiles, masking their pixels in stencil. Uncovered
   pixels draw from the uploaded source image with a fast sampler. Plain fling suppresses new
   quality-tile generation; an active finger pan does not. The worker rechecks stability.
5. Cached tiles use an integer-rounded shared anchor and nearest 1:1 blit. Source fallback uses
   fractional placement and bilinear sampling in approximate linear light. A path change can
   move a pixel feature by at most roughly half a screen pixel from this rounding alone, even
   when the camera moves monotonically. Example: fallback anchor -0.2 → next frame anchor -0.3,
   newly available tile rounds to 0; that feature moves +0.2 while camera continues forward.
   This is an analytical candidate, not observed device causality. Changing all sampling to
   blur/snap, or removing tiles, can alter ordinary image quality and is not an accepted fix.
6. The renderer acquires a surface texture, encodes, submits, requests present. Its returned
   `Drawn`, coverage callbacks and DISPLAYED records mean correct-image command submission;
   none timestamps physical scanout. Surface FIFO and GPU contention remain unmeasured here.
7. Native Quality produces fixed 2x pixels, encodes/cache-writes, then Android independently
   opens/decodes, generates CPU mips, allocates/uploads GPU textures. Main atomically swaps an
   ImagePage after source/model/generation/visibility validation. Source aspect ratio is preserved.
   Preparation is serial and its upload is NonCancellable to preserve ownership at handoff.
   Stale preparation may finish before validation rejects it; cancellation redesign needs its own
   lifetime proof. Do not remove NonCancellable without replacing that proof.

Relevant files: ImageViewerContinuous.kt, ImageViewerContinuousState.kt, ImageViewerState.kt,
TileRenderer.kt, RenderPage.kt, Image.kt, Mipmap.kt, WebGpuRenderer.kt, WebGpuViewer.kt and bridge.
LOD changes at scale power-of-two boundaries; fixed-scale vertical motion alone does not change LOD.

## What is established, what remains unresolved

- Existing coordinate traces are CPU state/capture/submission evidence. Missing reader-Surface
  FrameTimeline is unavailable evidence, never zero jank. `ready→present` omits earlier GPU work.
- v73 six runs used different forward pages and retained caches, not matched effect comparisons.
  v74 12 injected gestures yielded only five valid page exposures; this is insufficient for
  sustained coverage acceptance. Long images require actual unique-page counts, not swipe counts.
- Added cumulative peak-to-trough reverse detection: seven parser regressions pass. Reanalysis
  of six v73 plus v74 UP→next-DOWN fling windows: 4441 submitted-camera samples, no >1px
  cumulative reverse or height correction. This does not disprove pixel judder/physical latency.
- A narrow fling-stall guard compares page-relative scrollY: advancing exactly one page height
  can leave scrollY equal and falsely stop decay. No present trace establishes this happened.
  Keep this as a separate deterministic-test candidate, not a claimed cause or broad decay rewrite.
- Source mip upload has a proven lifetime bug: current texture was registered only after its
  last cancellable upload chunk. Cancellation/write failure leaked it. Registering immediately
  after allocation fixes ownership without changing uploaded bytes, geometry, shader or pacing.
  Exact production-source host probe: baseline 4/10 clean, six leaked; candidate 10/10 clean with
  exact-once release. Fake driver + real coroutine cancellation; native driver/device-loss lifetime
  remains a device check. Ordinary page scrolling does not itself cancel that source decode.

## Minimal measurement added, no rendering experiment enabled

All new instrumentation runs only when Android ATrace is explicitly enabled. Non-suspending
thread-local sections: frame capture/acquire/submit/present, image mip CPU/createTexture/upload,
source open/decode, AI cache open/decode. Counters: render dispatcher wait, skipped busy frames,
chunk bytes, existing tile GPU timestamps/size, fully tiled/fallback page counts and tile snap error.
Tile GPU timestamps already existed for worker pacing: this adds a trace counter, no query,
map/readback/pump or new GPU work. Fallback counts include partial-tile pages, not AI coverage.
Phase spans measure wall duration including scheduling/blocking, not exclusive CPU instructions.
Do not wrap suspending work in thread-local sections or turn these figures into physical FPS.
Tracing is off in ordinary reading; no network or continuous exported logs.

## Next matched workload, chosen before a performance candidate

First establish a v75 diagnostic baseline; the upload ownership fix is a correctness candidate,
not a motion-performance candidate. Use normal retained caches, classify first reads/revisits,
and record actually decoded source geometry/model/output identity. Never manufacture all-cold
conditions or compare already cached pages to uncached pages as a speedup.

1. Same procedural or permitted static long image, stable scale, AI OFF/GPU detail OFF; slow drag,
   fast flick, then >=2s untouched inertia tail. Separate source fallback/quality-tile path transitions
   from camera reversal and surface-acquire/dispatcher stalls. Repeat three times with actual frame
   presentation evidence if available. ADB is a reproducible injection, not a real-finger equivalence.
2. Same chapter material/path, Quality2x vs OFF, mixed cache: immediate next chapter, ordinary
   forward reading, fast battle passages, text pauses, limited reread. At least 20 unique valid page
   exposures; 20 gestures on a handful of long pages do not qualify. Observe >=20-page continuous
   workload; finite duration exhaustion means incomplete, not pass.
3. Keep sustained-throughput/coverage and motion tests separate. For speed greater than native
   completion throughput, missed pages are capacity loss; extra queue length cannot remove it.
   Count failures/rapidly passed pages in the coverage denominator; Δ=1s remains explicit. First
   entry, timely and ever-before-exit coverage stay separate, with current correct image identity.
4. Before any motion candidate, measure repeated baseline noise and freeze meaningful benefit and
   regression gates. Match actual refresh, same inner/outer panel/comfort scale/build/tracing, cache
   state, similar power/temperature. No fixed thermal preconditioning fiction; log the real state.
   Compare three counterbalanced pairs; missing presentation evidence keeps motion acceptance open.
5. First isolate measured dispatcher/acquire/upload stalls or observed pixel path changes. Never
   simultaneously change inertia, buffer mode, tile sampling, AI concurrency and prefetch. A candidate
   without benefit beyond noise is withdrawn; prior Mailbox/drag-replay/prewarm trials remain OFF.

No ordinary color/BW reduction, Parthas global grayscale/blur, new detail stack, model/Native update,
fold geometry or progress-semantic change in this revision. Phone was online but outside the reader
during host work; no current-version device scrolling/folding/physical feedback is claimed.
Quality2 sustained/physical-motion/long-term acceptance remains incomplete. Download, JM/R18 and
broader functional regression retain their prior open statuses; building this revision cannot clear them.
