# Manyue Final Stabilization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the existing Manyue fork with safe native AI execution, real current/+3 scheduling, stale-result protection, foldable width correctness, bounded image memory, reproducible builds, and final audit artifacts.

**Architecture:** Preserve the current Reader hooks and Manyue package. Add small pure policies for testable scheduling/admission/result validation, then connect them to the existing Pager, Webtoon, runtime, and settings lifecycle. No Mihon storage, download, extension, or source APIs are redesigned.

**Tech Stack:** Kotlin, Android SDK, Compose, coroutines, Mihon Reader views, Gradle, ARM64 ELF/NCNN.

**Spec:** `docs/superpowers/specs/2026-09-17-manyue-final-stabilization-design.md`

## Global Constraints

- Keep Mihon database, download, extension, and manga-source systems unchanged.
- Keep MangaDex popular-list behavior out of scope.
- Preserve OFF as the no-enhancement fast path.
- ARM64 Android is the native AI target.
- Never claim device acceptance without an attached device and observed evidence.

---

### Task 1: Establish baseline and testable policies

**Files:**
- Modify: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueFoldableWidthPolicyTest.kt`
- Modify: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyuePrefetchPolicyTest.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueAiSafetyPolicyTest.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueAiSafetyPolicy.kt`

**Interfaces:**
- Produces: `ManyueAiSafetyPolicy.prefetchPlan(currentIndex, pageCount)`, `priorityFor(pageIndex, currentIndex)`, `isPixelBudgetSafe(width, height, maxPixels)`, and `isCompletionCurrent(...)`.

- [ ] Run `gradlew.bat :app:testDebugUnitTest` and save the untouched baseline output.
- [ ] Add failing tests with hand-derived results: page 5 plans `5/100,6/30,7/20,8/10`; an out-of-window page is rejected; mode/generation/token mismatches reject completion; even Golden Reference samples select the lower median; AUTO may exceed 2344 when screen and samples require it.
- [ ] Run the targeted Manyue tests and confirm the new assertions fail for the expected missing/wrong behavior.
- [ ] Add the minimal pure policy implementation and correct foldable median/AUTO rules.
- [ ] Re-run targeted Manyue tests and confirm they pass.

### Task 2: Harden native runtime and bounded image processing

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueAiRuntime.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueAiUpscaler.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueEnhancementCache.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueClassicEnhancer.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueReaderHook.kt`

**Interfaces:**
- Consumes: Task 1 pixel-budget policy.
- Produces: cancellable native execution, atomic model extraction, sequential cached-part decode, deterministic temporary cleanup, and listener/request cleanup.

- [ ] Add failing policy tests for predicted 2x pixel overflow and invalid zero dimensions.
- [ ] Verify RED with the targeted unit-test command.
- [ ] Enable legacy JNI packaging; validate executable/dependency/model/input/output paths; redirect process output; poll for cancellation and timeout; destroy and reap cancelled processes.
- [ ] Reject unsafe predicted outputs before native launch. Decode cached parts sequentially and recycle each input bitmap. Recycle classic intermediate bitmaps when ownership permits.
- [ ] Delete per-request temporary files/directories in `finally`, remove delivered listeners, and bound diagnostic text.
- [ ] Run targeted tests and `:app:compileDebugKotlin`.

### Task 3: Enforce session identity and real current/+3 work

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueRuntimeState.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyuePageBridge.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyuePrefetchManager.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyuePagePrefetcher.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonPageHolder.kt`

**Interfaces:**
- Consumes: Task 1 plan/priorities/result guard and Task 2 cancellable requests.
- Produces: `ManyueRuntimeState.sessionGeneration`, viewer-owned prefetch windows, and callback validation against identity/mode/generation/token.

- [ ] Add failing tests for token replacement cancellation bookkeeping and completion rejection after OFF/session change.
- [ ] Verify RED with the targeted tests.
- [ ] Make mode changes increment generation and reset queued/running work. Make `register` cancel a prior token for the same chapter/page slot.
- [ ] Proactively load and enqueue current/+1/+2/+3 chapter pages with exact priorities, using `ReaderPage.index`; cancel viewer-owned coroutine jobs and native tokens on page-window, chapter, mode, and viewer lifecycle changes.
- [ ] Require token, identity, mode, session generation, and READY to match immediately before `setImage`.
- [ ] Re-run targeted tests and Kotlin compilation.

### Task 4: Correct settings and foldable application

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/reader/settings/GeneralSettingsPage.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueFoldableController.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt`

**Interfaces:**
- Produces: visible OFF/CLASSIC/AI×2/AI×2+CLASSIC controls, AUTO/FULL/MANUAL fold controls, initial configuration seeding, width restoration, and Webtoon anchor-preserving relayout.

- [ ] Extend foldable tests for outer-screen restoration decisions and lower-median results, then verify RED.
- [ ] Keep the existing UI section and replace fixed `2344` with MANUAL plus a width slider bound to `manyueFoldableTargetWidth`.
- [ ] Initialize the controller from the activity's current configuration. On changes, reapply to Pager/Webtoon descendants; use `MATCH_PARENT` for non-inner/FULL.
- [ ] Capture Webtoon first-visible position/top offset before width reapplication and restore it after layout.
- [ ] Re-run targeted tests and Kotlin compilation.

### Task 5: Final audit, builds, packaging inspection, and deliverables

**Files:**
- Create: `FINAL_CODE_AUDIT.md`
- Create: `DEVICE_TEST_CHECKLIST.md`
- Create: `KNOWN_LIMITATIONS.md`
- Create under output directory: build logs, source ZIP, and Universal APK.

**Interfaces:**
- Consumes: all prior tasks.
- Produces: traceable production call-chain documentation and user-installable artifacts.

- [ ] Run `gradlew.bat :app:compileDebugKotlin`, `gradlew.bat :app:testDebugUnitTest`, and `gradlew.bat :app:assembleDebug` separately while capturing complete stdout/stderr and exit codes.
- [ ] Inspect the Universal APK for `lib/arm64-v8a/libmanyue_realesr.so`, `libncnn.so`, `x2.bin`, and `x2.param`; record compression method, sizes, SHA-256, ELF interpreter, PIE flag, and `libncnn.so` dependency.
- [ ] Write the three requested documents, marking every unavailable device observation as `需要用户真机操作`.
- [ ] Copy the Universal APK and logs to the output directory and create the final source ZIP without transient Gradle/build caches.
- [ ] Verify artifact hashes, sizes, document presence, and final source contents before reporting completion.
