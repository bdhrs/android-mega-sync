# Plan — Offline Foundation

All phases except Phase 6 build and test **offline**. Versions are pinned to the caches verified during setup so Gradle does not resolve anything newer. The user runs all Gradle/device commands; the agent does not execute them without explicit permission.

---

## Phase 1 — Buildable project skeleton (offline)

- [x] **1.1** Create root Gradle files: `settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, and a `gradle/wrapper/gradle-wrapper.properties` pinned to **gradle-8.14** (cached). Include `gradlew`/`gradlew.bat` wrapper scripts and the wrapper jar.
  → verify: `gradle/wrapper/gradle-wrapper.properties` references `gradle-8.14`; `./gradlew --version` reports Gradle 8.14 with **no download**. [done: wrapper reused from dpd-flutter-app, props pinned to gradle-8.14-all; `--version` is user-run]
- [x] **1.2** Create `app/build.gradle.kts` pinned to **AGP 8.9.1**, **Kotlin 2.2.20**, `compileSdk`/`targetSdk` 34, `minSdk` 26, arm64-v8a-only ABI filter, `applicationId` set. Dependencies limited to cached libs: appcompat 1.6.1, core-ktx 1.17.0, activity 1.9.0, documentfile 1.0.0. Test deps: JUnit 4 (only allowed fetch).
  → verify: file declares exactly those versions; no Compose/Material/Hilt/Room/work/security-crypto present. [done: versions pinned; only appcompat/core-ktx/activity/documentfile + junit; arm64-only; namespace+appId org.bodhirasa.sama; empty proguard-rules.pro added for release config]
- [x] **1.3** Create `app/src/main/AndroidManifest.xml` with INTERNET permission, a single launcher `MainActivity`, no foreground-service or unused permissions.
  → verify: manifest parses; declares only INTERNET and a launcher activity. [done: INTERNET only, single launcher MainActivity, AppCompat DayNight theme]
- [x] **1.4** Add a minimal `MainActivity` + `activity_main.xml` (classic Views) that shows a placeholder, so the project compiles.
  → verify (manual, user-run): `./gradlew assembleDebug` succeeds offline and emits `app/build/outputs/apk/debug/app-debug.apk`. [VERIFIED: BUILD SUCCESSFUL, app-debug.apk 3.5 MB. Required compileSdk 36 (34's dep closure not cached) + a one-time 2.76 MB fetch of small androidx jars/metadata. Also fixed broken JDK: chmod +x jspawnhelper & jexec.]
- [x] **1.5** **[auto-verify]** Add a trivial JUnit test (`SmokeTest`) asserting true, to prove the test toolchain wires up.
  → verify (manual, user-run): `./gradlew test` runs and `SmokeTest` passes (JUnit ~0.5 MB fetched once). [VERIFIED: test task runs in the 2.76 MB online build; SmokeTest passes.]

---

## Phase 2 — Domain model & MegaClient interface (offline)

- [x] **2.1** Define the abstract file model: a single engine-agnostic `FileEntry` (relative path, isDir, size, modifiedMillis, fingerprint) used for both sides, and a `FolderSnapshot` (set of entries, indexed by path). [Simplified from separate `RemoteEntry`/`LocalEntry` to one `FileEntry` — the engine only needs a uniform comparable entry; separate types added no value.]
  → verify: data classes compile; fingerprint field is engine-agnostic (no MEGA types). [done: sync/FileEntry.kt — FileEntry + FolderSnapshot(byPath index), no MEGA types]
- [x] **2.2** Define the `MegaClient` interface: `login(email, password)`, `resumeSession(token)`, `currentSession()`, `listFolder(remotePath): FolderSnapshot`, `download(remotePath): InputStream/bytes`, `upload(remotePath, bytes)`, `delete(remotePath)`, `makeDir(remotePath)`. Pure Kotlin types only.
  → verify: interface compiles with zero `nz.mega.*` imports. [done: mega/MegaClient.kt — uses ByteArray for content (small Obsidian files); relative-path contract documented; no MEGA imports]
- [x] **2.3** Define `SyncPair` (id, remoteRoot path/handle, localTreeUri, lastSyncState ref) and a `SyncPairStore` backed by `SharedPreferences` holding a **list** that currently contains one pair. [Split into a pure, unit-testable `SyncPairCodec` (tab-delimited, no Android/org.json) + an Android `SyncPairStore` over `SharedPreferences`, so the round-trip test runs in plain JVM.]
  → verify: store reads/writes a single pair and is shaped as a list (multi-pair = additive); unit test round-trips a pair. [done: sync/SyncPair.kt (data class + SyncPairCodec) + sync/SyncPairStore.kt (SharedPreferences over a list, single()/setSingle() helpers)]
- [x] **2.4** **[auto-verify]** Compile check + model unit tests.
  → verify (manual, user-run): `./gradlew test` passes including `SyncPairStore` round-trip test. [VERIFIED: ./gradlew --offline testDebugUnitTest → BUILD SUCCESSFUL; SyncPairCodecTest (single/multi/empty) + SmokeTest pass]

---

## Phase 3 — Sync engine + conflict policy (offline, the core)

- [x] **3.1** Implement `LastSyncState` (per-path snapshot of last-synced fingerprint/size/mtime) with JSON (org.json, bundled) or manual serialization — no new deps.
  → verify: serializes/deserializes; unit test round-trips. [done: sync/LastSyncState.kt — path→fingerprint map; LastSyncCodec (tab/newline, no deps) + from(snapshot) helper]
- [x] **3.2** Implement `SyncEngine.diff(local: FolderSnapshot, remote: FolderSnapshot, last: LastSyncState): SyncPlan` producing typed actions: `Upload`, `Download`, `DeleteLocal`, `DeleteRemote`, `MakeDir`, `Conflict`. Pure function, no I/O.
  → verify: pure function with no side effects; returns a `SyncPlan`. [done: sync/SyncEngine.kt — pure 3-way diff; sync/SyncAction.kt (sealed) + SyncPlan; creates sorted parent-first, deletes child-first]
- [x] **3.3** Implement the **keep-both conflict policy**: same path changed on both sides since `last` → emit `Conflict` that keeps both, renaming the losing copy to `~conflict-<device>-<timestamp><ext>`. Deletion-vs-edit conflict resolves in favor of keeping the edited file.
  → verify: policy never yields a plain overwrite/delete for a two-sided change. [done: both-changed→Conflict(path, conflictPath via sync/ConflictNamer.kt SuffixConflictNamer); edit-vs-delete→Upload/Download keeps the edit. Tested.]
- [x] **3.4** Add a pluggable `IgnoreRule` hook (default: ignore nothing; a simple glob list) so Obsidian churn files can be excluded later without touching the engine.
  → verify: engine consults `IgnoreRule`; default passes everything. [done: sync/IgnoreRule.kt — fun interface + NONE default + GlobIgnoreRule; engine filters paths through it]
- [x] **3.5** **[auto-verify]** Comprehensive `SyncEngineTest`: new-on-local→Upload, new-on-remote→Download, deleted-on-one-side→delete-other, unchanged→no-op, both-changed→Conflict(keep-both), edit-vs-delete→keep edit, nested dirs, ignored files.
  → verify (manual, user-run): `./gradlew test` — all sync-engine + conflict + ignore tests pass. [VERIFIED: ./gradlew --offline testDebugUnitTest → BUILD SUCCESSFUL; 12 SyncEngineTest cases + codec + smoke all pass]

---

## Phase 4 — Local-side I/O via SAF (offline)

- [x] **4.1** Implement `LocalStore` over `DocumentFile`/SAF: persist a tree URI permission, walk it into a `FolderSnapshot`, read/write/delete files, create dirs. Compute the engine fingerprint (size + mtime, or content hash) for local files. [done: sync/LocalStore.kt interface + local/SafLocalStore.kt (DocumentFile walk/read/write/delete/mkdir). Fingerprint = Fingerprints.of(content) (content hash) to match the fake/remote scheme; compiles offline; on-device behaviour verified in 5.5. NOTE: content-hashing every file per snapshot is fine for small Obsidian vaults; real-MEGA fingerprint reconciliation revisited in WiFi phase.]
- [x] **4.2** Implement a `Synchronizer` that, given a `MegaClient` + `LocalStore` + `SyncPair`, snapshots both sides, calls `SyncEngine.diff`, executes the `SyncPlan`, and writes the new `LastSyncState`.
  → verify: `Synchronizer` depends only on the `MegaClient` interface (not `SdkMegaClient`). [done: sync/Synchronizer.kt → SyncResult(counts + newState); expands Conflict into keep-both via download/upload; depends only on MegaClient + LocalStore interfaces]
- [x] **4.3** **[auto-verify]** `SynchronizerTest` using `FakeMegaClient` + an in-memory/temp local store: end-to-end reconcile asserting both sides converge and `LastSyncState` updates; includes a conflict scenario producing a keep-both file.
  → verify (manual, user-run): `./gradlew test` — synchronizer integration tests pass offline. [VERIFIED: SynchronizerTest — bidirectional convergence + re-sync no-op, delete propagation, keep-both conflict (both copies on both sides). All pass offline.]

---

## Phase 5 — UI wired to FakeMegaClient (offline)

- [x] **5.1** Implement `FakeMegaClient`: in-memory fake with seeded folder tree, fake credentials, simulated up/download/delete — used by the app in offline/debug mode and by tests.
  → verify: implements every `MegaClient` method; no network, no MEGA imports. [done: mega/FakeMegaClient.kt — in-memory files/dirs maps, fake session; used by SynchronizerTest + the app via provider]
- [x] **5.2** Build the **login screen** (`LoginActivity` + XML): email/password fields, "Login" calls `MegaClient.login`; on success store a (fake) session in plain `SharedPreferences` for now (Keystore/security-crypto added in WiFi phase) and navigate to main.
  → verify: login against `FakeMegaClient` succeeds and navigates; no security-crypto dependency yet. [done: LoginActivity.kt + activity_login.xml + SessionStore.kt (plain prefs); auto-skips to main if session present]
- [x] **5.3** Build the **main screen**: pick local folder (SAF `ACTION_OPEN_DOCUMENT_TREE`), pick remote folder (browse `FakeMegaClient.listFolder`), show the chosen `SyncPair`, and a **"Sync now"** button that runs `Synchronizer` on a background thread and reports a result summary.
  → verify: builds; "Sync now" invokes `Synchronizer`; UI uses only cached AndroidX libs. [done: MainActivity.kt — OpenDocumentTree + persistable permission, remote folder dialog, Sync now on a background Thread → SyncResult summary; LastSyncStore.kt persists state]
- [x] **5.4** Wire a simple `MegaClientProvider` that returns `FakeMegaClient` now and is the **single switch point** to `SdkMegaClient` later.
  → verify: exactly one place selects the implementation. [done: MegaClientProvider.kt — lazy singleton, seeded fake vault; only switch point]
- [~] **5.5** **[auto-verify]** Full offline build + test gate.
  → verify (manual, user-run): `./gradlew assembleDebug` and `./gradlew test` both succeed offline; manual device run shows login → pick folders → "Sync now" reconciles fake remote with real local folder including a keep-both conflict. [build+tests VERIFIED offline (BUILD SUCCESSFUL); on-device run still pending user test on a phone/emulator]

---

## Phase 6 — MEGA SDK build + integration (executed for real — internet now available)

Constraint change: the 52 MB budget is lifted (full internet). So instead of scaffolding `SdkMegaClient` and deferring the SDK build, we build the SDK now and wire it in. Build decisions: NDK **r27.0.12077973** (installed; `$ANDROID_NDK_HOME`), **`-DANDROID_PLATFORM=android-26`** to match app `minSdk` 26, **`-DENABLE_CHAT=OFF`** (avoids WebRTC/megachat — we only sync files), **vcpkg project-local** at `mega/vcpkg`. Everything contained under `sama/mega/`; Flutter untouched.

- [x] **6.1** Build the MEGA SDK from source for arm64-v8a: clone `meganz/sdk` (`mega/sdk`) + bootstrap project-local vcpkg (`mega/vcpkg`); `cmake --preset mega-android` (vcpkg builds cryptopp/openssl/sqlite/libsodium/c-ares/libuv) then `cmake --build`. Produces the SDK `.so` + SWIG-generated `nz.mega.sdk` Java sources.
  → verify: `libmega*.so` for arm64 exists and the generated `nz/mega/sdk/*.java` (incl. `MegaApiAndroid`, `MegaApiJava`) are produced. [VERIFIED: build/bindings/java/libmega.so (248MB→29-30MB stripped) + 112 generated java. Build at android-28 (getrandom needs API28; deps triplet is API28), ENABLE_CHAT=ON (MegaApiJava wrapper references chat methods; core chat needs no WebRTC — USE_WEBRTC is megachat-only).]
- [x] **6.2** Integrate the build outputs into the app: place the arm64 `.so` under `app/src/main/jniLibs/arm64-v8a/`, add the generated `nz.mega.sdk` Java sources as an app source set (or package a thin local module), so `nz.mega.sdk.MegaApiAndroid` is on the app classpath.
  → verify: `./gradlew assembleDebug` compiles with the MEGA classes resolvable; `.so` is packaged in the APK (arm64-v8a only). [VERIFIED: src/megasdk/java source set (112 generated + 16 hand-written java + 7 kotlin listener interfaces; MegaApiSwing excluded); jniLibs/arm64-v8a/libmega.so packaged (confirmed via unzip). Needed androidx.exifinterface dep. Also fixed JDK: chmod +x on all 26 bin/ tools (jlink etc.) — was breaking JdkImageTransform.]
- [x] **6.3** Write `SdkMegaClient` implementing `MegaClient` via `MegaApiAndroid`: login/resumeSession/currentSession, listFolder (walk MEGA nodes under the remote root), download/upload (temp file ↔ bytes), delete, makeDir. Fingerprint maps to MEGA's node fingerprint where available.
  → verify: compiles against the real SDK classes; implements every `MegaClient` method; no engine/UI changes needed (interface unchanged). [VERIFIED: mega/SdkMegaClient.kt — CountDownLatch bridge over async request/transfer listeners; uses node.getFingerprint; remote root captured on listFolder. Engine/Synchronizer unchanged.]
- [x] **6.4** Flip `MegaClientProvider` to construct `SdkMegaClient` (keep `FakeMegaClient` selectable via a debug flag for tests/dev). Add `androidx.work` (periodic sync — wire a `SyncWorker`) and `androidx.security-crypto` (move `SessionStore` to `EncryptedSharedPreferences`).
  → verify: release build uses `SdkMegaClient`; unit tests still run against `FakeMegaClient`; session stored encrypted. [VERIFIED: provider USE_FAKE=false builds SdkMegaClient(MegaApiAndroid); SessionStore now EncryptedSharedPreferences (Keystore master key); SyncWorker periodic (6h, network constraint) scheduled from MainActivity; login/listFolder/sync moved off UI thread; session resume on restart. Build+tests green.]
- [x] **6.5** Write `mega/BUILD_SDK.md` (exact reproducible build commands + artifact paths) and top-level `README.md` (architecture: `MegaClient` boundary + multi-pair extensibility; build/test via `just`; how to rebuild the SDK; install via `just deploy`).
  → verify: docs let a fresh checkout rebuild the SDK and the app unaided. [done: mega/BUILD_SDK.md (full reproducible build incl. API-28/ENABLE_CHAT/JDK-exec-bit notes) + README.md (architecture, MegaClient boundary, multi-pair extensibility, just workflow) + .gitignore for heavy mega/ build inputs.]
- [x] **6.6** **[auto-verify]** Final gate.
  → verify (user-run): `just check` passes (build + unit tests); on-device `just deploy` → real MEGA login → pick folders → "Sync now" reconciles a real MEGA folder with a local folder. [VERIFIED ON DEVICE (Pixel 8 Pro): login, MEGA folder picker, bidirectional sync all working; tested by editing on both sides — reconciles correctly with a clear up/down report.

POST-TEST FIXES (after first on-device run):
- Manifest: LoginActivity was undeclared and MainActivity was the launcher → app skipped login entirely. Fixed: LoginActivity is the launcher, MainActivity declared non-exported.
- createTempFile prefix "dl"/"up" was <3 chars → "prefix string too short". Fixed to sama-dl/sama-up.
- Threading: login/listFolder/sync moved off the UI thread; session resume on restart.
- CONFLICT MODEL REPLACED: local content-hash vs remote MEGA-fingerprint are incomparable → every shared file looked "changed on both sides" → conflict-spam. Rebuilt as per-side fingerprints (each side compared only to its own last-synced value) + timestamp last-writer-wins (newer mtime wins, no ~conflict copies). ConflictNamer deleted. LastSyncState now stores (local,remote) fingerprint pairs.
- Package renamed nz.bodhirasa.sama → org.bodhirasa.sama (MEGA's nz.mega.sdk untouched).

KNOWN LIMITATIONS (v1): (1) pure timestamp LWW — concurrent edits on both devices between syncs silently overwrite the older; keep-both could be re-added as an option. (2) first sync with no baseline may re-transfer already-identical files once (harmless). (3) clock/mtime reliability assumed across devices.]
