# Session R18 Filtering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Hide confirmed R18 content across Library and source browsing by default, with one process-wide filter switch that reveals it until Android kills the process.

**Architecture:** A Metro application singleton owns non-persistent visibility state. A pure classifier combines extension content warnings, exact adult-source IDs, and normalized genres; Library, Browse, and later Cloud Favorites call the same policy rather than duplicating rules.

**Tech Stack:** Kotlin, StateFlow, Metro DI, Paging 3 transformations, Jetpack Compose, JUnit Jupiter.

**Spec:** `docs/superpowers/specs/2026-09-19-reader-metadata-r18-cloud-favorites-design.md`

## Global Constraints

- `showAdultContent` initializes `false` for every application process.
- Do not store this state in preferences, SavedStateHandle, database, backup, or connector settings.
- Activity recreation inside one process must retain the singleton's state.
- Source metadata and explicit genres may classify content; title keyword matching is forbidden.
- Unknown or missing classification evidence is fail-open.
- The exact known adult source IDs are PICACG `5768337869316468367` and JM `6286738698187452081`.

## Review Focus

- `MIXED` extensions must inspect per-title genres rather than hiding every title.
- Full-width punctuation, case, spaces, underscores, and hyphens in adult tags normalize consistently.
- A source warning update after extension reload must re-filter Library without app restart.
- Paging must continue loading after a fully filtered page instead of reporting a false terminal empty state.
- The active filter indicator must include the session switch without modifying persistent library filter preferences.

---

### Task 1: Add Process-Scoped State and Pure Classification

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/adult/AdultContentSessionState.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/adult/AdultContentClassifier.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/adult/AdultContentSessionStateTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/adult/AdultContentClassifierTest.kt`

**Interfaces:**
- Consumes: source ID, `ContentWarning`, and nullable genre list.
- Produces: `AdultContentSessionState.isShown: StateFlow<Boolean>`, `setShown(Boolean)`, `toggle()`, and `AdultContentClassifier.isAdult(sourceId, warning, genres): Boolean`.

- [ ] **Step 1: Write failing state and classifier tests**

```kotlin
class AdultContentSessionStateTest {
    @Test fun `new instance starts hidden`() {
        assertFalse(AdultContentSessionState().isShown.value)
    }

    @Test fun `toggle affects the current instance only`() {
        val current = AdultContentSessionState().also { it.setShown(true) }
        assertTrue(current.isShown.value)
        assertFalse(AdultContentSessionState().isShown.value)
    }
}

class AdultContentClassifierTest {
    private val classifier = AdultContentClassifier()

    @Test fun `nsfw extension hides all items`() {
        assertTrue(classifier.isAdult(1, ContentWarning.NSFW, null))
    }

    @Test fun `mixed extension keeps untagged item`() {
        assertFalse(classifier.isAdult(1, ContentWarning.MIXED, listOf("Action")))
    }

    @Test fun `normalized explicit tags classify adult content`() {
        listOf("R-18", "r 18", "18＋", "成人", "NSFW", "Hentai").forEach {
            assertTrue(classifier.isAdult(1, ContentWarning.SAFE, listOf(it)), it)
        }
    }

    @Test fun `known adult source id wins when metadata is missing`() {
        assertTrue(classifier.isAdult(5768337869316468367, ContentWarning.SAFE, null))
        assertTrue(classifier.isAdult(6286738698187452081, ContentWarning.SAFE, null))
    }

    @Test fun `title-like text is not inspected`() {
        assertFalse(classifier.isAdult(1, ContentWarning.SAFE, listOf("Action", "Romance")))
    }
}
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.adult.AdultContentSessionStateTest" --tests "eu.kanade.tachiyomi.ui.adult.AdultContentClassifierTest"
```

Expected: compilation fails because both classes are absent.

- [ ] **Step 3: Implement state and classifier**

Annotate `AdultContentSessionState` with `@Inject`, `@SingleIn(AppScope::class)`, and `@ContributesBinding(AppScope::class)` only if an interface is introduced; otherwise constructor injection of the concrete singleton is sufficient. Use a private `MutableStateFlow(false)` and expose `asStateFlow()`.

Normalize tags with Unicode NFKC, lowercase using `Locale.ROOT`, and remove whitespace, `_`, and `-`. Match only the normalized exact set:

```kotlin
setOf("r18", "18+", "成人", "成年", "nsfw", "hentai", "色情")
```

- [ ] **Step 4: Run tests and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.adult.*"
git add app/src/main/java/eu/kanade/tachiyomi/ui/adult app/src/test/java/eu/kanade/tachiyomi/ui/adult
git commit -m "feat(filter): add session adult-content policy"
```

Expected: all focused tests pass.

### Task 2: Resolve Extension Warnings Reactively

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/adult/SourceContentWarningResolver.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt:90-120`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/adult/SourceContentWarningResolverTest.kt`

**Interfaces:**
- Consumes: `ExtensionManager.loadedExtensionsFlow`.
- Produces: `SourceContentWarningResolver.warnings: StateFlow<Map<Long, ContentWarning>>` and `warningFor(sourceId): ContentWarning`.

- [ ] **Step 1: Write failing resolver tests against a pure map builder**

```kotlin
@Test fun `maps every loaded source id to its extension warning`() {
    val result = SourceContentWarningResolver.buildMap(
        listOf(extension(ContentWarning.NSFW, sourceIds = listOf(10L, 11L))),
    )
    assertEquals(ContentWarning.NSFW, result[10L])
    assertEquals(ContentWarning.NSFW, result[11L])
}

@Test fun `missing source defaults safe`() {
    assertEquals(ContentWarning.SAFE, SourceContentWarningResolver.warningFor(emptyMap(), 42L))
}
```

- [ ] **Step 2: Run the test and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.adult.SourceContentWarningResolverTest"
```

Expected: compilation fails because the resolver is absent.

- [ ] **Step 3: Implement resolver and a narrow ExtensionManager lookup**

Add `suspend fun getExtensionForSource(sourceId: Long): Extension.Loaded?` and a flow equivalent that return the loaded extension containing that exact source ID. The resolver converts `loadedExtensionsFlow` into a state map in an application scope; it must replace the entire map on reload so removed extensions do not leave stale warnings.

- [ ] **Step 4: Run tests, compile, and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.adult.SourceContentWarningResolverTest" :app:compileDebugKotlin
git add app/src/main/java/eu/kanade/tachiyomi/ui/adult app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt app/src/test/java/eu/kanade/tachiyomi/ui/adult
git commit -m "feat(filter): resolve source content warnings"
```

### Task 3: Apply the Shared Policy to Library Results

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/adult/AdultContentPolicy.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryViewModel.kt:100-280`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibrarySettingsViewModel.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/library/LibrarySettingsDialog.kt:45-140`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Modify: `i18n/src/commonMain/moko-resources/zh-rCN/strings.xml`
- Modify: `i18n/src/commonMain/moko-resources/zh-rTW/strings.xml`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/adult/AdultLibraryFilterTest.kt`

**Interfaces:**
- Consumes: session state, warning map, `Manga.source`, and `Manga.genre`.
- Produces: `AdultContentPolicy.shouldShow(...)` used from `LibraryViewModel.applyFilters`, plus Library settings accessors for the session switch.

- [ ] **Step 1: Write failing list-filter tests**

```kotlin
@Test fun `hidden mode removes adult source and tagged manga`() {
    val visible = AdultContentPolicy.filter(
        items = listOf(safeItem, taggedItem, adultSourceItem),
        showAdult = false,
        warningFor = { id -> if (id == adultSourceItem.source) ContentWarning.NSFW else ContentWarning.SAFE },
        sourceId = { it.source },
        genres = { it.genres },
    )
    assertEquals(listOf(safeItem), visible)
}

@Test fun `shown mode returns every item unchanged`() {
    assertEquals(allItems, AdultContentPolicy.filter(allItems, true, warningFor, sourceId, genres))
}
```

- [ ] **Step 2: Run the focused test and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.adult.AdultLibraryFilterTest"
```

Expected: compilation fails because `AdultContentPolicy` is absent.

- [ ] **Step 3: Implement policy and add session inputs to the Library flow**

Add `adultContentSessionState.isShown` and `sourceContentWarningResolver.warnings` to the existing `combine` that produces Library data. Filter before search and grouping. Return `AdultFilterResult(items, hiddenCount)` so the UI can distinguish an R18-filtered empty category. The default hidden state is not an active-filter badge; set `hasActiveFilters = true` only while R18 is explicitly shown.

- [ ] **Step 4: Add the Library filter control**

In `FilterPage`, place a two-state `CheckboxItem` labeled `R18 content` / `R18 内容` after existing manga-state filters and before tracker filters. Checked means “show R18 this session.” Supporting text states that restart hides it again. Call `viewModel.setAdultContentShown(!current)`; never write `LibraryPreferences`.

- [ ] **Step 5: Run tests and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.adult.*" :app:compileDebugKotlin
git add app/src/main/java/eu/kanade/tachiyomi/ui/library app/src/main/java/eu/kanade/presentation/library app/src/main/java/eu/kanade/tachiyomi/ui/adult app/src/test/java/eu/kanade/tachiyomi/ui/adult i18n/src/commonMain/moko-resources
git commit -m "feat(library): filter adult content per session"
```

### Task 4: Apply the Shared Policy to Source Paging

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceViewModel.kt:60-155`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/SourceFilterDialog.kt`
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse/BrowseSourceScreen.kt`
- Create: `app/src/test/java/eu/kanade/tachiyomi/ui/adult/AdultPagingPolicyTest.kt`

**Interfaces:**
- Consumes: `PagingData<StateFlow<Manga>>`, source warning, and `AdultContentSessionState.isShown`.
- Produces: `AdultPagingFilterResult(pagingData, hiddenCount: StateFlow<Int>)`, using a per-generation set of hidden `sourceId/url` keys so repeated presentation does not double-count, and reacts when the process-wide switch changes.

- [ ] **Step 1: Write failing paging-policy tests**

Use `PagingData.from`, `AsyncPagingDataDiffer`, and `kotlinx-coroutines-test` to assert a safe item remains while a tagged item is removed in hidden mode, and that both appear in shown mode. Add a case where page one is fully adult and page two contains a safe item; the safe item must still be collected.

- [ ] **Step 2: Run the paging test and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.adult.AdultPagingPolicyTest"
```

Expected: assertion or compilation failure because the paging policy is absent.

- [ ] **Step 3: Combine paging with session state and warning updates**

Build the pager once per listing, then combine its flow with `isShown` and the warning flow for `sourceId`. Apply `PagingData.filter` after the existing local-library hiding transform. Read `mangaFlow.value.genre` for classification; unknown genres remain visible unless the entire source is NSFW. Reset the hidden-key set for every new listing generation and expose its distinct count for the filtered-empty message.

- [ ] **Step 4: Add the source filter switch and active indicator**

Extend `SourceFilterDialog` parameters with:

```kotlin
showAdultContent: Boolean,
onShowAdultContentChange: (Boolean) -> Unit,
```

Render the same session-only checkbox above source-defined filters, separated by a divider. The Browse toolbar filter icon is active when source filters differ from defaults or R18 is shown.

- [ ] **Step 5: Run paging, classifier, and compilation checks**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.adult.*" :app:compileDebugKotlin
git diff --check
```

Expected: all checks pass.

- [ ] **Step 6: Commit Browse integration**

```powershell
git add app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse app/src/test/java/eu/kanade/tachiyomi/ui/adult
git commit -m "feat(browse): apply session adult filter"
```

### Task 5: Prove Non-Persistence and Empty-State Copy

**Files:**
- Modify: `app/src/main/java/eu/kanade/presentation/library/components/LibraryContent.kt`
- Modify: `app/src/main/java/eu/kanade/presentation/browse/BrowseSourceScreen.kt`
- Modify: `DEVICE_TEST_CHECKLIST.md`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/adult/AdultContentPersistenceBoundaryTest.kt`

**Interfaces:**
- Consumes: session state and filtered-empty conditions.
- Produces: explicit hidden-content empty states and a regression test proving no persistence dependency exists.

- [ ] **Step 1: Add a construction-boundary test**

Instantiate two `AdultContentSessionState` objects in sequence, show content in the first, and assert the second starts hidden. Inspect the constructor with reflection and assert it takes no `Preferences`, `SavedStateHandle`, DAO, or `Bundle` dependency.

- [ ] **Step 2: Add filtered-empty copy and reveal action**

When unfiltered input count is nonzero but the visible count is zero because of adult filtering, show `R18 内容已隐藏` with a button `本次运行显示`. Network-empty and search-empty states keep their existing copy.

- [ ] **Step 3: Run the full R18 regression suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.adult.*" :app:compileDebugKotlin :app:spotlessApply
git diff --check
```

Expected: all tests and compilation pass; formatting produces no invalid diff.

- [ ] **Step 4: Document device lifecycle checks and commit**

Add checks for activity rotation, home/resume, swipe-away with process retained, forced process stop, and cold relaunch. Only forced process death/cold launch must reset the switch.

```powershell
git add app/src/main/java/eu/kanade/presentation DEVICE_TEST_CHECKLIST.md app/src/test/java/eu/kanade/tachiyomi/ui/adult
git commit -m "test(filter): verify session-only adult visibility"
```
