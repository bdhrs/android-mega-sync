# Plan: Speed up the local scan during sync

See `spec.md` in this directory for the full problem statement, verified facts, and accepted trade-offs. Read it first.

## Architecture Decisions

- **Keep `DocumentFile` for the write paths, drop it only from the walk.** `write`, `delete`, `makeDir`, `read`, `resolve` and `ensureDir` run once per sync *action*, not once per file in the vault, so they are not the bottleneck and rewriting them adds risk for no gain. Only `snapshot()`/`walk()` and `listChildren()` move to raw `ContentResolver` cursor queries.
- **The fingerprint cache lives in the existing `LastSyncState`, not a new store.** `Synchronizer` is already handed the `LastSyncState` and already writes a new one, and `tech.md` rules out Room. Adding two fields to `SyncFingerprints` and to the `LastSyncCodec` TSV record is strictly additive and keeps one persisted concept instead of two.
- **Revised during Phase 3 — a `LocalScanPolicy` object, not parameters on `snapshot()`.** The original decision was to add default-argument parameters to `LocalStore.snapshot()` and thread the cache lookup through `Synchronizer`. Two things forced a change: (a) unit tests here are plain JUnit with no Robolectric and no Android framework, so nothing inside `SafLocalStore` can be tested at all — the size+mtime rule is the riskiest part of this thread (a wrong answer silently misses an edit) and had to be reachable by a test; (b) the call sites already hold both the exclusion list and the `LastSyncState`, so a policy built there needs no new plumbing. Result: `LocalScanPolicy` (two pure methods, unit-tested, called by the real walk), `SafLocalStore` takes it as one defaulted constructor parameter, and the `LocalStore` interface, `snapshot()`'s signature and `Synchronizer`'s sync logic all stayed as they were. (`Synchronizer` *is* touched in this thread, but only by Phase 1's scan-timing reporting, and `InMemoryLocalStore` gained an optional policy in review so the Synchronizer tests can exercise the real scan rules.)
- **Deliberately not abstracted:** no SAF wrapper layer, no generic "metadata provider", no cache store. The cache lookup is a function type `(path, size, modifiedMillis) -> String?` supplied by `LocalScanPolicy.fromLastSync`; the cursor query is inline in `SafLocalStore`.
- **Ordering rationale:** metadata-query first because it is pure mechanism with no semantic or storage change and can be verified in isolation; pruning second because it depends only on plumbing an existing object; the cache last because it is the only change that alters persisted format and reconcile inputs, so it should land on top of an already-verified walk.
- **Scan timing goes in the progress line, not a log.** The user verifies on a real device without adb; a status-line number is the only measurement channel that always works, and it is one line of code.
- **Revised during Phase 1:** the app has a single status line that is overwritten on every progress callback, so a one-shot "scan finished" message would flash and vanish. The two scan summaries are therefore kept as a header on every later progress line *and* returned on `SyncResult`, so the final "Sync done" line still shows them. Cost: one extra field on `SyncResult` and one string concat.

---

## Measurements

Measured 2026-09-02 on the user's own Pixel 8 Pro over USB, same vault and account throughout, driven by tapping Sync and reading the on-screen status. Vault was already in sync, so every timing run transferred nothing and the scan was the whole cost. 862 local files / 872 MEGA files (the 10-file gap is the excluded folder, now pruned locally).

| When | Local scan | Notes |
|---|---|---|
| Before, run 1 | 61.2s | pre-change build already installed (19:20, predates the HEAD commit) |
| Before, run 2 | 63.5s | |
| Before, run 3 | 61.0s | **median 61.2s** |
| After, cold cache | 26.3s | Phases 2 + 3 only — the stored baseline was still in the legacy 3-column format, so every file was read and hashed. **2.3× faster** |
| After, warm cache | 1.0s / 1.3s / 1.0s | Phase 4 active. **~60× faster than baseline** |
| After, one new file | 1.1s | 862 cache hits + 1 real hash, uploaded correctly |
| After review fixes, warm | 0.9–1.2s | column-by-name lookup changed nothing measurable; counts identical |

Phase 2 and Phase 3 could not be attributed separately — both were already in the build that was installed, so 26.3s is their combined effect.

## Phase 1 — Baseline and measurement

- [x] Add elapsed-time and file-count reporting to the two scan phases in `sync/Synchronizer.kt`. Both summaries persist as a header on subsequent progress lines and are carried on `SyncResult` so the final status line keeps them.
  → verify: `just check` passes; run `just deploy`, tap Sync now, and confirm both scan lines show a count and a duration.
- [x] Record the measured baseline (local scan seconds and file count) in the `## Measurements` table above.
  → verify: median of three pre-change runs recorded (61.2s), timed on the device by polling the status line for the local-scan phase.
- [x] Phase verification: `just check`.
  → verify: `./gradlew --offline assembleDebug test` exits 0.

## Phase 2 — One cursor query per directory

- [x] Rewrite `walk()` in `local/SafLocalStore.kt` to query children directly: build the children URI with `DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)` and query the projection `COLUMN_DOCUMENT_ID, COLUMN_DISPLAY_NAME, COLUMN_MIME_TYPE, COLUMN_SIZE, COLUMN_LAST_MODIFIED`. Detect directories by `MIME_TYPE_DIR`. Recurse on the child document id. Keep the existing `FileEntry` output identical (same paths, same `isDir`, same size, same `modifiedMillis`, same fingerprint values).
  → verify: `just check` passes; run `just deploy` and confirm the local scan line reports the **same file count** as the Phase 1 baseline, in less time.
- [x] If `COLUMN_SIZE` or `COLUMN_LAST_MODIFIED` comes back null for a row, fall back to 0 for that field and never treat that file as cacheable in Phase 4.
  → verify: read the new code and confirm null columns cannot produce a "size and mtime matched" cache hit.
- [x] Rewrite `listChildren()` to use the same single-cursor query (it currently pays the same per-child `name`/`isDirectory` round trips).
  → verify: run `just deploy`, open the exclusion browse picker, drill two levels into the local tree, and confirm names and folder/file distinction are correct and the screen loads at least as fast as before.
- [x] Update the comment block above `listChildren()` so it describes the cursor-based approach rather than `DocumentFile.listFiles()`, and update the SAF performance note in `AGENTS.md` to say the per-child metadata round trips were the real cost.
  → verify: neither file still claims `listFiles()` is what the code calls.
- [x] Phase verification: `just check`, then one full sync on the real vault with no changes pending.
  → verify: 38 unit tests pass; cold sync reported 0 uploaded, 0 downloaded, 0 deleted with the same 862-file count as the pre-change build, in 26.3s instead of 61.2s.

## Phase 3 — Prune excluded paths during the walk

- [x] Add a `policy: LocalScanPolicy = LocalScanPolicy()` constructor parameter to `SafLocalStore` (carrying the ignore rule) and skip ignored children in `walk()` — for a directory, do not descend at all.
  → verify: `just check` passes.
- [x] Pass the already-constructed `PathListIgnoreRule` into `SafLocalStore` at both sync call sites (`MainActivity.syncNow()` and `SyncWorker.doWork()`). Leave `BrowsePickerActivity` on the default so the picker still shows excluded folders (they must remain selectable/deselectable).
  → verify: run `just deploy`, exclude a large folder, sync, and confirm the local scan file count drops by that folder's contents and the duration falls with it; then open the picker and confirm the excluded folder is still listed.
- [x] Add unit tests for the pruning rule in `LocalScanPolicyTest` (exact path, whole subtree, nothing pruned by default).
  → verify: `just check` runs `LocalScanPolicyTest`. **Note:** the two-line `continue` inside `walk()` itself cannot be unit-tested without adding Robolectric (ruled out by `tech.md`), so the walk's use of the rule is verified on device instead.
- [x] Phase verification: `just check`, plus a no-change sync on the real vault.
  → verify: tests pass. The scan now reports 862 local files against 872 MEGA files — the excluded folder is genuinely pruned — and the sync still reported 0 deleted, confirming pruning does not read as a local deletion.

## Phase 4 — Cache the local fingerprint against size and modified time

- [x] Extend `SyncFingerprints` in `sync/LastSyncState.kt` with the local size and modified time observed at last sync, and extend `LastSyncCodec` to encode/decode the two extra tab-separated fields. `decode` must accept legacy 3-field records by treating the two new fields as absent (uncacheable).
  → verify: add a unit test that decoding a legacy 3-field record succeeds and yields no usable cache entry, and that encode→decode round-trips a 5-field record.
- [x] Populate the new fields in `LastSyncState.pair()` from the local snapshot entry for each path.
  → verify: `just check` passes; existing `SynchronizerTest` and `SyncEngineTest` still pass unchanged.
- [x] Add `LocalScanPolicy.reusableFingerprint(...)` and use it in `SafLocalStore.walk()`: on a hit, emit the `FileEntry` with the cached fingerprint and skip the content read and the CRC call entirely; on a miss, read and hash as today.
  → verify: `LocalScanPolicyTest` and `LastSyncCodecTest` cover hit, size mismatch, mtime mismatch, unknown path, legacy record and null-metadata cases.
- [x] Build the lookup with `LocalScanPolicy.fromLastSync(ignore, last)` at the two sync call sites, which already load the `LastSyncState`. `Synchronizer` needs no change.
  → verify: `just check` passes.
- [x] Phase verification: on the real vault, run two consecutive syncs with no changes in between, then change a file and sync again.
  → verify: cold 26.3s → warm 1.0s and 1.3s, both zero actions. A throwaway file (not one of the user's notes) was then created, edited with a different size, and edited again at the same size: each sync uploaded exactly that one file with the scan still at ~1.1s. Deleting it produced exactly one remote deletion, and a final settled sync reported zero actions.

## Phase 5 — Review and close out

Completed 2026-09-02. All phases verified on the user's own device.

- [x] Append the final measurements to the `## Measurements` section.
  → verify: baseline, cold and warm numbers present, all from the real device. Phases 2 and 3 are recorded as a combined figure — they could not be separated after the fact.
- [x] Update `AGENTS.md` and `kamma/tech.md` with the durable lessons: raw cursor queries over `DocumentFile` for any bulk SAF read, exclusions applied at scan time, and the size+mtime cache rule with its documented trade-off.
  → verify: a fresh reader of `AGENTS.md` alone would not reintroduce per-child `DocumentFile` metadata calls.
- [x] Run `/kamma:3-review` (CodeRabbit plus an independent from-scratch audit, in parallel) and apply valid findings.
  → verify: 41 unit tests pass after the fixes; see `## Review` below for each finding and its resolution.
- [x] Re-verify the reviewed build on the device: install, run a no-change sync, then create/edit/delete a throwaway file.
  → verify: done 2026-09-02 21:25. Counts unchanged at 862 local / 872 MEGA, warm scan 0.9–1.2s, zero actions on a no-change sync. A throwaway file was created (1 upload), edited to **exactly the same 21-byte length** — caught by the modification time alone, 1 upload — and deleted (1 remote deletion). A final sync settled to zero actions with no test files left behind.

---

## Review

Two passes in parallel on 2026-09-02: CodeRabbit CLI (`--uncommitted --include-untracked`, 13 files, **0 findings**) and an independent from-scratch audit given the spec and plan and told to distrust them.

**Fixed — HIGH: cursor columns were indexed positionally.** `queryChildren` assumed the provider returns columns in the order requested. A `DocumentsProvider` may ignore the projection (the platform's own default document projection has a different order), in which case the mime type would be read as the filename and every folder classified as a file — and because `SyncEngine` reads "absent locally, present remotely, baseline intact" as a remote deletion, the sync could have planned deleting the entire vault from MEGA. A cursor with fewer than five columns would also have thrown on `isNull()`. Columns are now resolved with `getColumnIndex`, a negative index counts as an absent column, and the required three (id, name, mime type) failing to resolve is an error. Only the external-storage provider was exercised on device, and it does echo the projection, which is why testing passed.

**Fixed — MEDIUM: two policy tests asserted their own fake.** The test helper injected a hand-written lambda that re-implemented the size/mtime comparison, so both cases would have passed with the production rule deleted. They now build the policy through `LocalScanPolicy.fromLastSync`. Confirmed by mutating the real comparison and watching them go red.

**Fixed — MEDIUM: no end-to-end coverage of reuse or pruning.** `InMemoryLocalStore` now takes an optional `LocalScanPolicy` and applies it exactly as the real walk does, and `SynchronizerTest` gained three cases: an unchanged cached file plans nothing; a same-length edit with a moved modification time is still re-hashed and uploaded; an excluded path is neither scanned nor deleted from the remote. All three go red if the rule is mutated.

**Fixed — LOW: root document id could diverge from the write paths.** `rootDocumentId` used `getTreeDocumentId` unconditionally while `DocumentFile.fromTreeUri` prefers `getDocumentId` for a document-in-tree URI. Not reachable today (the stored URI is always a bare tree URI) but it would have silently walked the volume root while writes went to a subfolder. Now derived the same way.

**Fixed — LOW: a null cursor looked like an empty directory.** A revoked permission, unmounted volume or dead provider would have presented the subtree as locally deleted and planned a remote deletion for every file in it. Now an error, so the sync aborts. Pre-existing behaviour (`DocumentFile.listFiles()` did the same) but this rewrite was the moment to close it.

**Fixed — three inaccurate documentation claims:** the plan asserted `Synchronizer` was unchanged when Phase 1 does change it; `AGENTS.md` understated when content still gets hashed (first sync, files not recorded on both sides, null provider metadata); the spec's "same size and same mtime" edge was presented as near-impossible when FAT/exFAT external storage has 2-second modification-time granularity.

**Fixed — nit:** the scan summary used the default locale for number formatting; now `Locale.ROOT`.

**Confirmed sound, no change:** the audit traced all six baseline-construction branches plus the cancel path and found no route by which a genuinely changed file is treated as unchanged, other than the documented same-size-same-mtime case; and no route by which pruning produces a spurious action. It independently verified the spec's four-queries-per-file claim, the three-construction-sites claim, the fingerprint-format claim and the legacy-decode claim.
