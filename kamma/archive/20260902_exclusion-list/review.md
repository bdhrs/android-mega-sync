## Thread
- **ID:** 20260902_exclusion-list
- **Objective:** Let the user exclude specific files/folders from sync, browsed from Local or MEGA, via a simple add/delete list.

## Files Changed
- `app/src/main/java/org/bodhirasa/androidmegasync/sync/IgnoreRule.kt` — new `PathListIgnoreRule` (exact path + subtree prefix match)
- `app/src/main/java/org/bodhirasa/androidmegasync/sync/ExclusionStore.kt` — new SharedPreferences store for the exclusion list
- `app/src/main/java/org/bodhirasa/androidmegasync/MainActivity.kt` — wire ignore rule into sync, "Exclusions" button, mid-sync button lock, readable local path display
- `app/src/main/java/org/bodhirasa/androidmegasync/SyncWorker.kt` — wire ignore rule into background sync
- `app/src/main/java/org/bodhirasa/androidmegasync/ExclusionsActivity.kt` — new: list current exclusions with per-row Delete, launches the picker
- `app/src/main/java/org/bodhirasa/androidmegasync/BrowsePickerActivity.kt` — new: one-level-at-a-time drill-down picker for Local/MEGA, pre-ticks existing exclusions
- `app/src/main/java/org/bodhirasa/androidmegasync/local/SafLocalStore.kt` — new `listChildren(path)` (one-level, no content read)
- `app/src/main/java/org/bodhirasa/androidmegasync/sync/Synchronizer.kt` — "remote" → "mega" in progress-log label
- `app/src/main/res/layout/activity_main.xml`, `activity_exclusions.xml`, `activity_browse_picker.xml`, `row_exclusion.xml`, `row_browse_item.xml` — new/updated layouts
- `app/src/main/AndroidManifest.xml` — register `ExclusionsActivity`, `BrowsePickerActivity`
- `app/src/test/java/org/bodhirasa/androidmegasync/sync/IgnoreRuleTest.kt` — new unit tests for `PathListIgnoreRule`
- `app/src/test/java/org/bodhirasa/androidmegasync/sync/SyncEngineTest.kt` — added `excludedFolderIsSkippedEntirely` (combined engine + `PathListIgnoreRule` coverage)

## Findings
| # | Severity | Location | What | Why | Fix |
|---|----------|----------|------|-----|-----|
| 1 | major | `BrowsePickerActivity.kt:68-84` | Fast double-navigation raced two background loads; the last-finishing one could overwrite the list with a stale folder's contents while the header showed the current path | Confusing near a picker used right before "Exclude" — user could exclude items believing they were from a different folder | Fixed: `load()` callback now bails if `path != currentPath` or the activity is finishing |
| 2 | minor | `BrowsePickerActivity.kt` | In-flight load thread kept posting to `runOnUiThread` after `finish()` | Wasted work / small leak window, not a crash | Fixed as part of the same guard (`isFinishing` check) |
| 3 | minor | `MainActivity.kt` `readableLocalPath` | `DocumentsContract.getTreeDocumentId` throws on a malformed tree URI, unguarded, on every `onCreate` | Low risk today (URI always comes from `OpenDocumentTree()`), but would make the main screen permanently uncrashable-into if the stored string were ever malformed | Fixed: wrapped in `runCatching { }.getOrDefault(uriString)` |
| 4 | minor | `SyncEngineTest.kt` | No test combined `PathListIgnoreRule` with `SyncEngine.diff` end-to-end (only `GlobIgnoreRule` had one) | Real coverage gap flagged in the spec's own file list and left undone | Fixed: added `excludedFolderIsSkippedEntirely` |
| 5 | nit | `IgnoreRuleTest.kt` | No test pinned the subtree-match boundary (`"archive"` vs `"archive-old"`) | A future refactor to naive `startsWith(it)` wouldn't be caught | Fixed: added `similarlyNamedSiblingIsNotIgnored` |
| 6 | minor | `ExclusionStore.kt` (CodeRabbit) | Newline-delimited path encoding would corrupt a path containing a literal `\n` | Android/SAF and MEGA disallow newline characters in file/folder names, so this can't occur in practice | Accepted, not fixed — would add defensive complexity against an input the platform doesn't produce |

## Fixes Applied
- Race condition in the browse picker's background load (major)
- Orphaned background-thread callback after `finish()` (minor)
- Unguarded `DocumentsContract` call (minor)
- Missing engine-level exclusion test (minor)
- Missing subtree-boundary unit test (nit)
- Stale "Source"/"Destination" wording and FakeMegaClient-vs-real-device wording left over from an earlier draft, in `spec.md`/`plan.md` (CodeRabbit, minor)

## Test Evidence
- `just check` (`./gradlew --offline assembleDebug test`, whole project, debug + release unit test variants) → pass, 20 tests including the 2 new ones
- `just deploy` → installed and manually exercised on a real device (Pixel 8 Pro, live MEGA account): add exclusion from Local, add from MEGA, run sync and confirm skipped, delete an exclusion and confirm it syncs again, mid-sync button lock, pre-ticked checkboxes, browse header — all confirmed by the user during the thread
- Independent subagent re-ran `./gradlew --offline test` itself (not trusted from prior claims) and confirmed pass

## Not Verified
- No automated instrumentation/UI test exists or was added for the two new Activities — all UI verification was manual, on one physical device, one Android version. Rotation/process-death mid-picker was not exercised.
- The accepted `ExclusionStore` newline-encoding limitation (finding #6) has no regression test, since the input it guards against is believed unreachable — flagged, not proven impossible.

## Verdict
PASSED
- Review date: 2026-09-02
- Reviewer: independent subagent (correctness/concurrency/architecture pass) + CodeRabbit CLI (doc drift, encoding robustness) + Claude (fixes applied, spec/plan sync)
