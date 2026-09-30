# Stabilization and Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Correct reproducible adjacent defects, remove avoidable settings friction, run complete verification, and package auditable source/APK deliverables.

**Architecture:** Keep fixes local to the build wrapper, Manyue settings, reader/filter/cloud lifecycles, and release tooling. Use scripted artifact inspection and a written evidence matrix so unrun device checks are never reported as passing.

**Tech Stack:** Windows batch, PowerShell, Gradle, Android APK tooling, Kotlin/JUnit Jupiter, Markdown audit documents.

**Spec:** `docs/superpowers/specs/2026-09-19-reader-metadata-r18-cloud-favorites-design.md`

## Global Constraints

- Do not perform unrelated refactors or add speculative features.
- Fix only reproducible bugs discovered in the touched flows.
- Do not mark an Android-device check passed without device evidence.
- Release archives exclude `.git`, `.gradle`, `.kotlin`, `build`, local credentials, signing stores, test caches, and the v0.7.4 reference application.
- User-facing artifacts are written only to `C:\Users\Administrator\Documents\Codex\2026-09-19\ba\outputs`.
- The audit report separates fixed defects, worthwhile follow-ups, and deliberately omitted low-value ideas.

## Review Focus

- The Windows wrapper exits once when Java is missing and preserves normal behavior when Java exists.
- Hidden or disabled Manyue settings do not silently change their stored values.
- Cancelling a cloud/reader job on navigation does not surface as an error toast.
- Release packaging never includes account data, local SDK paths, or keystore material.
- A failed optional APK build is reported explicitly and does not get represented by a stale artifact from an earlier run.

---

### Task 1: Fix the Windows Gradle Wrapper Failure Path

**Files:**
- Modify: `gradlew.bat`
- Create: `tools/tests/Verify-GradlewBat.ps1`

**Interfaces:**
- Consumes: standard Gradle wrapper batch labels and Java discovery.
- Produces: one clean error/exit path and a static regression check.

- [ ] **Step 1: Write a failing static control-flow check**

```powershell
$content = Get-Content -Raw "$PSScriptRoot\..\..\gradlew.bat"
if ($content -notmatch '(?s)ERROR: JAVA_HOME is not set.*?goto fail') {
    throw 'JAVA_HOME failure does not jump to :fail'
}
if ($content -notmatch '(?s)ERROR: JAVA_HOME is set to an invalid directory.*?goto fail') {
    throw 'Invalid JAVA_HOME failure does not jump to :fail'
}
```

- [ ] **Step 2: Run it and confirm current failure**

```powershell
pwsh -NoProfile -File tools/tests/Verify-GradlewBat.ps1
```

Expected: nonzero exit identifying the missing jump.

- [ ] **Step 3: Repair batch labels without changing Gradle arguments**

Add explicit `goto fail` after both Java error blocks. Preserve `:execute`, `:end`, `:fail`, command-line forwarding, and wrapper exit-code behavior.

- [ ] **Step 4: Run static and process-level checks**

```powershell
pwsh -NoProfile -File tools/tests/Verify-GradlewBat.ps1
cmd /d /c "set JAVA_HOME=C:\definitely-missing-java&& gradlew.bat --version" 2>&1
```

Expected: static check passes; process-level check prints one invalid-directory error and exits nonzero without showing a malformed `= 1>&2` path.

- [ ] **Step 5: Commit wrapper fix**

```powershell
git add gradlew.bat tools/tests/Verify-GradlewBat.ps1
git commit -m "fix(build): stop wrapper after Java discovery failure"
```

### Task 2: Make Manyue Settings Contextual

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/reader/settings/GeneralSettingsPage.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueSettingsVisibilityTest.kt`

**Interfaces:**
- Consumes: active Manyue enhancement mode and foldable mode.
- Produces: pure `ManyueSettingsVisibility.forState(enhancementMode, foldMode)` and conditional/disabled settings rows.

- [ ] **Step 1: Locate the actual Manyue settings owner and write failing policy tests**

If the Manyue section remains in `GeneralSettingsPage.kt`, do not move it merely to match the filename above. Test:

```kotlin
@Test fun `off mode hides strength and ai diagnostics`() {
    val visibility = ManyueSettingsVisibility.forState(EnhancementMode.OFF, FoldMode.AUTO)
    assertFalse(visibility.classicStrength)
    assertFalse(visibility.aiStatus)
}

@Test fun `manual fold width appears only in manual mode`() {
    assertTrue(ManyueSettingsVisibility.forState(EnhancementMode.CLASSIC, FoldMode.MANUAL).manualWidth)
    assertFalse(ManyueSettingsVisibility.forState(EnhancementMode.CLASSIC, FoldMode.AUTO).manualWidth)
}
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.manyue.ManyueSettingsVisibilityTest"
```

Expected: compilation fails because the policy is absent.

- [ ] **Step 3: Implement contextual rows without resetting preferences**

Hide classic strength outside CLASSIC mode, hide AI runtime/status outside AI mode, and show manual width only for MANUAL fold mode. Keep stored values intact so switching modes restores the user's prior choice. Add one-line disabled explanations where hiding would make the parent choice unclear.

- [ ] **Step 4: Run Manyue tests, compile, and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.manyue.*" :app:compileDebugKotlin
git add app/src/main/java/eu/kanade/presentation/more/settings app/src/test/java/eu/kanade/tachiyomi/ui/reader/manyue
git commit -m "fix(settings): show only relevant Manyue controls"
```

### Task 3: Run a Targeted Defect Audit

**Files:**
- Create: `docs/MANYUE_V5_AUDIT_2026-09-19.md`
- Modify only when a defect is reproduced: reader, adult filter, cloud registry/paging, connector, or lifecycle files covered by the approved spec
- Add one focused regression test beside every production fix

**Interfaces:**
- Consumes: implemented reader, R18, cloud, and wrapper flows.
- Produces: reproducible defect records with command/output evidence and focused fixes.

- [ ] **Step 1: Audit cancellation and stale-result paths**

Run focused tests repeatedly and inspect jobs for `CancellationException` handling:

```powershell
1..5 | ForEach-Object { .\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.*" --tests "eu.kanade.tachiyomi.ui.browse.cloud.*" }
rg -n "catch \(.*Exception|catch \(.*Throwable" app/src/main/java/eu/kanade/tachiyomi/ui/reader app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud cloud-connectors
```

For each broad catch, confirm cancellation is rethrown before error mapping.

- [ ] **Step 2: Audit filtering and target-source races**

Exercise extension reload, source uninstall, R18 toggle during paging, and item click after target removal through unit tests. Any reproduced defect receives a test that fails before its minimal fix and passes after it.

- [ ] **Step 3: Audit connector response boundaries**

For each parser, test empty item arrays, missing optional images/genres, one malformed item among valid items, missing page metadata, invalid JSON, and HTTP authentication/rate-limit status. Page-envelope corruption fails the provider; item corruption skips only that item and records a redacted count.

- [ ] **Step 4: Write the audit document**

Use three sections:

1. `已修复` — defect, impact, root cause, changed test, verification command.
2. `值得后续完善` — concise benefits and maintenance cost.
3. `不建议加入` — always-on overlay, automatic source-quality ranking, cross-source merge, background bidirectional sync, default bulk import, persistent R18 reveal, and the reason each was omitted.

- [ ] **Step 5: Run focused suites and commit audit fixes**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.*" --tests "eu.kanade.tachiyomi.ui.adult.*" --tests "eu.kanade.tachiyomi.ui.browse.cloud.*" :cloud-connectors:common:testDebugUnitTest :cloud-connectors:copymanga:testDebugUnitTest :cloud-connectors:jm:testDebugUnitTest :cloud-connectors:picacg:testDebugUnitTest
git diff --check
git add docs/MANYUE_V5_AUDIT_2026-09-19.md app source-api cloud-connectors
git commit -m "fix: resolve targeted reader and cloud regressions"
```

If no additional production defect is reproduced, commit only the audit document with message `docs: record targeted v5 audit`.

### Task 4: Full Automated Verification

**Files:**
- Modify: `FINAL_CODE_AUDIT.md`
- Modify: `KNOWN_LIMITATIONS.md`
- Modify: `DEVICE_TEST_CHECKLIST.md`

**Interfaces:**
- Consumes: all completed feature commits and a configured Java 17/Android SDK toolchain.
- Produces: command-by-command pass/fail evidence without inferred success.

- [ ] **Step 1: Record toolchain versions**

```powershell
java -version
.\gradlew.bat --version
$env:ANDROID_HOME
```

Expected: Java 17-compatible runtime, Gradle wrapper startup, and a valid Android SDK path. If unavailable, record the exact missing prerequisite before changing the environment.

- [ ] **Step 2: Run formatting and static checks**

```powershell
.\gradlew.bat spotlessCheck lintDebug
```

Expected: successful exit. Record any existing unrelated warning separately; do not suppress a new warning from touched files.

- [ ] **Step 3: Run all unit tests**

```powershell
.\gradlew.bat testDebugUnitTest
```

Expected: successful exit with no failed test task.

- [ ] **Step 4: Assemble main app and connectors**

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleRelease :cloud-connectors:copymanga:assembleDebug :cloud-connectors:jm:assembleDebug :cloud-connectors:picacg:assembleDebug
```

Expected: main debug/release candidates plus three debug connector APKs. If release signing is unavailable, retain the successful debug build and state the signing limitation exactly.

- [ ] **Step 5: Inspect APK identity and contents**

Use Android SDK `apkanalyzer` or `aapt2 dump badging` to verify each package name, version, extension class metadata, content warning, and absence of reference-app classes. Use `jar tf` to verify connector APKs contain only their provider/common runtime and expected resources.

- [ ] **Step 6: Update verification documents**

Record exact commands, timestamps, exit status, APK SHA-256, and device-only checks. Preserve `需要用户真机操作` for any check not executed on hardware.

- [ ] **Step 7: Commit verification evidence**

```powershell
git add FINAL_CODE_AUDIT.md KNOWN_LIMITATIONS.md DEVICE_TEST_CHECKLIST.md
git commit -m "docs: record final verification evidence"
```

### Task 5: Package Clean Deliverables

**Files:**
- Create: `tools/package-manyue-v5.ps1`
- Create in outputs: main APK, three connector APKs, audit report, SHA-256 manifest, and final source ZIP

**Interfaces:**
- Consumes: verified build outputs and tracked source tree.
- Produces: user-facing artifacts under the approved output directory only.

- [ ] **Step 1: Write a packaging script with an explicit allowlist**

The script accepts `-RepositoryRoot`, `-OutputDirectory`, and optional `-ProbeOnly`, then resolves both paths to absolute paths. A real packaging run requires the output directory to equal:

```text
C:\Users\Administrator\Documents\Codex\2026-09-19\ba\outputs
```

`-ProbeOnly` permits a temporary output directory but performs no copy. A real run copies only APKs produced by the current build, `docs/MANYUE_V5_AUDIT_2026-09-19.md`, and a ZIP created from `git ls-files`. The ZIP therefore excludes untracked credentials and build caches. It must explicitly reject tracked paths named `.git`, `keystore.properties`, `local.properties`, `*.jks`, `*.keystore`, or the v0.7.4 reference tree.

- [ ] **Step 2: Test the script in a temporary directory first**

```powershell
$probe = Join-Path $env:TEMP 'manyue-v5-package-probe'
New-Item -ItemType Directory -Force -Path $probe | Out-Null
pwsh -NoProfile -File tools/package-manyue-v5.ps1 -RepositoryRoot (Get-Location) -OutputDirectory $probe -ProbeOnly
Get-ChildItem $probe -Recurse | Select-Object FullName,Length
```

Expected: the probe lists intended files without modifying the real output directory and reports every exclusion check.

- [ ] **Step 3: Generate final artifacts**

```powershell
pwsh -NoProfile -File tools/package-manyue-v5.ps1 -RepositoryRoot (Get-Location) -OutputDirectory 'C:\Users\Administrator\Documents\Codex\2026-09-19\ba\outputs'
```

Expected filenames:

```text
ManyueNext-v5-enhanced-main.apk
ManyueNext-cloud-copymanga.apk
ManyueNext-cloud-jm.apk
ManyueNext-cloud-picacg.apk
ManyueNext-v5-audit.md
ManyueNext-v5-SHA256SUMS.txt
ManyueNext-Mihon-Fork-Source-v5-enhanced.zip
```

- [ ] **Step 4: Inspect final ZIP and hashes**

```powershell
$zip = 'C:\Users\Administrator\Documents\Codex\2026-09-19\ba\outputs\ManyueNext-Mihon-Fork-Source-v5-enhanced.zip'
tar -tf $zip | rg -i '(^|/)(\.git|build|\.gradle|\.kotlin)(/|$)|keystore|local\.properties|v0\.7\.4'
Get-FileHash 'C:\Users\Administrator\Documents\Codex\2026-09-19\ba\outputs\*' -Algorithm SHA256
```

Expected: forbidden-path search returns no matches; computed hashes match the manifest.

- [ ] **Step 5: Commit packaging script and verify clean state**

```powershell
git add tools/package-manyue-v5.ps1
git commit -m "build: package Manyue v5 deliverables"
git status --short
```

Expected: clean source tree. Output artifacts remain outside the repository and are not committed.
