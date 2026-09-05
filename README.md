# Android Mega Sync

A lightweight, ad-free Android app that keeps folders in a [MEGA](https://mega.nz) account in
**two-way sync** with local folders on your phone — any number of pairs, each with its own
exclusion list. Built to replace bloated third-party MEGA sync apps. Primary use case: keeping an
Obsidian vault in sync.

It talks to MEGA through the official [MEGA SDK](https://github.com/meganz/sdk) (BSD-2-Clause), built
from source — no protocol reverse-engineering, no untrusted binaries.

## Features

- **Bidirectional sync** of any number of MEGA folder ↔ local folder pairs, each with its own
  exclusion list.
- **Manual "Sync now"** plus a periodic background sync (~6h) via WorkManager, run per pair.
- **Last-writer-wins**: each side is compared only to its own state at the last sync; when a file
  changed on both sides, the newer modification time wins. No silent loss except in the rare case of
  editing the *same* file on two devices between syncs.
- Local folder access via the Storage Access Framework; MEGA session stored encrypted in the Android
  Keystore.
- Small and dependency-light: classic Android Views (no Compose), arm64-v8a, `minSdk` 28, `targetSdk` 34.

## Requirements

- Linux, JDK 17, Android SDK + NDK r27 (no Android Studio needed).
- [`just`](https://github.com/casey/just) for the task recipes.

## Getting started

The MEGA SDK (native `libmega.so` + Java bindings) is **not** committed — it's built from source.
Build it once by following [`mega/BUILD_SDK.md`](mega/BUILD_SDK.md) (clone the SDK, build with
vcpkg + NDK, copy the outputs into `app/`). This is a one-time ~30–40 min step.

After the SDK artifacts are in place, build and run the app:

```bash
just            # list recipes
just build      # debug APK
just test       # unit tests (sync engine + reconciliation)
just check      # build + test
just deploy     # build + adb install to a connected device
just release    # minified release APK
```

See [`mega/BUILD_SDK.md`](mega/BUILD_SDK.md) for the exact SDK build steps.

## First run

1. `just deploy`, open Android Mega Sync, log in with your MEGA email/password.
2. Add a pair: **pick MEGA folder** (vault root or a subfolder) and **pick local folder**. Add more
   pairs the same way; each gets its own exclusion list.
3. **Sync now**. After that a background job re-syncs every pair roughly every 6 hours when on a
   network.

## Architecture

```
UI (LoginActivity, MainActivity, SyncWorker)
        │
MegaClientProvider ──► MegaClient (interface)
                          ├── SdkMegaClient   (real, MegaApiAndroid)
                          └── FakeMegaClient  (in-memory, for tests/dev)
        │
Synchronizer ──► SyncEngine.diff() ──► SyncPlan      (pure function, no I/O)
        ├── LocalStore (SAF / in-memory)             reconciles via per-side
        └── LastSyncState (per-file local+remote      fingerprints + timestamp,
            fingerprints from the last sync)          pluggable IgnoreRule
```

**`MegaClient` is the only seam to MEGA.** The sync engine and `Synchronizer` depend solely on that
interface plus `LocalStore`, so the entire engine is unit-tested offline against `FakeMegaClient` (no
account needed), and upgrading the MEGA SDK touches only `SdkMegaClient`.

**Multiple folder pairs:** `SyncPair` is a first-class entity; `SyncPairStore` persists the list and
`ExclusionStore` keys exclusions per pair.

## Layout

```
app/src/main/java/org/bodhirasa/androidmegasync/
  sync/      SyncEngine, Synchronizer, SyncAction/Plan, LocalStore,
             LastSyncState, IgnoreRule, FileEntry, SyncPair…
  mega/      MegaClient, SdkMegaClient, FakeMegaClient
  local/     SafLocalStore (Storage Access Framework)
  LoginActivity, MainActivity, PairActivity, BrowsePickerActivity, ExclusionsActivity,
             MegaClientProvider, SessionStore (encrypted), SyncWorker
app/src/test/                       unit tests
app/src/megasdk/ , jniLibs/         vendored MEGA SDK bindings + libmega.so (generated, gitignored)
mega/BUILD_SDK.md                   how to build the MEGA SDK from source
```

## Licence

Released into the public domain under [The Unlicense](LICENSE). The bundled MEGA SDK is BSD-2-Clause
(see [meganz/sdk](https://github.com/meganz/sdk)).
