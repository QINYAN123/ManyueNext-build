# Cloud Favorites Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one read-only Cloud Favorites screen backed by separately installable CopyManga, JM, and PICACG connector extensions that open manga through the user's existing TheNano/Keiyoushi sources.

**Architecture:** Extend `source-api` with a hidden cloud-provider capability loaded by Mihon's existing signed-extension mechanism. The host discovers providers, rejects ambiguous duplicates, resolves exact target source IDs, pages each provider independently, applies the shared session R18 policy, and converts results to normal target-source manga records before navigation.

**Tech Stack:** Kotlin, Android extension loading, HttpSource/OkHttp, Android Keystore AES-GCM, Paging 3, Jetpack Compose/Voyager, Metro DI, kotlinx.serialization, JUnit Jupiter.

**Spec:** `docs/superpowers/specs/2026-09-19-reader-metadata-r18-cloud-favorites-design.md`

## Global Constraints

- Keep third-party source packages installed and unchanged.
- Connector package names are `app.manyuenext.cloud.copymanga`, `app.manyuenext.cloud.jm`, and `app.manyuenext.cloud.picacg`.
- Target source IDs are CopyManga `6696312508930833206`, JM `6286738698187452081`, and PICACG `5768337869316468367`.
- Connector source IDs are CopyManga `2071416177830019694`, JM `1264765706795493282`, and PICACG `2951833901539335569`.
- Cloud operations are read-only; no remote favorite mutation, bulk import, or background synchronization.
- Bind manga records by exact target source ID and canonical URL; package/name hints never substitute a different ID.
- Never log credentials, tokens, cookies, authorization headers, raw decrypted payloads, or login response bodies.
- A connector is usable only after the user trusts its signing certificate through the existing extension flow.

## Review Focus

- Two trusted connectors claiming the same provider produce an explicit duplicate state instead of nondeterministic selection.
- A connector can be loaded for account settings while remaining absent from normal source and migration lists.
- Expired credentials map to `LoginRequired` and retain already displayed items until the user retries.
- A target source removed after page load prevents navigation and database insertion without crashing.
- Malformed remote items are skipped individually; a malformed page envelope fails that provider only.

---

### Task 1: Define the Cloud Connector Contract

**Files:**
- Create: `source-api/src/main/kotlin/eu/kanade/tachiyomi/source/cloud/CloudFavoritesSource.kt`
- Create: `source-api/src/main/kotlin/eu/kanade/tachiyomi/source/cloud/CloudModels.kt`
- Modify: `source-api/build.gradle.kts`
- Test: `source-api/src/test/kotlin/eu/kanade/tachiyomi/source/cloud/CloudModelsTest.kt`

**Interfaces:**
- Consumes: existing `Source`, `ConfigurableSource`, and `SManga` conventions.
- Produces: `CloudProviderKey`, `CloudTargetSource`, `CloudAccountState`, `CloudFavorite`, `CloudFavoritesPage`, `CloudFailure`, `CloudFavoritesException`, and `CloudFavoritesSource`.

- [ ] **Step 1: Add source-api test dependencies and failing model tests**

Add `testImplementation(libs.bundles.test)` and `testRuntimeOnly(libs.junit.platform.launcher)`. Write:

```kotlin
class CloudModelsTest {
    @Test fun `page rejects non-positive page number`() {
        assertThrows<IllegalArgumentException> { CloudFavoritesPage(page = 0, items = emptyList(), hasNextPage = false) }
    }

    @Test fun `favorite requires canonical url and title`() {
        assertThrows<IllegalArgumentException> {
            CloudFavorite(remoteId = "1", canonicalUrl = "", title = "", thumbnailUrl = null)
        }
    }

    @Test fun `user message never embeds technical cause`() {
        val error = CloudFavoritesException(CloudFailure.Authentication, "登录已失效", IllegalStateException("Bearer secret"))
        assertEquals("登录已失效", error.userMessage)
        assertFalse(error.userMessage.contains("secret"))
    }
}
```

- [ ] **Step 2: Run the contract test and verify failure**

```powershell
.\gradlew.bat :source-api:testDebugUnitTest --tests "eu.kanade.tachiyomi.source.cloud.CloudModelsTest"
```

Expected: compilation fails because cloud types are absent.

- [ ] **Step 3: Implement exact contract shapes**

```kotlin
enum class CloudProviderKey { COPY_MANGA, JM, PICACG }

data class CloudTargetSource(val sourceId: Long, val packageHint: String, val displayName: String)

sealed interface CloudAccountState {
    data class Ready(val displayName: String?) : CloudAccountState
    data object LoginRequired : CloudAccountState
    data object SessionExpired : CloudAccountState
}

data class CloudFavorite(
    val remoteId: String,
    val canonicalUrl: String,
    val title: String,
    val thumbnailUrl: String?,
    val author: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
)

data class CloudFavoritesPage(val page: Int, val items: List<CloudFavorite>, val hasNextPage: Boolean)

interface CloudFavoritesSource : ConfigurableSource {
    val providerKey: CloudProviderKey
    val targetSource: CloudTargetSource
    suspend fun accountState(): CloudAccountState
    suspend fun refreshSession(): CloudAccountState
    suspend fun getCloudFavorites(page: Int): CloudFavoritesPage
}
```

Provide default unsupported implementations for the normal `Source` catalogue/chapter methods so connector implementations cannot accidentally become reading sources.

- [ ] **Step 4: Run source-api tests and ABI compilation**

```powershell
.\gradlew.bat :source-api:testDebugUnitTest :source-api:assembleDebug
```

Expected: tests pass and the AAR assembles.

- [ ] **Step 5: Commit the connector API**

```powershell
git add source-api
git commit -m "feat(source-api): define cloud favorites connector"
```

### Task 2: Hide Connectors and Build a Deterministic Registry

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudConnectorRegistry.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudTargetResolver.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudConnectorRegistryTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudTargetResolverTest.kt`

**Interfaces:**
- Consumes: `ExtensionManager.loadedExtensionsFlow`, `SourceManager`, and `CloudFavoritesSource`.
- Produces: `CloudConnectorState.Missing`, `.Available(source)`, `.Duplicate(packageNames)`, plus `CloudTargetResolution.Available(source)` and `.Missing(target)`.

- [ ] **Step 1: Write failing registry tests**

```kotlin
@Test fun `one provider resolves available`() {
    assertIs<CloudConnectorState.Available>(CloudConnectorRegistry.resolve(listOf(copyConnector), CloudProviderKey.COPY_MANGA))
}

@Test fun `duplicate provider is rejected`() {
    val state = CloudConnectorRegistry.resolve(listOf(copyConnector, secondCopyConnector), CloudProviderKey.COPY_MANGA)
    assertEquals(setOf("pkg.a", "pkg.b"), assertIs<CloudConnectorState.Duplicate>(state).packageNames.toSet())
}

@Test fun `target resolver never falls back by name`() = runTest {
    val result = resolver.resolve(CloudTargetSource(6696312508930833206, "expected.pkg", "拷贝漫画"))
    assertIs<CloudTargetResolution.Missing>(result)
}
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.browse.cloud.CloudConnectorRegistryTest" --tests "eu.kanade.tachiyomi.ui.browse.cloud.CloudTargetResolverTest"
```

Expected: compilation fails because registry/resolver types are absent.

- [ ] **Step 3: Implement registry and hidden-source behavior**

Keep connectors addressable through `SourceManager.get(id)` for `SourcePreferencesScreen`, but exclude `CloudFavoritesSource` from `sources`, `getAll()`, `getOnlineSources()`, and stub registration. The registry scans only `Extension.Loaded` instances, which already passed signature trust checks.

- [ ] **Step 4: Implement exact-ID target resolution**

Resolve only `sourceManager.get(target.sourceId)`. A package mismatch is diagnostic metadata, not an alternate binding rule. Return `Missing` if the source does not exist or if the resolved object is another cloud connector.

- [ ] **Step 5: Run tests, compile, and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.browse.cloud.*" :app:compileDebugKotlin
git add app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud app/src/test/java/eu/kanade/tachiyomi/ui/browse/cloud
git commit -m "feat(cloud): discover trusted connector sources"
```

### Task 3: Add Paging Repository, Failure Mapping, and Safe Manga Conversion

**Files:**
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudFavoritesRepository.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudFavoritesPagingSource.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudFavoriteMapper.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudFavoritesPagingSourceTest.kt`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudFavoriteMapperTest.kt`

**Interfaces:**
- Consumes: `CloudFavoritesSource`, `CloudTargetResolver`, `AdultContentSessionState`, `AdultContentClassifier`, and `NetworkToLocalManga`.
- Produces: `Pager<Int, CloudFavorite>`, provider-local load state, and `suspend fun openableManga(favorite, target): Manga`.

- [ ] **Step 1: Write failing paging and mapping tests**

Test page keys `1 -> 2`, no next key when `hasNextPage=false`, authentication exception mapping, cancellation rethrow, and R18 filtering. Add:

```kotlin
@Test fun `mapper writes target source id never connector id`() = runTest {
    val manga = mapper.toDomain(favorite, targetSourceId = 6696312508930833206)
    assertEquals(6696312508930833206, manga.source)
    assertEquals("/comic/slug", manga.url)
}

@Test fun `missing target stops before database insertion`() = runTest {
    assertThrows<MissingCloudTargetException> { repository.open(favorite, missingTarget) }
    verify(exactly = 0) { networkToLocalManga(any()) }
}
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.browse.cloud.CloudFavoritesPagingSourceTest" --tests "eu.kanade.tachiyomi.ui.browse.cloud.CloudFavoriteMapperTest"
```

Expected: compilation fails because repository, pager, and mapper are absent.

- [ ] **Step 3: Implement paging and failure isolation**

Use `PagingConfig(pageSize = 21, enablePlaceholders = false)`. `CloudFavoritesPagingSource.load` calls one provider only. Convert known `CloudFavoritesException.failure` values into typed host failures; rethrow `CancellationException`; map all other exceptions to a generic provider parse/network state after redacting the message.

- [ ] **Step 4: Implement target-source conversion**

Create `SManga` with the connector result, set its canonical URL exactly, call `toDomainManga(targetSourceId)`, then `NetworkToLocalManga`. Check target availability immediately before insertion to handle uninstall races.

- [ ] **Step 5: Run tests and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.browse.cloud.*"
git add app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud app/src/test/java/eu/kanade/tachiyomi/ui/browse/cloud
git commit -m "feat(cloud): page and map provider favorites"
```

### Task 4: Add the Unified Browse Tab and Provider UI

**Files:**
- Modify: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/BrowseTab.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudFavoritesTab.kt`
- Create: `app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudFavoritesViewModel.kt`
- Create: `app/src/main/java/eu/kanade/presentation/browse/cloud/CloudFavoritesScreen.kt`
- Create: `app/src/main/java/eu/kanade/presentation/browse/cloud/CloudProviderSection.kt`
- Modify: `i18n/src/commonMain/moko-resources/base/strings.xml`
- Modify: `i18n/src/commonMain/moko-resources/zh-rCN/strings.xml`
- Modify: `i18n/src/commonMain/moko-resources/zh-rTW/strings.xml`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudOverviewReducerTest.kt`

**Interfaces:**
- Consumes: registry/repository, provider account state, shared adult-content state, and `SourcePreferencesScreen`.
- Produces: Overview plus CopyManga/JM/PICACG tabs, independent preview states, paging screens, login/settings navigation, retry, and target-source navigation events.

- [ ] **Step 1: Write failing overview reducer tests**

```kotlin
@Test fun `one provider failure preserves other loaded sections`() {
    val state = CloudOverviewReducer.reduce(
        previous = loadedCopyAndPica,
        event = ProviderFailed(CloudProviderKey.JM, CloudUiFailure.Offline),
    )
    assertIs<ProviderPreview.Loaded>(state.providers.getValue(CloudProviderKey.COPY_MANGA))
    assertIs<ProviderPreview.Failed>(state.providers.getValue(CloudProviderKey.JM))
}

@Test fun `overview limits each provider preview to six`() {
    assertEquals(6, CloudOverviewReducer.loaded(CloudProviderKey.PICACG, nineItems).items.size)
}
```

- [ ] **Step 2: Run reducer tests and verify failure**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.browse.cloud.CloudOverviewReducerTest"
```

Expected: compilation fails because UI state/reducer types are absent.

- [ ] **Step 3: Implement ViewModel and independent Overview loads**

Create one coroutine per provider for page-one preview. Preserve successful previews when another provider refreshes or fails. Provider full lists expose independent `Flow<PagingData<CloudFavorite>>`. Apply `AdultContentPolicy` to previews and paging with the shared session state.

- [ ] **Step 4: Implement the Browse tab**

Insert `cloudFavoritesTab()` after Sources. Update `BrowseTab.showExtension()` to scroll to the new Extensions index. Inside Cloud Favorites use tabs `Overview`, `CopyManga`, `JM`, `PICACG`. Missing connector, duplicate connector, login required, expired session, missing target source, offline, rate limit, parse failure, empty favorites, and R18-filtered empty states have distinct copy and actions.

- [ ] **Step 5: Wire navigation and settings**

`Log in / Repair login` pushes `SourcePreferencesScreen(connector.id)`. Item clicks call `CloudFavoritesViewModel.open(favorite)` and emit the existing `MangaScreen(localManga.id)` navigation event only after target resolution and insertion succeed.

- [ ] **Step 6: Run tests, format, compile, and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.browse.cloud.*" :app:spotlessApply :app:compileDebugKotlin
git diff --check
git add app/src/main/java/eu/kanade/tachiyomi/ui/browse app/src/main/java/eu/kanade/presentation/browse/cloud app/src/test/java/eu/kanade/tachiyomi/ui/browse/cloud i18n/src/commonMain/moko-resources
git commit -m "feat(cloud): add unified favorites browser"
```

### Task 5: Scaffold Connector Modules and Encrypted Account Storage

**Files:**
- Modify: `settings.gradle.kts`
- Create: `cloud-connectors/common/build.gradle.kts`
- Create: `cloud-connectors/common/src/main/AndroidManifest.xml`
- Create: `cloud-connectors/common/src/main/kotlin/app/manyuenext/cloud/common/EncryptedAccountStore.kt`
- Create: `cloud-connectors/common/src/main/kotlin/app/manyuenext/cloud/common/CloudConnectorBase.kt`
- Create: `cloud-connectors/common/src/main/kotlin/app/manyuenext/cloud/common/SecureCredentialPreference.kt`
- Create: `cloud-connectors/common/src/main/kotlin/app/manyuenext/cloud/common/CloudLogRedactor.kt`
- Test: `cloud-connectors/common/src/test/kotlin/app/manyuenext/cloud/common/CloudLogRedactorTest.kt`
- Test: `cloud-connectors/common/src/test/kotlin/app/manyuenext/cloud/common/CloudConnectorBaseTest.kt`

**Interfaces:**
- Consumes: `projects.sourceApi`, OkHttp, Android Keystore, and extension source preferences.
- Produces: encrypted `AccountSession`, masked username/password preferences, safe error mapping, and a base class for the three connectors.

- [ ] **Step 1: Add modules and failing redaction tests**

Include `:cloud-connectors:common`, `:cloud-connectors:copymanga`, `:cloud-connectors:jm`, and `:cloud-connectors:picacg` in settings. Add:

```kotlin
@Test fun `redactor removes common secrets`() {
    val raw = "Authorization: Bearer abc Cookie: AVS=xyz password=hunter2 token=qwerty"
    val clean = CloudLogRedactor.redact(raw)
    listOf("abc", "xyz", "hunter2", "qwerty").forEach { assertFalse(clean.contains(it)) }
}

@Test fun `authentication status code maps without response body`() {
    val failure = CloudConnectorBase.mapHttpFailure(401, "server echoed a token")
    assertEquals(CloudFailure.Authentication, failure.failure)
    assertFalse(failure.userMessage.contains("token"))
}
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
.\gradlew.bat :cloud-connectors:common:testDebugUnitTest
```

Expected: configuration or compilation fails because the common module/classes are absent.

- [ ] **Step 3: Implement common module and Keystore store**

Use `compileOnly(projects.sourceApi)` so connector APKs consume the host API without packaging duplicate `eu.kanade.tachiyomi.source` classes. Provider modules use `implementation(project(":cloud-connectors:common"))` plus the same compile-only API. Use AES/GCM/NoPadding with one alias per connector. Persist `base64(iv).base64(ciphertext)` only. `SecureCredentialPreference` writes through a `Preference.OnPreferenceChangeListener` and returns `false` so cleartext never enters normal SharedPreferences. Password summary is always `••••••`; username summary may show the account name.

- [ ] **Step 4: Implement common HTTP failure mapping**

Map `401/403 -> Authentication`, `429 -> RateLimited`, other `4xx/5xx -> Remote`, `IOException -> Network`, and JSON/schema failures -> Parsing. User messages are fixed localized-safe phrases and never append raw causes.

- [ ] **Step 5: Run common tests and commit**

```powershell
.\gradlew.bat :cloud-connectors:common:testDebugUnitTest :cloud-connectors:common:assembleDebug
git add settings.gradle.kts cloud-connectors/common
git commit -m "feat(cloud): scaffold secure connector runtime"
```

### Task 6: Implement CopyManga Connector

**Files:**
- Create: `cloud-connectors/copymanga/build.gradle.kts`
- Create: `cloud-connectors/copymanga/src/main/AndroidManifest.xml`
- Create: `cloud-connectors/copymanga/src/main/kotlin/app/manyuenext/cloud/copymanga/CopyMangaCloudSource.kt`
- Create: `cloud-connectors/copymanga/src/main/kotlin/app/manyuenext/cloud/copymanga/CopyMangaApi.kt`
- Create: `cloud-connectors/copymanga/src/main/kotlin/app/manyuenext/cloud/copymanga/CopyMangaSchemas.kt`
- Test resource: `cloud-connectors/copymanga/src/test/resources/copymanga/favorites-page.json`
- Test: `cloud-connectors/copymanga/src/test/kotlin/app/manyuenext/cloud/copymanga/CopyMangaApiTest.kt`

**Interfaces:**
- Consumes: common connector base and v0.7.4 CopyManga authentication/favorites behavior.
- Produces: provider `COPY_MANGA`, connector ID `2071416177830019694`, target ID `6696312508930833206`, `/comic/{path_word}` canonical URLs, and 21-item pagination.

- [ ] **Step 1: Create fixture and failing parser tests**

```kotlin
@Test fun `favorites map to TheNano canonical url`() {
    val page = CopyMangaApi.parseFavorites(fixture("favorites-page.json"), page = 1)
    assertEquals("/comic/azure-legacy", page.items.single().canonicalUrl)
    assertEquals("Azure Legacy", page.items.single().title)
}

@Test fun `offset is page based`() {
    assertEquals(0, CopyMangaApi.offset(page = 1))
    assertEquals(21, CopyMangaApi.offset(page = 2))
}
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
.\gradlew.bat :cloud-connectors:copymanga:testDebugUnitTest
```

Expected: module/class compilation failure.

- [ ] **Step 3: Implement login and favorites requests**

Port the v0.7.4 web-account login request with its salt/password encoding and validate the returned token against `/member/info`. Fetch:

```text
/member/collect/comics?limit=21&offset={offset}&ordering=-datetime_modifier
```

Send `Authorization: Token {token}`. On validation failure clear the token but retain the encrypted username so repair login is straightforward. Do not port chapter, page-image, comments, or remote favorite mutation code.

- [ ] **Step 4: Add manifest metadata and account preferences**

Declare `uses-feature android:name="tachiyomi.extension"`, `tachiyomix.extensionLib=1.6`, and source class `.CopyMangaCloudSource`. Mark content warning `MIXED`. Preferences contain encrypted username/password, API host choice, `Validate session`, and `Log out`.

- [ ] **Step 5: Run parser tests, assemble, inspect, and commit**

```powershell
.\gradlew.bat :cloud-connectors:copymanga:testDebugUnitTest :cloud-connectors:copymanga:assembleDebug
jar tf cloud-connectors/copymanga/build/outputs/apk/debug/copymanga-debug.apk | Select-String 'AndroidManifest.xml|classes.dex'
git add cloud-connectors/copymanga
git commit -m "feat(cloud): add CopyManga favorites connector"
```

### Task 7: Implement JM Connector

**Files:**
- Create: `cloud-connectors/jm/build.gradle.kts`
- Create: `cloud-connectors/jm/src/main/AndroidManifest.xml`
- Create: `cloud-connectors/jm/src/main/kotlin/app/manyuenext/cloud/jm/JmCloudSource.kt`
- Create: `cloud-connectors/jm/src/main/kotlin/app/manyuenext/cloud/jm/JmApi.kt`
- Create: `cloud-connectors/jm/src/main/kotlin/app/manyuenext/cloud/jm/JmCrypto.kt`
- Create: `cloud-connectors/jm/src/main/kotlin/app/manyuenext/cloud/jm/JmSchemas.kt`
- Test resource: `cloud-connectors/jm/src/test/resources/jm/favorites-page.json`
- Test: `cloud-connectors/jm/src/test/kotlin/app/manyuenext/cloud/jm/JmApiTest.kt`
- Test: `cloud-connectors/jm/src/test/kotlin/app/manyuenext/cloud/jm/JmCryptoTest.kt`

**Interfaces:**
- Consumes: v0.7.4 `ApiClient`, `CryptoUtil`, and secure-session behavior limited to login/favorites.
- Produces: provider `JM`, connector ID `1264765706795493282`, target ID `6286738698187452081`, `/album/{id}/` canonical URLs, JWT/AVS session support, and JM page pagination.

- [ ] **Step 1: Add known-vector crypto and parser tests**

Extract one non-secret deterministic request-signing/encryption vector from the reference implementation. Assert the Kotlin port emits the same signature/ciphertext for fixed timestamp, path, and payload. Parse a fixture and assert:

```kotlin
assertEquals("/album/12345/", page.items.single().canonicalUrl)
assertEquals("12345", page.items.single().remoteId)
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
.\gradlew.bat :cloud-connectors:jm:testDebugUnitTest
```

Expected: module/class compilation failure.

- [ ] **Step 3: Port the minimum JM authentication surface**

Implement `login` with username/password, encrypted JWT plus AVS storage, request signing/encryption, and session validation. Fetch `favorite` using `page`, `folder_id=0`, and `o=mr`. Do not port history, comments, chapter lists, images, downloads, or favorite mutation.

- [ ] **Step 4: Add manifest and secure account preferences**

Use content warning `NSFW`. Preferences provide encrypted username/password, API endpoint selection copied from the validated reference list, `Validate session`, and `Log out`.

- [ ] **Step 5: Run tests, assemble, inspect, and commit**

```powershell
.\gradlew.bat :cloud-connectors:jm:testDebugUnitTest :cloud-connectors:jm:assembleDebug
jar tf cloud-connectors/jm/build/outputs/apk/debug/jm-debug.apk | Select-String 'AndroidManifest.xml|classes.dex'
git add cloud-connectors/jm
git commit -m "feat(cloud): add JM favorites connector"
```

### Task 8: Implement PICACG Connector

**Files:**
- Create: `cloud-connectors/picacg/build.gradle.kts`
- Create: `cloud-connectors/picacg/src/main/AndroidManifest.xml`
- Create: `cloud-connectors/picacg/src/main/kotlin/app/manyuenext/cloud/picacg/PicacgCloudSource.kt`
- Create: `cloud-connectors/picacg/src/main/kotlin/app/manyuenext/cloud/picacg/PicacgApi.kt`
- Create: `cloud-connectors/picacg/src/main/kotlin/app/manyuenext/cloud/picacg/PicacgSigner.kt`
- Create: `cloud-connectors/picacg/src/main/kotlin/app/manyuenext/cloud/picacg/PicacgSchemas.kt`
- Test resource: `cloud-connectors/picacg/src/test/resources/picacg/favorites-page.json`
- Test: `cloud-connectors/picacg/src/test/kotlin/app/manyuenext/cloud/picacg/PicacgApiTest.kt`
- Test: `cloud-connectors/picacg/src/test/kotlin/app/manyuenext/cloud/picacg/PicacgSignerTest.kt`

**Interfaces:**
- Consumes: current Keiyoushi Picacomic signing/auth behavior plus the v0.7.4 favorites response mapping.
- Produces: provider `PICACG`, connector ID `2951833901539335569`, target ID `5768337869316468367`, `https://picaapi.picacomic.com/comics/{id}` canonical URLs, and `/users/favourite` pagination.

- [ ] **Step 1: Add known-vector signer and parser tests**

For fixed path, method, timestamp, nonce, and API key, assert the Kotlin signer matches the current extension result. Parse a fixture and assert:

```kotlin
assertEquals(
    "https://picaapi.picacomic.com/comics/64abc",
    page.items.single().canonicalUrl,
)
assertEquals("64abc", page.items.single().remoteId)
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
.\gradlew.bat :cloud-connectors:picacg:testDebugUnitTest
```

Expected: module/class compilation failure.

- [ ] **Step 3: Implement authentication, signing, and favorites**

Login through `/auth/sign-in`, persist the token encrypted, refresh after a single authentication failure, and fetch `GET /users/favourite?page={page}&s=dd`. Allow one automatic retry after token refresh; a second 401/403 becomes `SessionExpired`.

- [ ] **Step 4: Add manifest and secure account preferences**

Use content warning `NSFW`. Preferences expose encrypted username/password, endpoint/channel choice only where the current extension proves it necessary, `Validate session`, and `Log out`.

- [ ] **Step 5: Run tests, assemble, inspect, and commit**

```powershell
.\gradlew.bat :cloud-connectors:picacg:testDebugUnitTest :cloud-connectors:picacg:assembleDebug
jar tf cloud-connectors/picacg/build/outputs/apk/debug/picacg-debug.apk | Select-String 'AndroidManifest.xml|classes.dex'
git add cloud-connectors/picacg
git commit -m "feat(cloud): add PICACG favorites connector"
```

### Task 9: Cloud Integration and Security Gate

**Files:**
- Modify: `DEVICE_TEST_CHECKLIST.md`
- Modify: `KNOWN_LIMITATIONS.md`
- Create: `docs/CLOUD_FAVORITES_COMPATIBILITY.md`
- Test: `app/src/test/java/eu/kanade/tachiyomi/ui/browse/cloud/CloudFavoritesEndToEndPolicyTest.kt`

**Interfaces:**
- Consumes: Tasks 1 through 8 and the shared session R18 policy.
- Produces: cross-module compatibility evidence and an installation/login test matrix.

- [ ] **Step 1: Add an end-to-end policy test with fake connectors**

Cover connector discovery, account ready, first page, R18 removal, target resolution, domain conversion, and navigation ID. Assert the resulting manga source is the target source for all three providers. Add failure cases for duplicate connector, missing target, expired login, and cancellation.

- [ ] **Step 2: Run all cloud tests and builds**

```powershell
.\gradlew.bat :source-api:testDebugUnitTest :app:testDebugUnitTest --tests "eu.kanade.tachiyomi.ui.browse.cloud.*" :cloud-connectors:common:testDebugUnitTest :cloud-connectors:copymanga:testDebugUnitTest :cloud-connectors:jm:testDebugUnitTest :cloud-connectors:picacg:testDebugUnitTest :cloud-connectors:copymanga:assembleDebug :cloud-connectors:jm:assembleDebug :cloud-connectors:picacg:assembleDebug
```

Expected: all tests pass and three connector APKs are assembled.

- [ ] **Step 3: Scan source and built artifacts for credential leakage**

Run:

```powershell
rg -n -i 'log.*(password|authorization|cookie|token)|print.*(password|authorization|cookie|token)' cloud-connectors app/src/main/java/eu/kanade/tachiyomi/ui/browse/cloud
```

Expected: no statement logs a secret value. Constant header names and redaction tests may match and must be reviewed manually.

- [ ] **Step 4: Document exact compatibility and device checks**

Record target package/source IDs, connector package/source IDs, install/trust steps, login repair flow, read-only boundary, and the behavior when an upstream extension changes its source ID. Add device checks for all three account flows, page two, offline retry, session expiry, target-source uninstall, and R18 session switching.

- [ ] **Step 5: Commit cloud verification**

```powershell
git add app/src/test/java/eu/kanade/tachiyomi/ui/browse/cloud DEVICE_TEST_CHECKLIST.md KNOWN_LIMITATIONS.md docs/CLOUD_FAVORITES_COMPATIBILITY.md
git commit -m "test(cloud): verify connector integration"
```
