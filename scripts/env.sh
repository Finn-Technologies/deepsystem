#!/usr/bin/env bash
# DeepSystem Android Development Environment
# Sourcing this file exports the necessary environment variables for Android builds & tools.

# Java Development Kit (OpenJDK 17 via Homebrew)
if [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
else
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
fi

# Android SDK
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Android NDK (r27 LTS)
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export NDK="$ANDROID_NDK_HOME"

# CMake
export CMAKE_HOME="$ANDROID_HOME/cmake/3.31.6"

# Android Virtual Devices (AVD)
export ANDROID_AVD_HOME="$HOME/.android/avd"

# PATH configuration
export PATH="$JAVA_HOME/bin:$PATH"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
export PATH="$ANDROID_HOME/emulator:$PATH"
export PATH="$CMAKE_HOME/bin:$PATH"
export PATH="/opt/homebrew/bin:$PATH"
