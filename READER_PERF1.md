# Perf1: completed-image display fixes

This build corrects two sources of delay between AI completion and visible display.

- A bound, attached but offscreen holder retains its encoded AI result in the cache. It starts staged tile decoding only when it intersects the viewport. Ordinary scrolling does not prevent preparation. Cancel/rebind invalidates the wait and prevents the previous page's result from being installed.
- Native inference and prefetch still obey thermal protection. An already decoded visible result uses a separate display gate: fast movement and repeated missed frames can briefly defer it, but thermal status alone cannot hide it indefinitely. Pinch, layout and touch checks remain in the reader.

The original remains visible until a complete base layer is decoded. Replacement still preserves strip height and the existing viewport. The single bounded AI tile worker remains; this change prevents offscreen holders from filling it, rather than adding competing decode threads.

This is a display-chain repair. It does not replace the existing Real-CUGAN/Real-ESRGAN model, remove per-page native cold starts, or prove that inference keeps up with fast reading. The existing 1–2x target-output setting still runs the complete fixed 2x model. GPU display enhancement remains independent.

Version: `0.20.12-perf1-benchmark`, versionCode 40, `app.mihon.benchmark`, ARM64 optimized test build with the existing local test signing certificate.

Validation: enhancement, image-display and metadata unit/Robolectric regressions, including offscreen-to-visible and stale-holder handling, motion/frame gates, and thermal-only ready-result commits. Packaging, signature and source/native/model byte consistency are checked separately. No Honor Magic V2 is connected; phone throughput, FPS, thermal behavior and visual quality are not accepted by host tests.

Phone checks:

1. Compare the same chapter/page/settings against GPU1, including a warm AI cache.
2. During ordinary scrolling, check that an already completed visible page displays without requiring idle.
3. Read quickly into several pages, reverse direction, then return; ensure the original remains visible, no previous-page result appears and cached results can display.
4. Check long-page strip height/position during swaps, pinch gestures and chapter changes.

Light-model work is research at this stage; see `ANDROID_LIGHT_SR_PLAN.md`. It is not bundled as a new selectable model in this APK.
