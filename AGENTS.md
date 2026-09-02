# Repo Notes for Agents

## SAF (local folder) performance

Reading a SAF tree through `androidx.documentfile`'s `DocumentFile` is far more expensive than it looks. `listFiles()` queries the children cursor for document IDs only; every subsequent `name`, `isDirectory`, `length()` and `lastModified()` on a child is then its **own** ContentProvider query. Reading a folder's metadata that way costs roughly four round trips per file, and that — not the recursion itself — is what made a full vault scan take minutes.

For any bulk read, query the provider directly instead: `DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)` with a projection of `COLUMN_DOCUMENT_ID`, `COLUMN_DISPLAY_NAME`, `COLUMN_MIME_TYPE`, `COLUMN_SIZE` and `COLUMN_LAST_MODIFIED` returns every child's full metadata in one cursor. Recurse on the child document ID via `buildDocumentUriUsingTree`. See `SafLocalStore.queryChildren`.

**Always resolve those columns by name (`cursor.getColumnIndex(...)`), never by the position you requested them in.** A `DocumentsProvider` may ignore the projection and return its own column set — the platform's own default document projection uses a different order — and positional indexing against such a provider reads the mime type as the file name, classifies every folder as a file, and can leave the sync planning a remote deletion for the whole vault. A short cursor also makes positional `isNull()` throw. Treat a negative index as an absent column, and treat a null cursor as an error rather than as an empty directory, so a revoked permission or unmounted volume aborts the sync instead of looking like a mass local deletion. `DocumentFile` is still fine in the per-action write paths (`write`, `delete`, `makeDir`), which run once per changed file, not once per file in the vault.

Separately, never build a browsing/picker UI around a full recursive walk — load one directory level at a time (`SafLocalStore.listChildren`, and `BrowsePickerActivity`'s drill-down design).

`SafLocalStore.snapshot()` exists only for the sync engine's diffing — don't reuse it for anything that just needs file/folder names. It is cheap only for files it can skip: a file's content is read and hashed unless its size *and* last-modified both match what the last sync recorded. That means a full read for every file on a first sync, for any file the last sync did not record on both sides (local-only files, anything recently un-excluded), and for any file whose provider reported a null size or last-modified.
