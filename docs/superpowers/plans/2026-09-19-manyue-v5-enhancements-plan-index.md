# Manyue v5 Enhancements Plan Index

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Coordinate four independently testable implementation plans that add unobtrusive reader metadata, session-only R18 filtering, three-source cloud favorites, and a verified release package.

**Architecture:** Implement the reader and R18 changes first because the Cloud Favorites UI consumes the shared adult-content policy. Build the cloud connector contract and host UI next, then the three provider connectors, and finish with cross-feature regression checks and packaging.

**Tech Stack:** Kotlin 2.4, Android SDK 37.1, Jetpack Compose, Paging 3, Metro DI, OkHttp/Okio, kotlinx.serialization, JUnit Jupiter, Gradle 9.4.

**Spec:** `docs/superpowers/specs/2026-09-19-reader-metadata-r18-cloud-favorites-design.md`

## Global Constraints

- The v5 ManyueNext source is the product base; ManyueHD v0.7.4 is reference material only.
- Existing Keiyoushi and TheNano source APKs, source IDs, and library records must remain installed and unchanged.
- Reader page/resolution metadata is visible only while translucent reader controls are visible.
- R18 reveal state is process-local and is never written to preferences, saved state, database, backup, or connector settings.
- Cloud favorites are read-only; remote add/remove and background synchronization are excluded.
- Connector credentials and tokens must never appear in logs, crash reports, exceptions shown to users, or packaged diagnostics.
- Production file edits use `apply_patch`; generated formatting output may be produced by Gradle.
- Final user-facing artifacts are copied only to `C:\Users\Administrator\Documents\Codex\2026-09-19\ba\outputs`.

## Review Focus

- A corrupt or unsupported image must show `原图未知`, never stale dimensions from the previous page.
- Rapid activity recreation must preserve the current process's R18 state, while a genuinely new application process must initialize hidden.
- An untrusted or duplicate cloud connector must not be selected merely because its display name matches.
- A cloud favorite whose target source is missing must not create a connector-owned or orphaned library record.
- Authentication and network failures from one provider must not clear successful data from the other providers.

---

### Task 1: Establish a Local Baseline Repository

**Files:**
- Track: all files currently present in the extracted `mihon_fork` directory

**Interfaces:**
- Consumes: the extracted v5 source archive with no `.git` directory.
- Produces: a local Git baseline from which task commits and an isolated execution worktree can be created.

- [ ] **Step 1: Confirm the source archive has no repository metadata and record a content inventory**

Run:

```powershell
Test-Path .git
rg --files | Sort-Object | Set-Content "$env:TEMP\manyue-v5-baseline-files.txt"
(Get-Content "$env:TEMP\manyue-v5-baseline-files.txt").Count
```

Expected: `False`, followed by a positive file count.

- [ ] **Step 2: Initialize a local repository and commit the untouched import**

Run:

```powershell
git init -b work
git add --all
git commit -m "chore: import ManyueNext v5 source"
```

Expected: one root commit and a clean `git status --short`.

- [ ] **Step 3: Verify the baseline contains the approved spec and all four plans**

Run:

```powershell
git ls-files docs/superpowers/specs docs/superpowers/plans
git status --short
```

Expected: the approved spec plus this index and the four linked plans; no working-tree changes.

### Task 2: Execute Reader Metadata Plan

**Files:**
- Read and execute: `docs/superpowers/plans/2026-09-19-reader-source-metadata.md`

**Interfaces:**
- Consumes: root baseline commit.
- Produces: `SourceImageInfo` lifecycle, original-dimension capture in every active viewer, and menu-only metadata rendering.

- [ ] **Step 1: Complete every checkbox in the reader plan**

Run the plan's focused tests after each task and its aggregate command at the end:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.*"
```

Expected: all reader metadata tests pass.

### Task 3: Execute Session R18 Plan

**Files:**
- Read and execute: `docs/superpowers/plans/2026-09-19-session-r18-filtering.md`

**Interfaces:**
- Consumes: root baseline; independent of reader metadata code.
- Produces: `AdultContentSessionState`, classification/resolution services, and shared Library/Browse controls that Cloud Favorites will consume.

- [ ] **Step 1: Complete every checkbox in the R18 plan**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.adult.*"
```

Expected: all adult-content policy and integration tests pass.

### Task 4: Execute Cloud Favorites Plan

**Files:**
- Read and execute: `docs/superpowers/plans/2026-09-19-cloud-favorites-platform.md`

**Interfaces:**
- Consumes: `AdultContentSessionState` and `AdultContentClassifier` from Task 3.
- Produces: source API contract, connector registry, unified Browse tab, and CopyManga/JM/PICACG connector APKs.

- [ ] **Step 1: Complete every checkbox in the cloud plan**

Run:

```powershell
.\gradlew.bat :source-api:testDebugUnitTest :app:testDebugUnitTest :cloud-connectors:common:testDebugUnitTest :cloud-connectors:copymanga:testDebugUnitTest :cloud-connectors:jm:testDebugUnitTest :cloud-connectors:picacg:testDebugUnitTest
```

Expected: all source API, host registry/UI policy, and provider contract tests pass.

### Task 5: Execute Stabilization and Delivery Plan

**Files:**
- Read and execute: `docs/superpowers/plans/2026-09-19-stabilization-and-delivery.md`

**Interfaces:**
- Consumes: completed Tasks 2 through 4.
- Produces: wrapper fix, targeted UX cleanup, full verification evidence, APKs, audit report, and final source archive.

- [ ] **Step 1: Complete every checkbox in the stabilization plan**

Run its final verification command exactly as written and inspect every generated artifact before copying it to `outputs`.

- [ ] **Step 2: Create a final integration commit**

Run:

```powershell
git status --short
git log --oneline --decorate -12
```

Expected: clean working tree and a reviewable sequence of focused commits. Do not create an empty commit.

## Execution order

1. Baseline repository setup.
2. Reader metadata plan.
3. Session R18 plan.
4. Cloud Favorites plan.
5. Stabilization and delivery plan.

Reader metadata and R18 do not depend on each other, but the listed sequence keeps review simple. Cloud Favorites must follow R18 because its preview and provider pages use the same session state and classifier. Packaging is last because it validates and collects all four APK families together.
