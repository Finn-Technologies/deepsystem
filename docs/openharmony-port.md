# OpenHarmony Port (Stage Model)

## Overview
The OpenHarmony port lives under `openharmony/` and follows the Stage model (API 12+). UI is ArkTS + ArkUI; the shared core is reached through a native C++ NAPI bridge (`libfinn_napi.so`).

## Structure
```
openharmony/
  AppScope/app.json5
  entry/src/main/module.json5
  entry/src/main/ets/entryability/EntryAbility.ets
  entry/src/main/ets/entryability/AssistantAbility.ets
  entry/src/main/ets/pages/{Index,LivePage,NotesPage,MiniAppPage}.ets
  entry/src/main/ets/common/finn_napi.ets
  entry/src/main/cpp/finn_napi.cpp
  entry/src/main/cpp/CMakeLists.txt
  entry/src/main/cpp/types/libfinn_napi/index.d.ts
  entry/src/main/resources/base/...
  hvigorfile.ts, entry/hvigorfile.ts, oh-package.json5, build-profile.json5
```

## Native bridge
`finn_napi.cpp` binds `finn_c.h` / `finn_c_api.h` and exposes:
`initEngine`, `chatStream`, `synthesizeSpeech`, `notesSearch`, `miniAppsList`, `runSandboxTool`, `destroyEngine`.

## Assistant & background
- `AssistantAbility` is exported with `entity.system.assistant` and runs a `ContinuousTask` (`ohos.resourceschedule.backgroundTaskManager`) for audio recording/playback while a live session is active.
- **Honest limits:** OpenHarmony reserves always-on hotword detection for system-privileged assistants. Finn does **not** provide a "Hey Finn" hotword. Invocation is through the system assistant entry (gesture / power button) or the in-app microphone. ContinuousTask keeps an active session alive but does not grant always-on listening.

## Build
Open with DevEco Studio or run:
```bash
hvigorw assembleHap
```
The native module requires the OpenHarmony SDK (NAPI headers); host builds skip it.
