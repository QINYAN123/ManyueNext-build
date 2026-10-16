# Quality2 v86 cache/display candidate

Base: v85 e6c9c20 (bottom-visible AI head, all visible owners protected, UI progress separate). v84 warm policy inherited. Original quality/native2 assets, Native libraries and sampling shaders unchanged; no weights/training or output-scale change.

## Cached AI must not wait for new-inference permission

The single worker previously tested thermal/bad-frame/pacer gates before looking for the disk AI cache. A resolved physical model now gets one metadata preflight before those gates. A hit can complete without new Native inference. A miss or failed probe retains all gates and is memoized, avoiding disk reads every80ms. Unbound Hybrid retains its physical-model selection/identity order.

After claim a hit is atomically revalidated and pinned through process completion. Evicted hits become misses and are requeued through both gates, rather than starting new inference under a stale hit exemption. Existing generation/cancellation checks, READY metadata/listeners/coverage, and display-time decode/upload leases remain. Cache hits are not proof of drawing. SEVERE does not become permission for new Native work.

## Bound next-page preparation behind visible work

Same-APK v85 hot-cache three-pair trial: first AI submission on first content frame33/36 ON vs9/36 OFF. This is useful evidence for preparing ahead. Pair3 ReaderSurface post-to-presentP95 increased6ms against a prospective5ms limit, so unconditional preparation was not accepted. Startup pre-jump pages and thermal phases differ; no energy/scanout proof. All Native counts0; this is not inference throughput validation.

The new experimental admission waits until all current visible owners have submitted their correct current-generation AI image before starting one offscreen neighbor. Current/bottom AI and normal Native pre-read continue through scrolling. The final visible submission directly reevaluates admission; no perpetual redraw polling. A prepared page entering the viewport retains true visibility during retirement/reset. Stable blocked frames do not repeatedly cancel/restart preparation. This policy remains benchmark-toggleable and defaultOFF until prospective device comparisons pass.

## Validation scope

New tests cover cache memoization, invalidated hit restoration of gates, Hybrid identity, READY/listener/coverage, cancelled hits, and preparation admission/withdrawal/current-visibility/reset/chapter boundaries. Host tests and APK identity are recorded separately from device validation. v85 results do not become v86 PASS. No claim of reduced per-image MAC, temperature, watts or sustained optimal energy. Thermal/current-Native/multi-page device acceptance remains required.

ARM64 benchmark app.mihon.benchmark; same signing certificate supports data-preserving upgrade. Keep user .722 margin, original quality2x and GPU detailOFF; do not restore historical .735/OFF settings or navigate an old book automatically.
