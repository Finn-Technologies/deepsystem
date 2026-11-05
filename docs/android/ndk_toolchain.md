# Android NDK Cross-Compilation Guide (`arm64-v8a`)

This document outlines the CMake toolchain configuration for cross-compiling the Finn DeepSystem shared core (`finn_core` & C ABI) for Android devices targeting the `arm64-v8a` architecture (API 26+).

---

## 1. Prerequisites (Provided by SDK/NDK Environment)
- **Android NDK:** Version r25c or newer (recommended: r26b / r27)
- **Host Tools:** CMake >= 3.20, Ninja >= 1.11
- **Target ABI:** `arm64-v8a` (64-bit ARM)
- **Minimum Android API Level:** `android-26` (Android 8.0 Oreo) or `android-28` (Android 9.0)

---

## 2. Directory Layout & Shared Core Artifacts
When cross-compiling `finn_deepsystem`, the build will output:
- `libfinn_core.a` (static library) or `libfinn_core.so` (shared library with `-DBUILD_SHARED_LIBS=ON`)
- `libmtmd.a` / `libmtmd.so`
- `libllama.a` / `libllama.so`
- `libggml-base.a` / `libggml-cpu.a`
- `libfinn_sqlite.a`

The stable C ABI defined in `include/finn/finn_c.h` exposes opaque C handles (`finn_engine_t`, `finn_storage_t`), suitable for direct binding from JNI via `System.loadLibrary("finn_core")`.

---

## 3. CMake Configuration Command

Assuming `$ANDROID_NDK_ROOT` points to the NDK installation:

```bash
export ANDROID_NDK_ROOT="/opt/homebrew/share/android-commandlinetools/ndk/<version>" # Or ~/Library/Android/sdk/ndk/<version>

cmake -B build-android-arm64-v8a \
    -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=ON \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_MTMD=ON \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16 \
    -DBUILD_TESTING=OFF

ninja -C build-android-arm64-v8a
```

---

## 4. Key Toolchain Parameters Explained

1. **`-DCMAKE_TOOLCHAIN_FILE`**:
   Points to the official NDK toolchain file which handles clang sysroots, target triples (`aarch64-linux-android`), and compiler flags.

2. **`-DANDROID_ABI=arm64-v8a`**:
   Ensures 64-bit ARM instructions, NEON SIMD vectorization, and FP16/dotprod optimizations.

3. **`-DANDROID_PLATFORM=android-26`**:
   Enables POSIX APIs and kernel support required for memory-mapped I/O (`mmap`), thread naming, and standard threading primitives.

4. **`-DANDROID_STL=c++_shared`**:
   Links `libc++_shared.so` so that standard C++17 runtime features (`std::mutex`, `std::thread`, `std::function`) are unified across shared library boundaries.

5. **`-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`**:
   Enables ARM dot product instructions (`sdot`, `udot`) and half-precision floating point instructions supported by Snapdragon 845+, MediaTek Dimensity, Google Tensor, and Kirin chips.

6. **`-DGGML_OPENMP=OFF`**:
   Uses standard `std::thread` and `ggml-threading` threadpool rather than linking OpenMP runtime, avoiding external runtime bundle issues on Android.

---

## 5. Gradle / Android Studio Integration

In the Android application project `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_MTMD=ON",
                    "-DGGML_OPENMP=OFF",
                    "-DBUILD_TESTING=OFF"
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("../CMakeLists.txt")
        }
    }
}
```

---

## 6. JNI Binding Coordinates
- Include header: `include/finn/finn_c.h`
- Direct C function symbols exported with `FINN_API` visibility:
  - `finn_engine_create()`
  - `finn_engine_load(engine, config)`
  - `finn_engine_generate_text(engine, prompt, ...)`
  - `finn_engine_generate_multimodal(engine, prompt, img_bytes, ...)`
  - `finn_engine_speak(engine, text, voice, audio_cb, user_data)`
  - `finn_engine_cancel(engine)`
  - `finn_storage_*()`
