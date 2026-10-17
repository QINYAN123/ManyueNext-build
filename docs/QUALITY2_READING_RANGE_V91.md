# Quality2 v91: visible re-entry and recent-result reuse

Fixed baseline: v90 `f967bc58853bad68959b16e98b1a3239fc3fd153`; ARM64 benchmark APK SHA-256 `39a5c493921cb97ddfdd28338ef7a70d01c5923989b7cb95d7e98fcf6f37164c`.

## Code-proven defects

A page leaving the reading window can have its token cancelled before its Main-thread listener receives the cancellation. If the page re-enters meanwhile, it still has that token and cannot request again. The later cancellation used to clear the token and retain the outside-window detail without scheduling a retry. A parked renderer need not send another visibility update. The candidate coalesces one deferred retry for an actually visible, source-ready, live page and waits for any active source-start coroutine to finish. It validates source fingerprint, generation, mode, reader lifetime, token and listener identity. Hide/reset/destroy cancel deferred work; permanent failures do not retry. A second cancellation in the same visible episode does not spin: another automatic retry needs a new visible episode. Ordinary cancellation of offscreen pages remains expected.

Request registration included the reader-owner epoch in its key, but recent-token lookup omitted it, so lookup always missed registered entries. Active requests were still deduplicated by the later registration path. Recent completed results instead took a new request and serial-worker disk-cache route unnecessarily. Both now share one key builder, including the epoch. Old-reader requests remain isolated; valid disk-cache reuse across readers is unchanged. This does not remove all source reads/fingerprinting and is not a reduction in neural work.

## Verification protocol and limits

Deterministic tests cover cancellation delivered after re-entry, cancellation while source start is active with no further gesture, coalesced visibility/cancellation retry, hide/reset, callback identity, retry limit, ready/permanent-failure gates, active/completed-token reuse, stale owner and mismatched request identities.

Device diagnostics retain the user's 0.725 border ratio, quality native2_head3 2×, detail OFF, severe-head experiment OFF, and existing mixed caches. Per book, small 500–850px swipes, short backreads and text pauses stay within initial page -2/+4 and away from chapter end. Source availability and navigation failures must be reported separately. UI polling adds overhead; these are functional traces, not matched throughput/energy or independent reader-Surface frame acceptance. Cache warmth and source dimensions differ between books and successive runs. Results ready before observation are not evidence of newly accelerated inference.

Model weights, Native binaries, shaders, output size, system thermal behavior and user settings are unchanged. Long thermal waits and the conditional single-worker inner-wait priority risk remain separate investigations. This candidate does not claim to solve all occasional slow rendering or sustained energy use. Host/package and current device results belong to separate delivery reports; no formal performance PASS follows from a successful build.

ARM64 benchmark retains `app.mihon.benchmark` and its existing signing certificate; same-package upgrades preserve data. It is a test build, distinct from other application IDs/certificates.
