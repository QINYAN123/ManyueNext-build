# Reader Metadata, Session R18 Filtering, and Cloud Favorites Design

## Purpose

Extend the existing ManyueNext Mihon fork in three focused areas:

1. Show the current page and the original source-image resolution while the reader's translucent controls are visible, without placing persistent information over the artwork.
2. Hide R18 content in the library, source browsing, and cloud favorites by default for every new app process, with one session-wide filter switch that can reveal it temporarily.
3. Add one cloud-favorites entry point backed by three companion connectors for CopyManga, JM (Jinman Tiantang), and PICACG, while keeping the user's existing Keiyoushi and TheNano sources installed and unchanged.

The v5 ManyueNext source is the product base. ManyueHD v0.7.4 is a technical reference for cloud authentication, endpoints, pagination, and response mapping; it is not a replacement application and its documents are not treated as product requirements.

## Product principles

- Reading remains visually clean until the user summons reader controls.
- Adult-content visibility is an intentional, temporary action and never survives process death.
- Existing library source IDs and third-party extension installations are preserved.
- Cloud failures are isolated by provider; one broken site must not blank the whole screen.
- The implementation adds only behavior needed for the requested workflows. It does not add cross-source merging, recommendation scoring, or background synchronization.

## Architecture

### Host application

The host application owns:

- the reader metadata model and translucent control rendering;
- the process-local adult-content visibility state and filtering policy;
- cloud connector discovery, trust, target-source resolution, navigation, paging presentation, and error states;
- a unified Cloud Favorites screen under Browse.

The host does not contain site credentials and does not replace CopyManga, JM, or PICACG as reading sources.

### Cloud connector API

Add a small backward-compatible API to `source-api`. A `CloudFavoritesSource` is also a normal `Source` so it can use Mihon's existing signed-extension loading and trust flow. The marker exposes:

- a stable provider key and display name;
- one or more exact target source identities, led by source ID and supplemented by package/name hints for diagnostics;
- account state (`ready`, `login required`, or `session expired`);
- paged, read-only cloud favorites;
- a refresh-session operation;
- a standard extension settings screen for login and logout.

Connector sources are marked hidden. `AndroidSourceManager` excludes them from normal source browsing, migration, and library source pickers, but `ExtensionManager` still loads and validates them. Existing extensions remain ABI-compatible because they do not implement the new marker.

Each returned favorite contains the canonical manga URL expected by the installed target source, title, thumbnail, optional author/description/genres, and the provider's remote identifier. The host always rewrites the result to the resolved target source ID before opening details or adding it to the library. Connector source IDs are never written into library records.

### Companion connectors

Build three separately installable APKs with unique package names and signatures controlled by this project. They coexist with third-party source APKs:

- CopyManga Cloud Connector, compatible with the TheNano CopyManga source;
- JM Cloud Connector, compatible with the Keiyoushi Jinman Tiantang source;
- PICACG Cloud Connector, compatible with the Keiyoushi Picacomic source.

The connectors own site-specific authentication, requests, signing/encryption, pagination, and response parsing. They run through the same trusted extension loader as other source extensions and use their own preference namespaces. Where an existing source exposes a stable reusable token in host-visible source preferences, the connector may import it after validation. It must not use reflection to read private extension fields. When reuse is unavailable or invalid, the connector asks the user to log in once through its extension settings.

PICACG should reuse its established favorites endpoint and authentication behavior. CopyManga and JM should port only the cloud-account and favorites portions needed from the v0.7.4 reference, not the full standalone reader or duplicate chapter/image implementations.

## Cloud Favorites experience

Browse gains one Cloud Favorites action. The screen contains:

- an Overview tab with an independent preview section for each available provider;
- CopyManga, JM, and PICACG tabs with full paging;
- provider-specific refresh and retry actions;
- a direct `Log in / Repair login` action that opens the relevant connector settings;
- a clear missing-connector or missing-target-source message rather than an empty list.

The Overview does not merge three remote feeds into an artificial chronology. Each section loads independently and links to its provider tab. A provider error remains inside that section.

Opening a cloud favorite resolves its installed target source, creates or reuses the normal local manga record for that source and canonical URL, then opens Mihon's existing manga details flow. Adding to the library uses the existing details action and database path. Cloud favorites are read-only in this release: the app does not add or remove favorites on the remote website.

Requests are cancelled when their screen leaves composition, preserve already loaded pages during retry, and distinguish authentication, rate-limit, network, parsing, and missing-source failures. Credentials and tokens must never appear in logs, crash reports, or user-facing error strings.

## Session R18 filtering

### State and lifecycle

Introduce one process-scoped `AdultContentSessionState` backed by a `StateFlow`. Its initial value is always `hidden`. It is deliberately not stored in preferences, SavedStateHandle, database, backup, or connector settings.

The state survives activity recreation while the process remains alive. Once Android kills the process, the next launch creates a new hidden state. This matches the requested “show for this session only” behavior.

### Classification

`AdultContentClassifier` uses the following evidence in order:

1. the installed extension's NSFW/content-warning metadata;
2. an exact source-ID registry for known adult-only variants, including JM and PICACG compatibility targets;
3. normalized manga genres/tags such as `R18`, `R-18`, `18+`, `成人`, `成年`, `NSFW`, `Hentai`, and explicit equivalent tags.

Adult-only sources are hidden in full. General sources such as CopyManga remain visible, but individually tagged adult titles are removed. Title keywords alone are not used because they create unacceptable false positives. When neither source metadata nor manga tags establish adult status, the item remains visible.

### Surfaces and controls

The same state filters:

- Library results after the existing library query/filter pipeline;
- Browse-source `PagingData` before rendering;
- all Cloud Favorites provider previews and pages.

Library filters, source filters, and Cloud Favorites expose the same `R18 content` filter row. Turning it on updates all three surfaces immediately. A visible active-filter chip indicates that adult content is currently shown. There is no independent per-screen state and no persistent “remember my choice” option.

When hidden filtering removes every item, the empty state explains that R18 content is hidden and offers the same session-only reveal action. It does not masquerade as a network failure.

## Reader page and original resolution

### Data capture

Add immutable source-image metadata to the reader page lifecycle. Pager and Webtoon holders read image bounds from the original downloaded/cached bytes before Manyue processing, rotation, crop, split-page handling, AI enhancement, or display downsampling. Bounds decoding must not allocate a full second bitmap.

The result is `width x height` for the original source asset. If one source image is displayed as two split pages, both logical halves show the same original dimensions. Processing output dimensions are never presented as source resolution.

The holder publishes metadata through the reader's existing state/update path. A page change clears the previous page's dimensions immediately so stale values cannot flash on the next page. Unsupported or failed images produce an unavailable state rather than `0 x 0`.

### Presentation

Remove the current always-on page indicator behavior. While `menuVisible` is true, the bottom translucent reader controls show one compact line:

`12 / 38 · 原图 2400 × 3600`

Before bounds are available it shows `12 / 38 · 原图读取中`; if bounds cannot be determined it shows `12 / 38 · 原图未知`. When the reader controls are hidden, this line is not composed and no replacement overlay is drawn.

The existing page-number preference is preserved as the switch for this metadata line, with its default enabled. The preference no longer authorizes an always-on overlay. Page numbering continues to use the reader's existing dual-page and chapter-transition rules.

## Targeted bug and UX work

The implementation includes defects discovered while touching these flows, provided they are reproducible and local to reader loading, extension loading, filters, cloud paging, or lifecycle handling. The already observed Windows `gradlew.bat` no-Java fall-through will be corrected so it exits through the intended failure path instead of emitting misleading secondary errors.

Settings that only apply to an active Manyue mode should be conditionally presented or disabled with a useful explanation. Cloud and filter screens receive explicit loading, empty, authentication, offline, parse-error, and retry states. Unrelated refactors and speculative features remain out of scope.

## Security and compatibility

- Companion APKs use unique package names; they never attempt to replace a Keiyoushi or TheNano package signed by someone else.
- Connector installation follows Mihon's existing extension trust decision. The host does not silently trust a new signer.
- Account secrets remain in connector-owned preferences using Android keystore-backed encryption where supported. Plain credentials, authorization headers, tokens, and decrypted payloads are redacted from diagnostics.
- Target sources resolve by exact source ID first. Package and display-name matching can explain setup problems but cannot silently bind library records to a different source.
- If an upstream target source changes URL formats or IDs, that provider becomes unavailable with an actionable compatibility message; the host must not create orphaned library entries.

## Verification

### Automated tests

- original image dimensions are captured before every image transformation;
- split, rotated, AI-processed, cached, failed, and rapidly changed reader pages cannot expose transformed or stale dimensions;
- metadata UI is present only when reader controls are visible and the preference is enabled;
- the session R18 state initializes hidden, survives activity recreation, and is not persisted across a recreated application process;
- adult classification covers extension warnings, exact source IDs, normalized tags, and fail-open unknown metadata;
- Library, Browse paging, Overview previews, and provider pages all consume the same R18 state;
- connector discovery hides connectors from normal source lists and rejects untrusted, incompatible, or duplicate provider implementations;
- each connector maps pagination and canonical URLs to its intended target source;
- authentication expiration, rate limiting, network loss, malformed responses, cancellation, and per-provider retry are isolated;
- cloud results never write connector source IDs into the library;
- Windows wrapper failure exits once with the correct message.

### Build and package checks

- run formatting/lint and relevant JVM/unit tests;
- compile the app and all three connector modules;
- assemble the main APK and three signed debug/release-candidate connector APKs as the available signing environment permits;
- inspect APK manifests, package names, extension metadata, source API compatibility, and absence of logged credentials;
- verify that release artifacts contain no `.git`, test caches, local credentials, or reference-app binaries.

### Device checks

On an Android device, verify pager and Webtoon metadata visibility, page transitions, split pages, Manyue modes, process-death R18 reset, all three login flows, cloud paging, opening details through the original source, add-to-library behavior, expired sessions, and missing connector/source states. Any check that cannot be automated without a device is recorded explicitly rather than reported as passed.

## Deliverables

- updated ManyueNext Mihon fork source;
- main application APK when the Android toolchain permits a successful build;
- CopyManga, JM, and PICACG cloud connector source and APKs;
- automated tests and device-test checklist updates;
- a concise audit report listing fixed bugs, recommended follow-up improvements, and deliberately omitted low-value features;
- one final source archive under the task output directory.

## Non-goals

- an always-visible page or resolution overlay;
- automatic “clearest source” scoring from a single page sample;
- cross-source title deduplication or merged chronology;
- automatic bulk import of every remote favorite;
- bidirectional or background cloud-favorite synchronization;
- remote favorite add/remove actions;
- persisting the R18 reveal state across process death;
- replacing, resigning, or silently trusting the user's Keiyoushi/TheNano extensions;
- porting the full ManyueHD application into Mihon.

## Acceptance criteria

The work is acceptable when:

1. Normal reading contains no page/resolution overlay, and summoned reader controls show the correct current page plus pre-processing source dimensions.
2. Every fresh app process hides confirmed R18 content across Library, Browse, and Cloud Favorites; one session toggle reveals it everywhere, and process death resets it.
3. CopyManga, JM, and PICACG cloud favorites are accessible from one screen through separately failing, paged providers.
4. Cloud items open and enter the library under the user's existing target source IDs, with no need to uninstall or migrate Keiyoushi/TheNano sources.
5. Missing components, login expiry, offline operation, and malformed remote responses result in actionable states without crashes or leaked credentials.
6. Automated checks pass where the local toolchain supports them, and all remaining device-only checks are disclosed.
