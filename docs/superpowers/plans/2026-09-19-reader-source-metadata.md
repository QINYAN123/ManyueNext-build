# Reader Source Metadata Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display the current page and pre-processing source-image dimensions only inside visible reader controls for Pager, Webtoon, and WebGPU viewers.

**Architecture:** Store source-image state on `ReaderPage`, decode bounds from raw bytes before Manyue or viewer transformations, and let `ReaderViewModel` collect only the selected page's state. Keep formatting and visibility policy pure so stale-state and menu-visibility behavior are unit tested without an Android device.

**Tech Stack:** Kotlin, StateFlow, Android `BitmapFactory.Options.inJustDecodeBounds`, Okio, Jetpack Compose, JUnit Jupiter.

**Spec:** `docs/superpowers/specs/2026-09-19-reader-metadata-r18-cloud-favorites-design.md`

## Global Constraints

- Display source asset dimensions, never split, cropped, rotated, downsampled, HDR, or AI-output dimensions.
- Do not allocate a second full bitmap to read bounds.
- Clear previous metadata immediately when the selected page changes.
- Hide the entire metadata line when `menuVisible` is false or `showPageNumber` is false.
- Keep existing page numbering and dual-page navigation semantics.
- Add English base strings plus Simplified and Traditional Chinese strings; other locales use the base fallback.

## Review Focus

- A zero-sized or undecodable image becomes `Unavailable`, not `Available(0, 0)`.
- Two logical split pages sourced from one image retain the same original dimensions.
- A late metadata update from a previously selected page cannot alter current reader state.
- WebGPU captures decoder dimensions before crop or spread composition.
- Manyue prefetch may populate metadata early but cannot overwrite a valid value with a decode failure.

---

### Task 1: Add Source Image State and Bounds Decoder

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/metadata/SourceImageInfo.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/metadata/SourceImageBoundsDecoder.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderPage.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/metadata/SourceImageBoundsDecoderTest.kt`
- Test resource: `app/src/test/resources/reader/metadata/source-3x2.png`

**Interfaces:**
- Consumes: original encoded bytes as `BufferedSource` or `ByteArray`.
- Produces: `sealed interface SourceImageInfo { Loading; Available(width: Int, height: Int); Unavailable }`, `SourceImageBoundsDecoder.decode(BufferedSource): SourceImageInfo`, `ReaderPage.sourceImageInfo: StateFlow<SourceImageInfo>`, and `ReaderPage.updateSourceImageInfo(SourceImageInfo)`.

- [ ] **Step 1: Add a tiny deterministic image fixture and failing decoder tests**

Create the PNG fixture through the existing test-resource mechanism, then add tests with these assertions:

```kotlin
class SourceImageBoundsDecoderTest {
    @Test fun `reads encoded dimensions without consuming caller buffer`() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/reader/metadata/source-3x2.png"))
            .use { it.readBytes() }
        val buffer = okio.Buffer().write(bytes)

        assertEquals(SourceImageInfo.Available(3, 2), SourceImageBoundsDecoder.decode(buffer))
        assertEquals(bytes.size.toLong(), buffer.size)
    }

    @Test fun `invalid bytes are unavailable`() {
        assertEquals(
            SourceImageInfo.Unavailable,
            SourceImageBoundsDecoder.decode(okio.Buffer().writeUtf8("not an image")),
        )
    }

    @Test fun `zero dimensions are unavailable`() {
        assertEquals(SourceImageInfo.Unavailable, SourceImageBoundsDecoder.fromBounds(0, -1))
    }
}
```

- [ ] **Step 2: Run the tests and confirm the contract does not exist yet**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.SourceImageBoundsDecoderTest"
```

Expected: compilation fails because `SourceImageInfo` and `SourceImageBoundsDecoder` are absent.

- [ ] **Step 3: Implement the model, non-consuming decoder, and monotonic page update**

Use these exact public shapes:

```kotlin
sealed interface SourceImageInfo {
    data object Loading : SourceImageInfo
    data class Available(val width: Int, val height: Int) : SourceImageInfo
    data object Unavailable : SourceImageInfo
}

object SourceImageBoundsDecoder {
    fun decode(source: BufferedSource): SourceImageInfo
    internal fun fromBounds(width: Int, height: Int): SourceImageInfo
}
```

`decode` must call `BitmapFactory.decodeStream(source.peek().inputStream(), null, options)` with `inJustDecodeBounds = true`. `ReaderPage.updateSourceImageInfo` accepts `Available` at any time, accepts `Unavailable` only while the current value is `Loading`, and never downgrades `Available` to `Unavailable`.

- [ ] **Step 4: Run decoder and page-state tests**

Add a test that applies `Unavailable` after `Available(3, 2)` and asserts the available value remains. Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.*"
```

Expected: all tests pass.

- [ ] **Step 5: Commit the metadata primitive**

```powershell
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/metadata app/src/main/java/eu/kanade/tachiyomi/ui/reader/model/ReaderPage.kt app/src/test/java/eu/kanade/tachiyomi/ui/reader/metadata app/src/test/resources/reader/metadata
git commit -m "feat(reader): model original image dimensions"
```

### Task 2: Capture Dimensions Before Every Viewer Transformation

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/metadata/SourceImageCaptureOrder.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt:145-170`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonPageHolder.kt:188-208`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webgpu/WebGpuViewer.kt:1110-1160`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue/ManyueReaderPrefetcher.kt:88-104`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/metadata/SourceImageCaptureOrderTest.kt`

**Interfaces:**
- Consumes: `SourceImageBoundsDecoder.decode` and `ReaderPage.updateSourceImageInfo` from Task 1.
- Produces: original metadata from Pager, Webtoon, WebGPU, and Manyue prefetch without changing each viewer's processed stream.

- [ ] **Step 1: Write failing capture-order tests around a pure helper**

Add `SourceImageCaptureOrder.kt` with a not-yet-implemented function contract and test the ordering explicitly:

```kotlin
internal object SourceImageCaptureOrder {
    fun <T> captureThenTransform(
        raw: BufferedSource,
        publish: (SourceImageInfo) -> Unit,
        transform: (BufferedSource) -> T,
    ): T
}

@Test fun `publishes raw dimensions before transform runs`() {
    val events = mutableListOf<String>()
    SourceImageCaptureOrder.captureThenTransform(
        raw = fixtureBuffer(),
        publish = { events += "publish:$it" },
        transform = { events += "transform"; Unit },
    )
    assertEquals(listOf("publish:Available(width=3, height=2)", "transform"), events)
}
```

- [ ] **Step 2: Run the focused test and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.SourceImageCaptureOrderTest"
```

Expected: compilation or assertion failure because the helper has no implementation.

- [ ] **Step 3: Implement the helper and integrate Pager/Webtoon before `process`**

Pager must change from processing the newly read buffer immediately to this sequence:

```kotlin
val encoded = Buffer().readFrom(streamFn())
val raw = SourceImageCaptureOrder.captureThenTransform(
    raw = encoded,
    publish = item::updateSourceImageInfo,
) { process(item, it) }
```

Webtoon uses the same sequence with `process(it)`. Move `ManyueFoldableController.recordSample` to consume `SourceImageInfo.Available`; remove the existing post-process bounds decode so foldable sampling and visible metadata share the true source dimensions.

- [ ] **Step 4: Integrate Manyue prefetch and WebGPU**

After Manyue prefetch reads its `ByteArray`, decode a temporary `Buffer().write(bytes)` and publish before enhancement. In WebGPU, publish the decoder's first encoded frame width and height immediately after `decodeNext()` and before crop, trim, spread, scale, or GPU upload. Do not replace an earlier valid value with an error.

- [ ] **Step 5: Run capture-order and existing Manyue reader tests**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.*" --tests "eu.kanade.tachiyomi.ui.reader.manyue.*"
```

Expected: all selected tests pass and existing Manyue behavior remains green.

- [ ] **Step 6: Commit capture integration**

```powershell
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer app/src/main/java/eu/kanade/tachiyomi/ui/reader/manyue app/src/main/java/eu/kanade/tachiyomi/ui/reader/metadata app/src/test/java/eu/kanade/tachiyomi/ui/reader/metadata
git commit -m "feat(reader): capture source dimensions before processing"
```

### Task 3: Bind Only the Selected Page to Reader State

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt:474-580,980-1005`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/metadata/SelectedPageMetadata.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/metadata/SelectedPageMetadataTest.kt`

**Interfaces:**
- Consumes: `ReaderPage.sourceImageInfo`.
- Produces: `ReaderViewModel.State.sourceImageInfo: SourceImageInfo` and identity-checked updates for the selected page.

- [ ] **Step 1: Write failing stale-page policy tests**

```kotlin
class SelectedPageMetadataTest {
    @Test fun `new selection clears dimensions immediately`() {
        val state = SelectedPageMetadata(selectedKey = "old", info = SourceImageInfo.Available(3, 2))
        assertEquals(SourceImageInfo.Loading, state.select("new", SourceImageInfo.Loading).info)
    }

    @Test fun `late update from old page is ignored`() {
        val state = SelectedPageMetadata("new", SourceImageInfo.Loading)
        assertEquals(state, state.update("old", SourceImageInfo.Available(99, 99)))
    }
}
```

- [ ] **Step 2: Run the focused test and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.SelectedPageMetadataTest"
```

Expected: compilation fails because `SelectedPageMetadata` is absent.

- [ ] **Step 3: Implement the pure identity guard and ViewModel collection**

Use `chapter.chapter.id` plus `page.index` as the selection key. `onPageSelected` must cancel the previous metadata collection job, update current page and current metadata in one `mutableState.update`, and collect the selected page flow. Every collected update checks the key before mutating `State.sourceImageInfo`.

- [ ] **Step 4: Run stale-page tests and ReaderViewModel compilation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.SelectedPageMetadataTest" :app:compileDebugKotlin
```

Expected: test and compilation pass.

- [ ] **Step 5: Commit selected-page state binding**

```powershell
git add app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt app/src/main/java/eu/kanade/tachiyomi/ui/reader/metadata app/src/test/java/eu/kanade/tachiyomi/ui/reader/metadata
git commit -m "fix(reader): reject stale page metadata"
```

### Task 4: Render Metadata Only Inside Visible Controls

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/metadata/ReaderMetadataPresentation.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/reader/ReaderPageIndicator.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt:254-280`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Modify: `i18n/src/commonMain/moko-resources/zh-rCN/strings.xml`
- Modify: `i18n/src/commonMain/moko-resources/zh-rTW/strings.xml`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/reader/metadata/ReaderMetadataPresentationTest.kt`

**Interfaces:**
- Consumes: `State.currentPage`, `State.totalPages`, `State.menuVisible`, and `State.sourceImageInfo`.
- Produces: `ReaderMetadataPresentation.visible(menuVisible, preferenceEnabled)` and `ReaderMetadataPresentation.text(current, total, info, labels)`; `ReaderPageIndicator` accepts `sourceImageInfo`.

- [ ] **Step 1: Write failing presentation-policy tests**

```kotlin
@Test fun `metadata is hidden whenever menu is hidden`() {
    assertFalse(ReaderMetadataPresentation.visible(menuVisible = false, preferenceEnabled = true))
}

@Test fun `available dimensions format on one compact line`() {
    assertEquals(
        "12 / 38 · 原图 2400 × 3600",
        ReaderMetadataPresentation.text(12, 38, SourceImageInfo.Available(2400, 3600), zhLabels),
    )
}

@Test fun `loading and unavailable have distinct text`() {
    assertEquals("12 / 38 · 原图读取中", ReaderMetadataPresentation.text(12, 38, SourceImageInfo.Loading, zhLabels))
    assertEquals("12 / 38 · 原图未知", ReaderMetadataPresentation.text(12, 38, SourceImageInfo.Unavailable, zhLabels))
}
```

- [ ] **Step 2: Run the presentation tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.ReaderMetadataPresentationTest"
```

Expected: compilation fails because the presentation policy is absent.

- [ ] **Step 3: Implement formatter, translations, and translucent styling**

Keep `ReaderPageIndicator` aligned above the bottom reader controls with a `surfaceContainerHigh` background at partial alpha, rounded shape, compact horizontal padding, and `bodySmall` text. Avoid outlined duplicate text because the control surface supplies contrast.

- [ ] **Step 4: Reverse the overlay condition and remove the always-on branch**

`ReaderActivity.setComposeOverlay` must call the metadata indicator only when:

```kotlin
ReaderMetadataPresentation.visible(
    menuVisible = state.menuVisible,
    preferenceEnabled = showPageNumber,
)
```

No page/resolution composable is emitted while controls are hidden.

- [ ] **Step 5: Run all reader metadata tests, formatting, and debug compilation**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.*" :app:spotlessApply :app:compileDebugKotlin
git diff --check
```

Expected: all tests pass, compilation succeeds, and `git diff --check` prints nothing.

- [ ] **Step 6: Commit the menu-only presentation**

```powershell
git add app/src/main/java/eu/kanade/presentation/reader/ReaderPageIndicator.kt app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt app/src/test/java/eu/kanade/tachiyomi/ui/reader/metadata i18n/src/commonMain/moko-resources
git commit -m "feat(reader): show source metadata in reader controls"
```

### Task 5: Reader Metadata Regression Gate

**Files:**
- Modify: `DEVICE_TEST_CHECKLIST.md`
- Modify: `KNOWN_LIMITATIONS.md`

**Interfaces:**
- Consumes: Tasks 1 through 4.
- Produces: automated evidence plus explicit Android-device checks for visual behavior.

- [ ] **Step 1: Add exact device checks**

Document Pager, Webtoon, WebGPU, split-page, rotation, cached download, Manyue OFF/CLASSIC/AI, corrupt page, rapid swipe, menu show/hide, and `showPageNumber` disabled cases. Each line must state the expected page text and whether the metadata surface is visible.

- [ ] **Step 2: Run the aggregate regression command**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.reader.metadata.*" --tests "eu.kanade.tachiyomi.ui.reader.manyue.*" :app:compileDebugKotlin
```

Expected: all selected tests and compilation pass.

- [ ] **Step 3: Record device-only boundaries and commit**

If no Android device is connected, mark the visual checks as `需要用户真机操作`; do not mark them passed.

```powershell
git add DEVICE_TEST_CHECKLIST.md KNOWN_LIMITATIONS.md
git commit -m "docs(reader): add source metadata verification"
```
