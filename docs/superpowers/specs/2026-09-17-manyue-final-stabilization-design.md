# Manyue Final Stabilization Design

## Scope

Stabilize the existing Manyue integration in the Mihon reader without changing Mihon's database, download, extension, or source systems. Existing Manyue classes and Reader entry points remain in place. MangaDex behavior is explicitly out of scope.

## Reader settings

Keep the existing `GeneralSettingsPage` section. Expose the four enhancement modes, classic strength, and foldable `AUTO`, `FULL`, and `MANUAL` choices. Manual width is stored in the existing `manyueFoldableTargetWidth` preference. Mode changes advance a Manyue session generation and cancel stale work so an old AI completion cannot refresh the reader after OFF is selected.

## Native AI execution

Package ARM64 native files in extracted legacy JNI form so `applicationInfo.nativeLibraryDir/libmanyue_realesr.so` is a real executable file. Before launch, validate the executable, `libncnn.so`, model files, input, and writable output location. Run the PIE executable with `LD_LIBRARY_PATH` pointing at `nativeLibraryDir`, redirect combined output to a bounded diagnostic file, enforce the timeout, and destroy the process when its request is cancelled. Always delete request input, native output, diagnostic, and segment-working files after success, failure, cancellation, or timeout.

## Scheduling and stale-result protection

Use one worker and the existing priorities: current `100`, `+1` `30`, `+2` `20`, `+3` `10`. A pure planner defines that window. Viewer page selection proactively schedules the next three chapter pages through Mihon's existing page loader and stream, while visible holders continue to schedule the current page. Pager and Webtoon use `ReaderPage.index`, not adapter positions containing transition pages.

Each request captures page identity, selected mode, and a monotonically increasing session generation. The UI replacement requires all of these to still match: current bridge token, identity, mode, generation, and READY state. Chapter changes, mode changes, holder recycle/detach, and viewer destruction cancel owned work. A running native process is destroyed rather than merely ignoring its eventual result.

## Foldable width

Initialize foldable state from `resources.configuration` during Reader creation, not only from `onConfigurationChanged`. Reapply the policy to current Pager/Webtoon view trees on every relevant configuration change and restore `MATCH_PARENT` on outer screens or FULL mode.

AUTO width uses current screen width and Golden Reference samples only. It does not reuse the AI `2344px` cap. For even samples, width and aspect-ratio medians use the lower median. Webtoon relayout captures the visible adapter position and top offset, reapplies widths, then restores that anchor.

## Memory and long images

Reject AI before native launch when the predicted 2x output or final display allocation exceeds a conservative pixel budget. Cached parts are decoded and recycled one at a time. A final display bitmap is allocated only after its manifest dimensions pass the same budget; otherwise the reader keeps the original image. OFF returns the original source before reading or copying it.

## Verification boundary

Automated verification covers pure policies, stale-result guards, scheduling plans, Kotlin compilation, JVM tests, APK assembly, APK entries, ELF metadata, and packaged assets. Since no ADB device is connected, native execution, visual replacement, fold transitions, scroll anchoring, and sustained memory behavior remain explicitly marked `需要用户真机操作` in the device checklist and limitations.
