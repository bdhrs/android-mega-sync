## Thread
- **ID:** 20260902_multiple-folder-pairs
- **Objective:** Any number of folder pairs, each with its own local folder, MEGA folder and exclusions, shown as a list.

## Files Changed
- `app/src/main/java/.../MainActivity.kt` — now the pair list: rows, empty state, add, "Sync all", log out
- `app/src/main/java/.../PairActivity.kt` — new; the old main screen, scoped to one pair, plus delete
- `app/src/main/java/.../sync/PairSyncer.kt` — new; the single place a pair becomes a running sync
- `app/src/main/java/.../sync/SyncRuns.kt` — new; process-wide "which sync is running" state and cancel flag
- `app/src/main/java/.../sync/SyncPair.kt` — pure id allocation and pure list transforms for the store
- `app/src/main/java/.../sync/SyncPairStore.kt` — `find`/`upsert`/`remove`/`allocateId`; remove also clears dependent state
- `app/src/main/java/.../sync/ExclusionStore.kt` — keyed by pair id; legacy global list migrated explicitly
- `app/src/main/java/.../sync/LastSyncStore.kt` — `remove(pairId)`
- `app/src/main/java/.../sync/PairDisplay.kt` — new; pure on-screen wording for a pair
- `app/src/main/java/.../SyncWorker.kt` — syncs every pair, holds the sync lock, runs the migration
- `app/src/main/java/.../MegaSession.kt`, `LocalPaths.kt`, `SyncSummary.kt`, `SyncRunWatch.kt` — new; extracted helpers shared by the two screens
- `app/src/main/java/.../ExclusionsActivity.kt`, `BrowsePickerActivity.kt` — operate on the pair they were opened for
- `app/src/main/res/values/styles.xml`, `colors.xml` — new; one visual language for all five screens
- `app/src/main/res/layout/*.xml` — restyled; `activity_pair.xml` and `row_pair.xml` new
- `app/src/main/AndroidManifest.xml` — registers `PairActivity`
- Log-out button removed from the list screen and `activity_main.xml` at the user's request
- `app/src/test/java/.../sync/SyncPairsTest.kt` — new; 13 tests over the pure helpers

## Findings
Two reviewers: CodeRabbit (5 findings) and an independent from-scratch subagent (12 findings + per-claim verdicts). Findings that were real and are fixed:

| # | Severity | Location | What | Why | Fix |
|---|----------|----------|------|-----|-----|
| 1 | major | `ExclusionStore.kt` migration | Legacy exclusion key was deleted even when no pair existed to receive it | Silent loss of the user's exclusion list on upgrade | Return early, keeping the key, until a pair exists |
| 2 | major | `PairActivity.kt:53` | New-pair screen re-derived its id on rotation/process death | The just-configured pair was orphaned and a second record created | Id carried in `onSaveInstanceState`, allocated once from the store |
| 3 | major | `MainActivity`/`PairActivity` sync state | `syncing`/`cancelRequested` were Activity fields | A recreated screen could start a second sync of the same pair; both wrote that pair's baseline, and a wrong baseline plans deletions | Process-wide `SyncRuns` lock; every caller (both screens and the worker) must acquire it |
| 4 | major | `PairSyncer.kt` baseline write | A run finishing after its pair was deleted re-created that pair's baseline | Orphaned baseline could later be inherited by another pair | Skip the write when the pair is no longer stored |
| 5 | major | `SyncPair.kt` / `SyncPairStore.kt` | Ids were max+1 over current pairs, so deleting the highest freed its id; cleanup of dependent state was three loose calls in a dialog callback | A new pair could inherit a deleted pair's exclusions and baseline | Ids come from a stored counter; `remove` clears exclusions and baseline itself |
| 6 | minor | `SyncPair.kt` `nextPairId` | A bare numeric id (`"7"`) was parsed as a pair number | Wrong next id | Require the `pair-` prefix; regression test added |
| 7 | minor | `PairActivity` vault-root pick | Choosing vault root on a new pair persisted nothing while the screen showed it as chosen | Silent loss of a real choice | Persist on either pick |
| 8 | minor | `ExclusionsActivity`/`BrowsePickerActivity` | Absent pair-id extra defaulted to the `""` key | Exclusions written to a phantom bucket | `finish()` instead |
| 9 | minor | `PairActivity` opened for a deleted pair | Silently became a new pair under a dead id | Delete would act on an unstored id | `finish()` instead |
| 10 | minor | `PairSyncer` | Left `MegaSession.ensure` to each caller | A fourth caller would get an unauthenticated client | `PairSyncer` ensures the session itself |
| 11 | minor | `ExclusionStore` constructor | Constructing it migrated another store, on the UI thread, once per render | Construction order became significant | Explicit `migrateLegacyGlobalList(context)` at both entry points |
| 12 | minor | `activity_main.xml` status | Unbounded "Sync all" report shrank the list and pushed buttons off a non-scrolling screen | Controls unreachable | `maxLines` + ellipsize |
| 13 | minor | `PairActivity` dialogs | Not dismissed on destroy | Window leak on rotation | Held and dismissed in `onDestroy` |
| 14 | nit | `styles.xml`, `activity_browse_picker.xml`, `activity_login.xml` | Heading size overridden inline; dead `AppEmptyState` height; login inputs unstyled | The "one visual language" goal was not actually met | `AppSubHeading` and `AppInput` styles; dead item removed |

Declined, with reason:
- CodeRabbit's three `spec.md` findings asked for draft-pair semantics, a fake-persistence test boundary, and a crash-safe migration marker protocol. None is a defect: incomplete pairs are already filtered out of both sync paths, and the rest is scope the spec deliberately excludes for a single-user sideloaded app.
- Reviewer nits kept as-is: a fresh `ArrayAdapter` per render (lists are a handful of rows), the worker re-running already-succeeded pairs on retry (correct, and the alternative is per-pair retry state), and row horizontal padding (the claim was wrong — the sibling rows have no horizontal padding either).

## Dead Code
- `SessionStore.clear()` has no caller now that the log-out button is gone (removed at
  the user's request mid-thread). Left in place as a one-line store API rather than
  deleted, so a future log-out or account switch has it. Noted, not removed.

## Fixes Applied
All 14 findings above. `nextPairId`'s fix was mutation-checked: reverting it turns `ignoresNumericIdsWithoutThePairPrefix` red, confirming the test binds to production code.

## Test Evidence
- `just check` — offline `assembleDebug` + `test` (scope: whole project, both variants) → pass
- `./gradlew --offline test --rerun-tasks` (scope: all 8 test classes, 54 tests, no caching) → pass, 0 failures
- Mutation check: `nextPairId` prefix guard reverted → 1 test failed as expected; restored → 54 pass
- `./gradlew --offline compileDebugKotlin --rerun-tasks` (scope: warnings) → only the pre-existing deprecated `startUpload` warning
- Resource sweep by the independent reviewer: every `R.id`, `R.layout`, `@style/`, `@color/` reference resolves; no duplicates
- On device (user, before the review fixes): both pairs configured, per-pair exclusions isolated, per-pair sync, "Sync all", delete, and the five screens' visual pass all confirmed

## Not Verified
- **The review fixes have not been tested on device.** The user's on-device pass covered the build before findings 1–14 were applied. The lifecycle fixes in particular (rotation mid-sync, process death on the add-pair screen, worker-vs-manual contention) are argued from code and unit tests only; nothing about them is device-verified.
- Rotation/process-death paths, the concurrent-sync lock, and the worker deferring to a manual run are not covered by unit tests — they need a `Context` and the project has no Robolectric.
- The legacy exclusion migration is only reachable on a real upgraded install; unit tests cannot touch it.
- One residual on that migration: an install with a legacy list but no stored pair would hand the list to whichever pair is created first. That cannot happen through the old UI (exclusions required a stored pair), and retaining beats dropping — files get skipped, never deleted.
- A recreated screen shows "A sync is running…" without the live progress lines; it recovers the buttons when the run ends, but the running run's own progress text is lost with the old instance.

## Verdict
PASSED
- Review date: 2026-09-02
- Reviewer: CodeRabbit CLI + independent subagent (zero-memory, instructed to attack the author's claims); fixes and this file by kamma inline
