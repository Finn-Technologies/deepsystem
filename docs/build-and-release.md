# Build and Release

## Prerequisites
- CMake >= 3.20, Ninja, a C++17 compiler.
- Git submodules / dependencies fetched via `scripts/fetch_llama.sh` and `scripts/fetch_models.sh`.
- For Android: Android Studio + NDK (see `docs/android/ndk_toolchain.md`).
- For OpenHarmony: DevEco Studio + OpenHarmony SDK (API 12+).
- For Linux GUI: GTK4, libadwaita, (optional) WebKitGTK + D-Bus dev packages.

## Core library
```bash
cmake -B build -G Ninja
ninja -C build
ctest --test-dir build --output-on-failure
```

## Android
```bash
cd android
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Linux
```bash
cmake -S linux -B build-linux -G Ninja
ninja -C build-linux
ctest --test-dir build-linux --output-on-failure
```

## OpenHarmony
Open `openharmony/` in DevEco Studio and run `hvigorw assembleHap`, or:
```bash
cd openharmony
hvigorw assembleHap
```

## Release checklist
1. Run all unit tests (core, linux, Android JVM).
2. Verify `PrivacyNoNetworkTest` passes (no network APIs outside ModelDownloader).
3. Build release artifacts for each target platform.
4. Confirm model files are not bundled (downloaded on first run).
5. Tag the release and publish artifacts.
