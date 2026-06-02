# Building the MEGA SDK (arm64-v8a)

The app uses the official [`meganz/sdk`](https://github.com/meganz/sdk) (BSD-2-Clause), built
from source into a native `.so` plus SWIG-generated Java bindings. There is no Maven artifact.
This document reproduces that build. Everything stays under `sama/mega/`.

## Outputs consumed by the app

- `app/src/main/jniLibs/arm64-v8a/libmega.so` — stripped native library (~30 MB).
- `app/src/megasdk/java/nz/mega/sdk/**` — SWIG-generated + hand-written Java/Kotlin bindings.

## Prerequisites (one-time)

```bash
sudo apt install -y swig nasm autoconf-archive build-essential cmake ninja-build libtool-bin autoconf pkg-config
```

Also required (already present on this machine): JDK 17, Android NDK r27 (`~/Android/Sdk/ndk/27.0.12077973`).

> Note: if a `cmake`/Gradle build dies with `error=13, Permission denied` starting `java`/`jlink`,
> the JDK lost its execute bits (common after a backup/restore). Fix once:
> `chmod +x $JAVA_HOME/bin/* && chmod +x $JAVA_HOME/lib/jspawnhelper $JAVA_HOME/lib/jexec`.

## Build steps

```bash
cd sama/mega

# 1. Clone the SDK (shallow) and project-local vcpkg.
git clone --depth 1 https://github.com/meganz/sdk.git sdk
git clone https://github.com/microsoft/vcpkg.git vcpkg
./vcpkg/bootstrap-vcpkg.sh -disableMetrics

# 2. Configure (vcpkg builds the C++ deps for arm64 — long the first time, then cached).
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.0.12077973
export JAVA_HOME=/usr/lib/jvm/jdk-17.0.13+11
export PATH="$JAVA_HOME/bin:$PATH"
cmake --preset mega-android -B build -S sdk \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DENABLE_CHAT=ON

# 3. Compile.
cmake --build build -j"$(nproc)"
```

### Why these flags

- **`-DANDROID_PLATFORM=android-28`** — the vcpkg triplet (`arm64-android-mega`) builds the C++
  deps at API 28, and libsodium uses `getrandom()`, which only exists in Android's libc from API 28.
  Linking at a lower API fails with `undefined symbol: getrandom`. The app's `minSdk` is therefore 28.
- **`-DENABLE_CHAT=ON`** — the hand-written `MegaApiJava.java` wrapper calls chat-management methods
  (`authorizeChatNode`, `isChatNotifiable`, …). With chat off, the SWIG-generated `MegaApi.java` omits
  them and the wrapper fails to compile. Core chat needs **no** WebRTC — that's only the separate
  megachat module (`USE_WEBRTC`). Always pass this explicitly; CMake caches the value, so removing the
  flag does not reset a prior `OFF`.

## Install into the app

```bash
cd sama
STRIP=~/Android/Sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip

# These output dirs are gitignored, so create them on a fresh clone.
mkdir -p app/src/main/jniLibs/arm64-v8a app/src/megasdk/java/nz/mega/sdk

# Native library (strip debug info: ~248 MB -> ~30 MB).
cp mega/build/bindings/java/libmega.so /tmp/libmega.so
$STRIP --strip-unneeded /tmp/libmega.so
cp /tmp/libmega.so app/src/main/jniLibs/arm64-v8a/libmega.so

# Generated Java (SWIG).
cp mega/build/bindings/java/nz/mega/sdk/*.java app/src/megasdk/java/nz/mega/sdk/

# Hand-written Java + Kotlin (skip desktop-only Swing).
for f in mega/sdk/bindings/java/nz/mega/sdk/*.java; do
  [ "$(basename "$f")" = "MegaApiSwing.java" ] || cp "$f" app/src/megasdk/java/nz/mega/sdk/
done
cp mega/sdk/bindings/java/nz/mega/sdk/*.kt app/src/megasdk/java/nz/mega/sdk/
```

Then `just build` (or `just deploy` to a device).

## Other ABIs

This build targets `arm64-v8a` only (virtually all modern phones). To add another, re-run the
configure/build with a different `-DANDROID_ABI` (e.g. `x86_64` for emulators), place the resulting
`.so` under the matching `jniLibs/<abi>/`, and add the ABI to `abiFilters` in `app/build.gradle.kts`.
