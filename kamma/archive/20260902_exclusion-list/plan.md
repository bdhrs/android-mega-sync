# Plan: Sync exclusion list

See `spec.md` for full design.

## Tasks

- [x] 1. `PathListIgnoreRule` in `sync/IgnoreRule.kt` + unit test (exact-path match, subtree match, non-matching path unaffected)
- [x] 2. `ExclusionStore` in `sync/ExclusionStore.kt` (SharedPreferences, `\n`-delimited paths, `load()`/`save()`)
- [x] 3. Wire `ignore = PathListIgnoreRule(ExclusionStore(...).load())` into `SyncEngine(...)` at `MainActivity.kt:143` and `SyncWorker.kt:32`
- [x] 4. `BrowsePickerActivity` + `activity_browse_picker.xml`: one-level-at-a-time drill-down picker (local via new `SafLocalStore.listChildren(path)`, remote via filtered `MegaClient.listFolder(...)`), checkbox-per-row selection persists across navigation, "Exclude" button returns selected paths as activity result — revised from the original flat-list design after local scanning proved too slow (see spec.md)
- [x] 5. `ExclusionsActivity` + `activity_exclusions.xml`: `ListView` of current exclusions with per-row Delete, "Browse Local"/"Browse MEGA" buttons launching `BrowsePickerActivity`, replaces the stored list with the picker's returned (edited) selection on result
- [x] 6. `MainActivity`: add "Exclusions" button to `activity_main.xml`, launch `ExclusionsActivity`
- [x] 7. Register `ExclusionsActivity` and `BrowsePickerActivity` in `AndroidManifest.xml`
- [x] 8. Manual smoke check on real device (Pixel 8 Pro, live MEGA account) — add exclusion from each side, run sync, confirm excluded paths skipped; delete exclusion, confirm it syncs again. Also caught and fixed during testing: local browse-picker perf (full-tree scan → one-level drill-down), Local/MEGA terminology consistency, readable local path display, pre-ticking existing exclusions in the picker, browse header, and disabling config buttons mid-sync (all now reflected in spec.md)

## Verify

- `./gradlew testDebugUnitTest` (`just test`) for the unit test suite — actually run, see review.md
- Task 8 was verified live on a real device against a live MEGA account (`just deploy`), not `FakeMegaClient` — a device/adb connection turned out to be available, so that's the coverage this thread actually has, superseding the original "no emulator/device assumed" plan note
