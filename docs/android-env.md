# Android Development Environment

This document describes the Android development, build, and emulation environment configured for DeepSystem on macOS (Apple Silicon arm64).

## 1. Toolchain & Package Versions

| Component | Version | Installation Path / Identifier |
| :--- | :--- | :--- |
| **Host Architecture** | macOS arm64 (Apple Silicon) | `/opt/homebrew` |
| **Java JDK** | OpenJDK 17.0.21.0.3 | `/opt/homebrew/opt/openjdk@17` |
| **CMake (Homebrew)** | 4.4.5 | `/opt/homebrew/bin/cmake` |
| **CMake (Android SDK)** | 3.31.6 | `~/Library/Android/sdk/cmake/3.31.6` |
| **Ninja** | 1.13.2 | `/opt/homebrew/bin/ninja` |
| **Android SDK Root** | API 35 (`platforms;android-35`) | `~/Library/Android/sdk` |
| **Build-Tools** | 35.0.0 (`build-tools;35.0.0`) | `~/Library/Android/sdk/build-tools/35.0.0` |
| **Android NDK** | r27c LTS (`ndk;27.2.12479018`) | `~/Library/Android/sdk/ndk/27.2.12479018` |
| **Android Emulator** | 37.2.13 (`emulator`) | `~/Library/Android/sdk/emulator` |
| **System Image** | Android 34 Google APIs arm64-v8a | `system-images;android-34;google_apis;arm64-v8a` |
| **Command-line Tools** | latest (v22.0) | `~/Library/Android/sdk/cmdline-tools/latest` |

## 2. Environment Variables

The project includes an environment script located at [`scripts/env.sh`](../scripts/env.sh). It configures all necessary paths without exposing any credentials or secrets:

```bash
# Source the environment script in your terminal
source scripts/env.sh
```

### Configured Variables:
- `JAVA_HOME`: Points to Homebrew OpenJDK 17 (`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` or `/opt/homebrew/opt/openjdk@17`).
- `ANDROID_HOME` & `ANDROID_SDK_ROOT`: Points to `~/Library/Android/sdk`.
- `ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`, & `NDK`: Points to `~/Library/Android/sdk/ndk/27.2.12479018`.
- `CMAKE_HOME`: Points to `~/Library/Android/sdk/cmake/3.31.6`.
- `ANDROID_AVD_HOME`: Points to `~/.android/avd`.
- `PATH`: Updated with Java binaries, Android cmdline-tools, platform-tools (`adb`), emulator, CMake, and Homebrew binaries.

## 3. Virtual Device (AVD): `finn_mid`

A mid-range virtual device named `finn_mid` has been created and verified for local testing and CI headless validation.

### Specifications (`~/.android/avd/finn_mid.avd/config.ini`):
- **Target ABI:** `arm64-v8a` (native Apple Silicon virtualization via Hypervisor.framework)
- **RAM:** 6 GB (`hw.ramSize=6144`)
- **CPU Cores:** 6 vCPUs (`hw.cpu.ncore=6`)
- **Data Partition Size:** 16 GB (`disk.dataPartition.size=16G`)
- **Display Resolution & Density:** 1080 x 2400 @ 440 dpi (`hw.lcd.width=1080`, `hw.lcd.height=2400`, `hw.lcd.density=440`)
- **Snapshots:** Disabled (`snapshot.present=no`, `fastboot.forceColdBoot=yes`, `fastboot.forceFastBoot=no`)
- **Heap Size:** 512 MB (`vm.heapSize=512M`)

## 4. Emulator Headless Execution

To boot the `finn_mid` emulator headlessly for background verification:

```bash
source scripts/env.sh

# Launch emulator headless
emulator -avd finn_mid -no-window -no-audio &
EMU_PID=$!

# Wait for emulator to fully finish booting
adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; do
  sleep 2
done

echo "AVD finn_mid is ready."

# Graceful shutdown
adb emu kill
```
