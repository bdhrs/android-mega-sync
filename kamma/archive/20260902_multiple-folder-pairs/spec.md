# Spec — Multiple folder pairs

## Overview
The app currently syncs exactly one folder pair. `SyncPairStore` already persists a
*list* of pairs and `LastSyncStore` is already keyed by pair id, but everything above
that layer assumes one: `SyncPairStore.setSingle()`, a hardcoded `"pair-1"` id in
`MainActivity` and `SyncWorker`, a single global `ExclusionStore`, and a main screen
whose controls edit "the" pair.

This thread makes folder pairs plural: any number of pairs, each with its own local
folder, MEGA folder and exclusion list, presented as a list on the main screen. The
existing main screen becomes the per-pair screen reached by adding or tapping a pair.

## What it should do
- Main screen shows a list of folder pairs, one row per pair: local folder → MEGA
  folder, plus how many exclusions that pair has. Empty state when there are none.
- "Add folder pair" opens the pair screen with nothing configured; the pair is
  persisted as soon as either folder is picked (picking MEGA's vault root is a real
  choice and an empty remote path, so persistence cannot be inferred from emptiness).
- Tapping a row opens the pair screen for that pair: pick local folder, pick MEGA
  folder, exclusions, sync now / cancel, status, delete pair.
- Exclusions are per pair. The exclusions screen and the browse picker operate on the
  pair they were opened for.
- "Sync all" on the main screen syncs each configured pair in turn, showing which pair
  is running and its progress, cancellable between files as today.
- Background `SyncWorker` syncs every configured pair, not just the first.
- Deleting a pair also drops its exclusions and its last-sync baseline.
- A pair's label is derived from its folders — no name field.
- The one existing global exclusion list is migrated to the existing pair on first run,
  from every entry point that can reach a sync — including the background worker, since
  it may run before the app is next opened.
- Only one sync runs in this process at a time. A screen recreated mid-sync shows the
  run as in progress rather than offering a second one, and the background worker defers
  to a manual run.
- Pair ids are never reused. Deleting a pair takes its exclusions and baseline with it.
- No log-out control. The button existed on the old main screen; the user asked for it
  to go, so the list screen is pairs only. The stored session token is still cleared by
  `SessionStore.clear()`, which now has no caller.

## Assumptions & uncertainties
Verified by reading the code:
- `SyncPairStore.load()/save()` already handle lists; `SyncPairCodec` round-trips
  multiple records (tested in `SyncPairCodecTest`).
- `LastSyncStore` is keyed by pair id string; only the caller's hardcoded `"pair-1"`
  ties it to one pair. It has no delete method — one is needed.
- `ExclusionStore` has a single `paths` key with no pair dimension.
- `Synchronizer.sync()` takes `remoteRoot` + last state and is pair-agnostic already.
- `MainActivity.syncNow()` and `SyncWorker.doWork()` duplicate the same six lines of
  wiring (ignore rule, `SafLocalStore`, `LocalScanPolicy`, engine, save state).
- `BrowsePickerActivity` reads `SyncPairStore.single()` and the global `ExclusionStore`.
- Unit tests are plain JUnit, no Robolectric — nothing needing a `Context` is testable,
  so anything correctness-critical must live in a pure helper (`LocalScanPolicy` set
  this precedent).

Assumptions:
- Pair ids stay opaque strings; `pair-N` numbering is fine for a single-user app.
- Exclusion paths never contain a tab or newline, so keying exclusions by pair id in
  their own prefs file (mirroring `LastSyncStore`) is safer than packing them into the
  pair record.
- Sequential "Sync all" is acceptable; no parallel pair syncing.

## Constraints
- Classic Android Views + AppCompat only. No Compose, no Material, no new dependencies.
- No new persistence engine: `SharedPreferences` and the existing tab/newline codecs.
- Preserve the local-scan performance rules in `AGENTS.md` (no bulk `DocumentFile`
  reads, exclusions applied during the scan, fingerprint reuse by size+mtime).
- Existing exclusions must not be silently lost.

## How we'll know it's done
- `just check` passes (offline build + unit tests).
- Two pairs can be configured with different local folders, MEGA folders and
  exclusions; each syncs correctly and independently on device.
- Deleting one pair leaves the other's exclusions and baseline intact.
- "Sync all" runs both pairs and can be cancelled.
- The app's screens share one visual language: same padding, heading and button style.

## What's not included
- Per-pair sync schedules or intervals; the worker keeps one global 6-hour schedule.
- Per-pair conflict policy, pair reordering, import/export, renaming.
- Parallel syncing of pairs.
- Any change to the sync engine's diff/reconcile logic.
