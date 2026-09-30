# ManyueNext v5 delivery verification

## Implemented

- Reader controls show page number and source image dimensions only while the translucent reader UI is visible.
- Dimensions are captured from the source image bounds before image processing; late updates from an old page cannot overwrite the selected page.
- R18/NSFW content is hidden by default for each app process. The Library, source browse and cloud favorites use the same session switch. Enabling it is intentionally not persisted, so a fresh process starts hidden again.
- Cloud favorites has one Browse entry and separate CopyManga, JM and PICACG connector APKs. Connectors are hidden from normal source/migration lists and are resolved by exact target source ID.
- Cloud favorites are read-only and open through the existing Keiyoushi/TheNano target source. They are not copied into a different local source.

## Verification performed

```text
:source-api:testDebugUnitTest
:source-api:assembleDebug
:app:testDebugUnitTest --tests eu.kanade.tachiyomi.ui.reader.metadata.* --tests eu.kanade.tachiyomi.ui.adult.*
:app:compileDebugKotlin
:app:assembleDebug
:cloud-connectors:copymanga:assembleDebug
:cloud-connectors:jm:assembleDebug
:cloud-connectors:picacg:assembleDebug
```

All commands completed successfully with the bundled JDK 21 and Android SDK. The JVM temporary directory was set to `C:\jt` because the Windows JDK/Gradle loopback path otherwise fails on this host.

## Scope notes

- The connector settings use an encrypted per-connector session token. This keeps credentials out of ordinary preferences and logs, but it does not add a second embedded browser/password-login flow to Mihon.
- No remote favorite mutation, background synchronization, ads, forced sharing, or bulk import was added.
- Device installation, real account sessions, and live endpoint acceptance still require an Android device/network session; the delivered build verification is source, unit-test, compile, and APK packaging verification.
