# Android Mega Sync — MEGA folder sync. Run `just` to list recipes.

set shell := ["bash", "-cu"]

apk := "app/build/outputs/apk/debug/app-debug.apk"

# List available recipes
default:
    @just --list

# Build the debug APK offline (no downloads — fails if anything is missing)
build:
    ./gradlew --offline assembleDebug

# Run unit tests offline
test:
    ./gradlew --offline test

# Build + test offline, the full local gate
check: build test

# One-time online build to fetch missing deps (e.g. JUnit ~0.5 MB), then go back to `just build`
fetch:
    ./gradlew assembleDebug test

# Report the Gradle version (confirms wrapper resolves, no download)
version:
    ./gradlew --offline --version

# Remove build outputs
clean:
    ./gradlew --offline clean

# Install the debug APK on the connected device over adb
install:
    adb install -r {{apk}}

# Build then install in one step
deploy: build install

# List connected adb devices
devices:
    adb devices -l

# Build the release (minified) APK offline
release:
    ./gradlew --offline assembleRelease
