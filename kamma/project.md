# Project Guide

## What It Is and Why

A lightweight, ad-free, privacy-respecting Android app that keeps a folder in a MEGA cloud account in **bidirectional** sync with a local folder on the phone. It exists to replace an existing third-party MEGA sync app that is bloated with ads and of questionable data-handling. The primary driving use case is keeping an Obsidian vault (stored on MEGA) in sync with its local copy on Android, but the app is a general MEGA-folder sync tool.

The app talks to MEGA through MEGA's official BSD-licensed SDK (built from source), so there is no reverse-engineering of MEGA's end-to-end-encrypted protocol and no dependency on an untrusted binary.

## Who It's For

The developer/user personally — a single-user, sideloaded, self-built app. Not aimed at the Play Store or a general audience for v1. The user values: small footprint, no ads, no data harvesting, reliability of their notes.

## One-Off or Ongoing

**Ongoing.** The app is a long-lived personal tool that will be installed, updated, and extended over time. The codebase must be easy to install, test, extend, and update.

## What It Will Produce

- An installable Android APK (arm64-v8a, sideloaded via adb).
- A locally built MEGA SDK `.aar` consumed by the app.
- Any number of configurable folder pairs (each one MEGA folder ↔ one local folder, with its own exclusions) kept in bidirectional sync, triggered manually per pair, manually for all pairs, and on a periodic background schedule.
- A clear, documented build process runnable from the Linux CLI (`./gradlew`), with no Android Studio required.

## Scope and Extensibility

- **Multiple folder pairs shipped 2026-09-02.** Each pair owns its two folders and its own exclusion list; pair ids key the exclusion and last-sync stores. Only one sync runs at a time in the process, and pair ids are never reused.
- Bidirectional sync requires a conflict-resolution policy. Default policy: keep both versions, suffixing the losing copy (e.g. `~conflict-<device>-<timestamp>`), never silently overwriting or deleting user data.
- Obsidian-specific niceties (ignoring `.obsidian/workspace*.json` churn, handling `.trash`) are desirable but secondary to a correct general sync.

## How We'll Know It Worked

- The app installs on the phone and logs into MEGA with email/password, persisting the session securely (Android Keystore).
- A user can add folder pairs, pick a MEGA folder and a local folder for each, and run a sync for one pair or for all of them.
- After a sync, both sides contain the same set of files with matching contents; changes made on either side since the last sync are propagated to the other.
- Concurrent edits on both sides do not lose data — the conflict policy preserves both copies.
- Periodic background sync runs on schedule without a foreground service.
- The whole thing builds and installs from the Linux CLI, and the build/test steps are documented well enough for the user to repeat them unaided.
