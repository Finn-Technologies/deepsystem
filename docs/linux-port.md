# Linux Port (Native GTK4 + libadwaita)

## Overview
Finn DeepSystem ships a native Linux desktop application under `linux/`. It links against the shared `finn_core` C++17 library and exposes the same C ABI used by Android JNI and OpenHarmony NAPI.

## Architecture
- `linux/CMakeLists.txt` — standalone CMake project. Detects GTK4, libadwaita, D-Bus, and WebKitGTK via `pkg-config`. When GTK4 is unavailable (e.g. macOS CI) it builds a headless smoke-test executable instead.
- `linux/src/main.cpp` — entry point; runs the GTK4/libadwaita application when `FINN_HAVE_GTK4` is defined, otherwise runs the headless smoke test.
- `linux/src/gtk_ui.cpp` — AdwApplication + GtkNotebook with Chat, Live, Notes (FTS5), and MiniApps (WebKitGTK sandbox) pages.
- `linux/src/linux_sandbox.cpp` — rootless container runner preferring `bwrap`, falling back to `unshare`, then direct execution.
- `linux/src/desktop_assistant.cpp` — D-Bus session-bus integration for a global hotkey and StatusNotifierItem tray icon (degraded no-op mode without D-Bus).
- `linux/finn-deepsystem.desktop` — freedesktop launcher.

## Features
- **Chat view**: streaming text generation through `finn_engine_generate_text`.
- **Live mode**: VAD/turn-taking/barge-in via `finn_live_*` with a camera preview placeholder.
- **Notes**: FTS5 search through `finn_storage_search_notes_json`.
- **MiniApps**: WebKitGTK sandbox view + bubblewrap tool runner.
- **Desktop assistant**: global hotkey (Super+Shift+F) + tray (StatusNotifierItem) when D-Bus is present.

## Honest limits
- Global hotword ("Hey Finn") is not implemented; Linux has no privileged always-on audio trigger for third-party apps. Invocation is via the configured global hotkey or tray.
- WebKitGTK and D-Bus are optional; the build degrades gracefully.

## Build
```bash
cmake -S linux -B build-linux -G Ninja
ninja -C build-linux
ctest --test-dir build-linux --output-on-failure
./build-linux/finn_linux   # headless smoke test on macOS; full GUI on GTK4 systems
```

On a GTK4 system install the dev packages first:
```bash
# Debian/Ubuntu
sudo apt install libgtk-4-dev libadwaita-1-dev libwebkitgtk-6.0-dev libdbus-1-dev
```

## Tests
`linux_tests` exercises engine lifecycle, storage, sandbox execution, and desktop assistant start/stop.
