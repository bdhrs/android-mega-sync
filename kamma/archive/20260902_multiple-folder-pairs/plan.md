# Plan — Multiple folder pairs

## Architecture Decisions
- **Exclusions keyed by pair id in their own prefs file**, mirroring `LastSyncStore`,
  rather than a new field in the `SyncPair` record. Keeps `SyncPairCodec` unchanged and
  avoids inventing a third separator inside a tab-separated field.
- **Legacy migration inside `ExclusionStore`**: on construction, a legacy `paths` key is
  moved to the first stored pair's id and removed. One place, runs once.
- **Extract the sync wiring into `PairSyncer`** instead of copying it a third time.
  `MainActivity` (sync all), `PairActivity` (sync one) and `SyncWorker` all call it.
  This removes existing duplication rather than adding abstraction.
- **The old main screen becomes `PairActivity`**, per the user's suggestion; the new
  `MainActivity` is only a list + add + sync all + log out.
- **No pair name field**; the row label is derived from the folders.
- **Pair id allocation is a pure function** (`nextPairId`) so it is unit-testable
  without a `Context`, following the `LocalScanPolicy` precedent.
- **Visual coherence via a small `styles.xml`** (heading, body, row title/subtitle,
  primary/danger button) applied across all five layouts. No theme change.

## Phase 1 — Per-pair data layer
- [x] Add `nextPairId(existing: List<SyncPair>): String` to `SyncPair.kt` returning
      `pair-<max+1>`, and `pairLabel(pair, exclusionCount)`-style pure display helpers
      used by the list row.
  → verify: new cases in `SyncPairCodecTest` (or a new `SyncPairsTest`) assert
    `nextPairId(emptyList()) == "pair-1"`, that it skips existing ids including a
    non-numeric one, and the label text for an unconfigured pair; `just test` passes.
- [x] Key `ExclusionStore` by pair id: `load(pairId)`, `save(pairId, paths)`,
      `remove(pairId)`, plus one-time migration of the legacy global `paths` key onto
      the first stored pair.
  → verify: `just build` compiles; grep confirms no remaining call site uses the
    no-arg form.
- [x] Add `LastSyncStore.remove(pairId)` and a `SyncPairStore.remove(id)` /
      `upsert(pair)` pair-aware API; keep `single()` only if still used.
  → verify: `just build` compiles; grep for `setSingle` returns no production hits.
- [x] Phase verification: `just check`.
  → verify: build and all unit tests pass.

## Phase 2 — One sync path for one pair
- [x] Add `sync/PairSyncer.kt` with a single entry point that takes a context, a
      `SyncPair`, `onProgress` and `shouldCancel`, builds the ignore rule from that
      pair's exclusions, the `SafLocalStore` with `LocalScanPolicy.fromLastSync`, runs
      `Synchronizer`, and saves the pair's new baseline.
  → verify: `just build` compiles; read the diff and confirm the wiring is
    line-for-line what `MainActivity.syncNow` and `SyncWorker.doWork` did, including
    that the baseline is saved after a cancelled run exactly as before.
- [x] Rewrite `SyncWorker.doWork()` to loop every pair with a non-empty local folder,
      calling `PairSyncer`, and to return retry if any pair failed.
  → verify: `just build`; confirm the hardcoded `PAIR_ID` constant is gone.
- [x] Phase verification: `just check`.
  → verify: build and unit tests pass.

## Phase 3 — Pair screen and pair list
- [x] Create `PairActivity` + `activity_pair.xml` from the current `MainActivity` /
      `activity_main.xml`: takes a pair id extra (absent = new pair), pick local, pick
      MEGA, exclusions, sync now/cancel, status, delete pair with a confirm dialog that
      also clears that pair's exclusions and baseline.
  → verify: `just build`; on device, add a pair, pick both folders, reopen the screen
    and confirm both are still shown.
- [x] Rewrite `MainActivity` + `activity_main.xml` as the pair list: heading, `ListView`
      of pairs with `row_pair.xml` (local → MEGA, exclusion count), empty state, "Add
      folder pair", "Sync all", "Log out". Refresh the list in `onResume`.
  → verify: on device, two configured pairs both appear with correct folders and
    exclusion counts; deleting one leaves the other's row unchanged.
- [x] Pass the pair id through `ExclusionsActivity` and `BrowsePickerActivity` and use
      the per-pair exclusion store in both; drop `SyncPairStore.single()` use there.
  → verify: on device, set an exclusion on pair A, open pair B's exclusions and confirm
    it is empty; re-open pair A's and confirm it is still listed.
- [x] Implement "Sync all" as a sequential run over configured pairs through
      `PairSyncer`, with a status line naming the current pair and the existing
      cancel-button behaviour.
  → verify: on device, tap Sync all with two pairs, observe both pair names in the
    status, and cancel mid-run.
- [x] Register `PairActivity` in `AndroidManifest.xml`.
  → verify: `just build`; tapping a row opens the screen without a crash.
- [x] Phase verification: `just check`.
  → verify: build and unit tests pass.

## Drift from the plan as written
- The user asked mid-thread for the "Log out" button (pre-existing, from the
  exclusion-list commit) to be removed. It is gone from the list screen and from
  `activity_main.xml`; `SessionStore.clear()` is now uncalled.
- Review found four major defects that the plan's design did not prevent; the fixes are
  part of this thread (see `review.md`): a process-wide `SyncRuns` lock replacing the
  per-Activity `syncing`/`cancelRequested` fields, `SyncPairStore.allocateId` handing out
  ids from a stored counter so a deleted pair's id is never reissued,
  `SyncPairStore.remove` clearing exclusions and baseline together, `PairSyncer` skipping
  the baseline write for a pair deleted mid-run, and `PairActivity` keeping its pair id
  across rotation and process death.
- `ExclusionStore`'s migration moved out of its constructor into an explicit
  `migrateLegacyGlobalList(context)` called from `MainActivity.onCreate` and
  `SyncWorker.doWork`.
- The pure list transforms behind `upsert`/`remove`/`allocateId` were extracted into
  `SyncPair.kt` so they are unit-testable without a `Context`.
- `styles.xml` and `colors.xml` were created during Phase 3 rather than Phase 4, because
  the new layouts were written once against them instead of being written plain and then
  rewritten. Phase 4 then applied the same styles to the pre-existing screens.
- `activity_login.xml` was restyled too — it is a fifth screen and would otherwise have
  been the only one with its own padding and heading size.
- Three small extractions the plan did not name, each removing duplication the split
  between the two screens would otherwise have created: `MegaSession` (the resume-once
  block that lived in `MainActivity` and inline in `BrowsePickerActivity`),
  `readableLocalPath` in `LocalPaths.kt` (needed by both the list row and the pair
  screen), and `syncSummary` (the finished-run wording, needed by "Sync all" and by a
  single pair).
- `PairDisplay` holds the pure display helpers; the plan sketched them inside
  `SyncPair.kt`, but `readableLocalPath` needs Android APIs and the pure half must stay
  loadable under plain JUnit.

## Phase 4 — Visual coherence
- [x] Add `res/values/styles.xml` with screen-padding, heading, body/status, row title,
      row subtitle and primary/danger button styles, and apply them across
      `activity_main`, `activity_pair`, `activity_exclusions`, `activity_browse_picker`,
      `row_pair`, `row_exclusion` and `row_browse_item`. Move the hardcoded sync-button
      colours out of `MainActivity`/`PairActivity` into colour resources.
  → verify: `just check` passes; on device, walk all five screens and confirm identical
    padding, heading size and button width/colour language.
- [x] Full smoke: `just check`, then a real two-pair sync on device.
  → verify: unit tests pass; both pairs sync, file counts land as expected on both
    sides.

## Finalize
- [ ] Review, then finalize the thread.
