# Spec — Offline Foundation

## Overview

Build the foundation of the MEGA↔local sync app **entirely offline**, against a fake MEGA backend, within a tight 52 MB download budget. The actual MEGA SDK build (a 50–120 MB clone + native dependency download) is deferred to a single, clearly-marked WiFi phase at the end.

The guiding architectural idea: **MEGA is accessed only through a thin `MegaClient` interface.** A `FakeMegaClient` lets the whole app compile, run, and be unit-tested offline. The real `SdkMegaClient` (which calls `MegaApiAndroid`) is written as source but kept out of the default build until the `.aar` exists. This same interface boundary is what makes multi-pair support a later additive change.

## What It Should Do

- Provide a Gradle project, pinned to already-cached tool versions, that builds with `./gradlew assembleDebug` and runs tests with `./gradlew test` **with no network access** (except the one-time ~0.5 MB JUnit fetch).
- Implement a **bidirectional sync engine**: given the state of a local folder and a remote folder plus the last-known-synced state, compute the set of uploads, downloads, deletes, and conflicts to reconcile both sides.
- Apply a **keep-both conflict policy**: when the same file changed on both sides since last sync, never overwrite or delete — preserve both, suffixing the losing copy (`~conflict-<device>-<timestamp>`).
- Model a **sync pair** (remote path/handle + local SAF tree URI + last-sync state) as a first-class entity, stored as a list that currently holds exactly one — so multi-pair is additive.
- Read/write the **local side via SAF / DocumentFile**.
- Present a **login screen** and a **main screen** wired to the `MegaClient` interface, fully functional against `FakeMegaClient` (fake login, fake folder tree).
- Include a **full unit-test suite** for the sync engine and conflict policy, runnable offline.
- Carry the real `SdkMegaClient` source, isolated so it does not break the offline build, ready to enable once the `.aar` is present.

## Assumptions & Uncertainties

- **Cached versions assumed available** (verified during setup): AGP 8.9.1, Gradle 8.14, Kotlin 2.2.20, appcompat 1.6.1, core 1.17.0, activity 1.9.0, documentfile 1.0.0, lifecycle-runtime 2.7.0, NDK 28/29, build-tools 36.1.0, platforms android-34/35/36.
- JUnit 4 + hamcrest (~0.5 MB) is **not** cached and will be fetched on first `./gradlew test`. This is the only expected download in the offline phases. `androidx.work`, `androidx.security-crypto`, and `tink` (~5 MB) are deferred to the WiFi phase along with the SDK.
- The sync engine is designed against an **abstract file model** (path, size, modified-time, content hash/fingerprint), not MEGA specifics, so it is testable without MEGA.
- Uncertainty (resolved only in the WiFi phase): exact `MegaApiAndroid` method signatures and the SDK `.aar` artifact name/coordinates. `SdkMegaClient` is written to the documented API and may need minor adjustment once the real headers are present.
- `minSdk` set to 26 (Android 8.0) for Keystore + modern WorkManager; `compileSdk`/`targetSdk` 34.

## Constraints

- **Hard 52 MB download budget** until WiFi. Offline phases must not trigger large downloads. Pin all plugin/library versions to cached ones; do not let Gradle resolve newer versions.
- arm64-v8a only.
- No Jetpack Compose, no Material3, no Hilt, no Room. Classic Views + AppCompat. Plain `SharedPreferences` for simple state.
- Do not modify `.env`/`.ini` files. No git commits unless explicitly requested.
- The user runs builds/installs themselves; do not execute Gradle or device commands without explicit permission.

## How We'll Know It's Done

- `./gradlew assembleDebug` produces a debug APK **offline** (after a clean Gradle cache check, no large downloads).
- `./gradlew test` runs and **all sync-engine + conflict-policy unit tests pass**.
- The app launches on a device/emulator, shows the login screen, "logs in" via `FakeMegaClient`, and a manual "Sync now" run reconciles a fake remote tree with a real local SAF folder, demonstrating uploads, downloads, deletes, and a keep-both conflict — all without the real SDK.
- `SdkMegaClient` source exists and is documented as the single integration point for the WiFi phase.
- The README documents the exact offline build/test commands and the deferred WiFi steps.

## Constraint Update (2026-06-02)

The 52 MB download budget was lifted mid-thread — full internet is now available. Phase 6 therefore **executes the MEGA SDK build for real** (clone `meganz/sdk`, build native deps via project-local vcpkg, SWIG Java bindings) and integrates `SdkMegaClient`, rather than scaffolding it and deferring. Phases 1–5 (offline foundation) are unchanged and remain the tested core. Build is contained under `sama/mega/`; the shared Android NDK is read-only and Flutter is untouched.

## What's Not Included (this thread)

- Real MEGA authentication smoke test is the final user-run on-device step (6.6); automated tests continue to use `FakeMegaClient`.
- Multi-folder-pair UI (architecture supports it; UI is later).
- Obsidian-specific ignore rules (`.obsidian/workspace*.json`, `.trash`) beyond a simple, pluggable ignore hook.
- Play Store packaging, signing for distribution.
