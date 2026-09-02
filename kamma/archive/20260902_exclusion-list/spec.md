# Spec: Sync exclusion list

## Goal

Let the user exclude specific files/folders from sync, browsed from either the Local or MEGA tree, via a simple add/delete list.

## User-confirmed behavior

- A third button on the main screen, "Exclusions", opens a new screen.
- That screen shows two entry points: Browse Local and Browse MEGA.
- Tapping either opens a picker that drills into that root one folder level at a time (see "Revised after testing" below); the user selects any number of files and/or folders (via checkbox) and taps "Exclude" to add them.
- Back on the Exclusions screen, the current exclusion list is shown with a "Delete" action per row to remove an entry.
- Excluding a folder excludes everything under it.
- Excluded paths are skipped by both manual sync (`MainActivity.syncNow`) and background sync (`SyncWorker`).

## Scope note (v1, single sync pair)

The app supports exactly one `SyncPair` today (`SyncPairStore.single()`). The exclusion list is therefore a single global list, not per-pair — matches current scope, no new concept introduced.

## Design

### Data model & matching

New `PathListIgnoreRule` in `sync/IgnoreRule.kt`, alongside the existing `GlobIgnoreRule`:

```kotlin
class PathListIgnoreRule(paths: List<String>) : IgnoreRule {
    private val excluded = paths.toSet()
    override fun isIgnored(path: String): Boolean =
        path in excluded || excluded.any { path.startsWith("$it/") }
}
```

Exact-path list rather than globs: the user picks concrete entries from a browsed list, so no glob syntax is needed. Matches on the same relative path string both `SafLocalStore` and `SdkMegaClient` already key entries by (confirmed in research — both sides normalize to one shared path-string identity space), so one exclusion list works for paths picked from either side.

### Persistence

New `ExclusionStore`, mirroring `SyncPairStore`'s exact shape (`SharedPreferences`, one delimited string):

```kotlin
class ExclusionStore(context: Context) {
    private val prefs = context.getSharedPreferences("exclusions", Context.MODE_PRIVATE)
    fun load(): List<String> = prefs.getString(KEY_PATHS, null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
    fun save(paths: List<String>) = prefs.edit().putString(KEY_PATHS, paths.joinToString("\n")).apply()
    private companion object { const val KEY_PATHS = "paths" }
}
```

### Wiring into sync

Both call sites currently construct `SyncEngine()` with the default `IgnoreRule.NONE`:
- `MainActivity.kt:143` — `Synchronizer(client, local, SyncEngine())`
- `SyncWorker.kt:32` — `Synchronizer(mega, local, SyncEngine())`

Both change to `SyncEngine(ignore = PathListIgnoreRule(ExclusionStore(this/applicationContext).load()))`.

### UI

Three new screens/changes, following the existing plain-Views/no-Compose/no-RecyclerView style (`activity_main.xml` pattern, `ListView` + `ArrayAdapter<String>` — stdlib, no new Gradle dependency):

1. **`MainActivity`**: add a third button `exclusions` to `activity_main.xml`, wired to launch `ExclusionsActivity`.

2. **`ExclusionsActivity`** (`activity_exclusions.xml`):
   - `ListView` of current exclusions (from `ExclusionStore.load()`), each row shows the path plus a "Delete" button that removes it from the store and refreshes.
   - Two buttons: "Browse Source" and "Browse Destination", each launching `BrowsePickerActivity` via `registerForActivityResult` with an intent extra selecting local vs. remote.
   - On picker result, appends the returned paths (deduped) to `ExclusionStore` and refreshes the list.

3. **`BrowsePickerActivity`** (`activity_browse_picker.xml`), shared for both roots:
   - Reads an intent extra (`EXTRA_ROOT = "local" | "remote"`).
   - **Revised after testing (v1 flat-list picker was too slow for local):** a full recursive local scan (`SafLocalStore.snapshot()`, or even a name-only recursive walk) takes ~20s+ on a real vault, because every SAF `listFiles()` call is its own ContentProvider round trip. MEGA's `listFolder()` stays instant regardless, because the SDK's node tree is already cached in memory after login. So the picker now drills down one directory level at a time instead of listing the whole tree upfront:
     - local: `SafLocalStore.listChildren(path)` — new method, lists only the immediate children of `path` (name + isDir, no content read, no recursion).
     - remote: fetches the full `MegaClient.listFolder(pair.remoteRoot)` once per picker session (cheap — no perf problem there) and filters in-memory to entries whose parent equals the current path, keeping one code path/UI for both roots.
   - Each row shows a checkbox (select for exclusion) and a name; tapping a folder's row navigates into it, tapping a file's row toggles its checkbox, tapping the checkbox always toggles selection without navigating. A ".." row navigates to the parent directory when not at root.
   - Selections persist across navigation (a `Set<String>` of paths on the activity), so the user can select files/folders from multiple directories before tapping "Exclude" once.
   - The selection set is pre-populated from `ExclusionStore.load()` on open, so already-excluded paths show pre-ticked wherever they're browsed to (exclusions are a single global list, not tied to which root you excluded from — see Scope note). Unticking a pre-ticked item removes that exclusion.
   - "Exclude" button returns the full edited set via `setResult(RESULT_OK, Intent().putStringArrayListExtra(...))`, finishes. `ExclusionsActivity` replaces its stored list with this set (not a union-merge — the picker already carries prior state).
   - If no `SyncPair` / empty root is configured, shows a short status message and disables the "Exclude" button (mirrors `syncNow`'s existing "Pick a local folder first." guard).
   - Activity title bar reads "Browse Local"/"Browse MEGA"; a bold header line above the list also shows the current path and selection count (e.g. "Local — notes/archive (2 selected)"), since a bare file list gave no indication of which root or folder was being browsed.

### AndroidManifest

Register `ExclusionsActivity` and `BrowsePickerActivity`.

## Guard against changing config mid-sync

Added after user testing: while a sync is running, "Pick local folder", "Pick MEGA folder", "Exclusions", and "Log out" are disabled (`Button.isEnabled = false`) on `MainActivity`, re-enabled when the sync thread finishes or is cancelled. Prevents the sync pair or exclusion list from changing while `Synchronizer` is mid-diff/mid-transfer against them. "Sync now"/"Cancel" itself stays enabled throughout.

## Out of scope

- Per-sync-pair exclusion lists (moot until multi-pair support exists).
- Glob/wildcard exclusion authoring — only picked concrete paths.
- Real tree/drill-down browsing UI — flat checklist only.
- Showing exclusions inline in the main status view.

## Files touched

- `sync/IgnoreRule.kt` — add `PathListIgnoreRule`
- `sync/ExclusionStore.kt` — new
- `MainActivity.kt` — add exclusions button + launch; wire `ignore` into `syncNow()`
- `SyncWorker.kt` — wire `ignore` into `doWork()`
- `ExclusionsActivity.kt` + `res/layout/activity_exclusions.xml` — new
- `BrowsePickerActivity.kt` + `res/layout/activity_browse_picker.xml` — new
- `res/layout/activity_main.xml` — add button
- `AndroidManifest.xml` — register two new activities
- `app/src/test/.../sync/SyncEngineTest.kt` or a new `IgnoreRuleTest.kt` — unit test for `PathListIgnoreRule` (exact match + subtree match)
