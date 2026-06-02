# Tech Notes

## Tools & Platforms

**Language & UI**
- **Kotlin** — the MEGA SDK ships Java/Kotlin bindings, so Kotlin is the natural, zero-bridge choice.
- **Classic Android Views (XML layouts) + AppCompat** — no Jetpack Compose, no Material3. A sync app needs ~3 screens; Compose would add runtime weight for nothing.
- **Target:** `compileSdk`/`targetSdk` 34, `minSdk` 26 (Android 8.0) — covers Keystore-backed encryption and modern WorkManager cleanly.

**MEGA integration**
- **Official MEGA C++ SDK (`meganz/sdk`, BSD-2-Clause), built from source** to a `.aar` with the installed NDK + CMake. Same code MEGA's own app uses; no protocol reverse-engineering, no untrusted third-party mirror.
- Native lib built for **arm64-v8a only** (ABI split) to keep APK size down.

**Build & tooling (all already installed locally)**
- JDK 17 (Temurin), Android SDK at `~/Android/Sdk`, NDK (27/28/29 available), CMake 3.22.1, build-tools 34/35/36, platforms android-31/34/35/36, platform-tools/adb v36, Gradle wrapper cache (8.9, 8.14).
- Build via **Gradle wrapper from the CLI** (`./gradlew assembleRelease`). **No Android Studio required.**
- **R8/ProGuard** on release builds.
- Install to phone via `adb install`.

## Dependencies (Maven)

```
androidx.appcompat:appcompat              # cached locally
androidx.documentfile:documentfile        # cached locally — SAF helper for local folder
androidx.work:work-runtime-ktx            # NOT cached (~1-2 MB) — periodic background sync
androidx.security:security-crypto         # NOT cached (~1-2 MB) — Keystore-backed session storage
nz.mega.sdk (local .aar)                  # built from source, not on Maven Central
```

No Material, no Compose, no Hilt, no Room. If local state is needed, plain `SharedPreferences` or a single SQLite table — no ORM.

## Who This Is For

Single developer/user. Sideloaded, self-built, personal. No Play Store, no multi-user concerns, no analytics, no ads, no telemetry.

## Constraints

- **Download budget is limited** — plan downloads explicitly. New downloads expected: MEGA SDK source (~30–80 MB one-time, shallow clone) + its build dependencies, plus AndroidX `work` and `security-crypto` (~3 MB). Toolchain itself needs ~0 MB (already installed).
- **APK size** dominated by MEGA native `.so` (~8–12 MB arm64). Total APK ~10–15 MB. Unavoidable without reimplementing MEGA's protocol (rejected — silent corruption risk).
- **Modern Android background limits (8+):** no true continuous sync without a foreground service. Periodic sync uses **WorkManager** with constraints; real-time is out of scope for v1.
- **MEGA "app key" gray area:** the third-party app-key portal is abandoned; a self-chosen identifier string is used. Acceptable for a private sideloaded app.
- **Bidirectional sync correctness** is the core engineering risk. Conflict policy must never silently lose data (keep-both with suffix).
- Disk: ~113 GB free — ample.

## Resources

- `meganz/sdk` — official SDK + Android build scripts (BSD-2-Clause).
- `meganz/android` — official Kotlin app, reference for wiring `MegaApiAndroid`.
- `treagent/mega-android-provider` — Kotlin reference for session storage in `EncryptedSharedPreferences` + Keystore (read for patterns only; no clear license).
- Android SAF (Storage Access Framework) — persistent URI permission for the local folder.

## What the Output Looks Like

- A `.aar` (locally built MEGA SDK) committed/cached in the repo's build inputs.
- An arm64-v8a release APK installable via `adb install`.
- A documented, repeatable CLI build (`./gradlew assembleRelease`) plus test commands, all runnable on Linux without an IDE.

## Architecture Notes (for extensibility)

- Model a **sync pair** as a first-class entity (MEGA node handle/path + local SAF tree URI + last-sync state) even though v1 exposes only one. Storing it as a list of one keeps the multi-pair upgrade additive.
- Keep the **sync engine** (diff + reconcile) independent of UI and of the trigger mechanism, so manual button, WorkManager, and future triggers all call the same engine.
- Isolate MEGA SDK access behind a thin interface so the engine is testable without a live MEGA account where practical.
