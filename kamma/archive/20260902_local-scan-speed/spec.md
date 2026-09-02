# Spec: Speed up the local scan during sync

## Overview

A manual sync on the user's real vault spends ~2 minutes in the "Scanning local files…" phase before any transfer starts. The scan is `SafLocalStore.snapshot()` (`app/src/main/java/org/bodhirasa/androidmegasync/local/SafLocalStore.kt`), a recursive SAF walk that builds a `FolderSnapshot` for `SyncEngine.diff()`.

Research pass (2026-09-02) found **three independent causes**, all confirmed by reading the code:

**1. Per-file metadata round trips.** The walk uses `androidx.documentfile`'s `DocumentFile`. `dir.listFiles()` queries the children cursor for `COLUMN_DOCUMENT_ID` only; each subsequent `child.name`, `child.isDirectory`, `child.lastModified()` and `child.length()` is then its own separate `ContentResolver.query()`. So the current walk issues roughly **four ContentProvider round trips per file**, purely for metadata that a single cursor query over the children URI could return in one shot (`COLUMN_DOCUMENT_ID`, `COLUMN_DISPLAY_NAME`, `COLUMN_MIME_TYPE`, `COLUMN_SIZE`, `COLUMN_LAST_MODIFIED`).

**2. Excluded folders are scanned anyway.** `IgnoreRule` (built from `ExclusionStore` at both call sites — `MainActivity.syncNow()` and `SyncWorker.doWork()`) is passed only to `SyncEngine`, which filters paths inside `diff()`. The local walk knows nothing about it, so every excluded file — e.g. an entire `.obsidian` tree — is still listed, fully read, and fingerprinted before being discarded.

**3. Every file's full content is read and hashed on every sync.** For each file the walk calls `readDoc()` (opens the SAF stream and reads all bytes into memory) and passes them to `mega::contentFingerprint`, which writes the bytes to a temp file in the cache dir, calls `api.getCRC(path)`, and deletes the temp file. Nothing is cached between runs, so an unchanged vault does the full read-write-hash-delete cycle for every file on every sync.

## What it should do

Three changes, each independently verifiable, in this order:

**A — One cursor query per directory.** Replace `DocumentFile` in the recursive walk (`snapshot()`/`walk()`) and in `listChildren()` with a direct `contentResolver.query()` on `DocumentsContract.buildChildDocumentsUriUsingTree(...)`, projecting document id, display name, mime type, size and last-modified. Recurse using `buildDocumentUriUsingTree(treeUri, childDocumentId)`. `DocumentFile` may stay in the write paths (`write`, `delete`, `makeDir`, `resolve`, `ensureDir`, `read`) — those are per-action, not per-file-in-vault, and are not the bottleneck.

**B — Prune excluded paths during the walk.** Pass the existing `IgnoreRule` into `SafLocalStore` (both construction sites already build one) and skip any child whose path is ignored — for a directory, skip the whole subtree without descending.

**C — Cache the local fingerprint against size + modified time.** Persist, per path, the local size and modified time observed at the last successful sync alongside the fingerprint already stored in `LastSyncState`. During the walk, if a file's current size and modified time both match the stored pair, reuse the stored fingerprint and skip the content read and CRC entirely. Otherwise read and hash as today. `Synchronizer` builds the lookup from the `LastSyncState` it is already given and hands it to `snapshot()`.

Also add scan timing to the progress line (e.g. `Scanning local files… 1,842 files in 6s`) so before/after is measurable on the device without adb.

## Assumptions & uncertainties

**Verified by reading the code / androidx source, not assumed:**
- `DocumentFile.listFiles()` projects only `COLUMN_DOCUMENT_ID`; `name`, `isDirectory`, `length()` and `lastModified()` each issue their own query. This is the basis of change A.
- `IgnoreRule` reaches only `SyncEngine`, never the local walk. This is the basis of change B.
- `SafLocalStore` is constructed in exactly three places: `MainActivity` (line ~156), `SyncWorker` (line ~33), and `BrowsePickerActivity` (line ~92, which only calls `listChildren`). Change B's new constructor parameter must default so the picker site needs no exclusion rule.
- The local and remote fingerprint schemes are deliberately the same string form (`"<size>:<crc>"`, from `api.getCRC(path)` locally and `api.getCRCFromFingerprint(node.fingerprint)` remotely), which is why `SyncEngine` can short-circuit on `l.fingerprint == r.fingerprint`. None of the three changes touches the fingerprint value itself, so cross-side comparison semantics are unchanged.
- `LastSyncCodec` is tab-separated with `split(FIELD_SEP, limit = 3)`. Change C adds two fields, so `decode` must tolerate legacy 3-field records.
- `LastSyncState.pair()` records only paths present on **both** sides, so the cache covers files already in sync — exactly the ones worth skipping. Local-only files are read and hashed anyway because they are about to be uploaded.

**Accepted trade-offs (decided with the user, 2026-09-02):**
- The cache key is size + modified time — the same quick-check `rsync` uses by default. An edit that leaves both size and mtime identical would be missed until the file is touched again. This is not purely hypothetical: FAT/exFAT external storage — an ordinary folder-picker target — stores last-modified at 2-second granularity, so a same-length edit landing in the same 2-second tick as the previous one is missed. The Obsidian vault this app was built for lives on internal storage, where the granularity is milliseconds. Modified time is used *only* as a local cache-validity key; it is never compared between the local and remote sides, so the engine's mtime-independent cross-device identity is preserved.
- Pruning excluded paths from the walk also removes them from the baseline that `LastSyncState.pair()` records. Consequence: if a path is later un-excluded, it has no recorded history, so identical content is still recognised as in sync (the engine returns early on equal fingerprints) but differing content resolves by newer-wins instead of by which side changed. Today, excluded paths *do* get a baseline recorded. This is a deliberate, documented behaviour change.
- After a `Download`, `Synchronizer` copies the **remote** `FileEntry` into the new local baseline, so the cached size/mtime for that path describes the MEGA node, not the freshly written local file. The next scan will see a mismatch and re-hash that one file. This fails safe (toward re-hashing) and is not worth extra machinery.

**Remaining uncertainty:**
- The actual speedup is unmeasured. Change A should dominate for a vault of many small notes; change C should dominate on the second and later syncs. Task 1 captures a baseline number so each change can be attributed.
- Whether every SAF provider on the device populates `COLUMN_SIZE` and `COLUMN_LAST_MODIFIED` for the external-storage tree. If either comes back null, the file must fall back to reading and hashing rather than being treated as unchanged.

## Constraints

- No new dependencies (`tech.md`: no Room, no Compose; SharedPreferences or one SQLite table only). The cache rides in the existing `last_sync` SharedPreferences via `LastSyncCodec`.
- Sync correctness cannot regress: no file may be silently skipped except under the documented size+mtime rule, and the conflict/deletion semantics in `SyncEngine` must not change at all.
- `LocalStore` is the seam the unit tests use (`InMemoryLocalStore`, `SynchronizerTest`, `SyncEngineTest`). Any signature change to `snapshot()` should use default arguments so existing tests keep compiling.
- Offline build gate: `just check` (`./gradlew --offline assembleDebug test`).
- The upgrade path must not force a re-transfer: an existing stored `LastSyncState` in the old 3-field format must decode cleanly, producing one slow scan and no spurious uploads or downloads.

## How we'll know it's done

- On the user's real vault, the first sync after the change is measurably faster than the recorded ~2 minute baseline, and a second sync with no changes made is faster again (cache hit path).
- A no-change sync reports zero uploads, zero downloads, zero deletions — i.e. the speedups did not cause a spurious action.
- A file edited on the phone between two syncs is still detected and uploaded.
- A file excluded via the exclusion list is neither read nor acted on.
- `just check` passes, with new unit tests covering: the fingerprint cache hit/miss rule, legacy 3-field `LastSyncState` decoding, and ignore-pruning during snapshot.

## What's not included

- The remote (MEGA) scan. `SdkMegaClient.listFolder()` walks in-memory SDK nodes and already gets fingerprints from node metadata without downloading content.
- Parallelising the walk or the transfers.
- Any change to the reconcile logic, conflict policy, or the fingerprint scheme itself.
- Replacing `DocumentFile` in the write/read-single-file paths.
- Incremental or watcher-based sync (`FileObserver`, `DocumentsContract` change notifications).
