# Assistant & Background

## Assistant (VoiceInteractionService)
- `assistant/FinnVoiceInteractionService`, `FinnVoiceInteractionSessionService`, `FinnVoiceInteractionSession` render a compact bottom overlay (`AssistOverlayUi`) that reuses the on-device engine.
- `AssistActivity` handles `android.intent.action.ASSIST` when Finn is not the active VoiceInteractionService.
- `AssistantCard` ("Make Finn your assistant") requests `RoleManager.ROLE_ASSISTANT` via `AssistantRoleHelper`; falls back to the system Default-apps settings on older API levels.
- **Honest limits:** Android reserves hotword detection (`HotwordDetectionService`, "Hey Google"-style always-on listening) for privileged/preinstalled assistants. Finn does **not** offer a hotword. Invocation is via the system assist gesture (long-press home / power button) once Finn holds the role, or the in-app mic.
- Emulator check: `adb shell cmd role get-role-holders android.app.role.ASSISTANT`, `adb shell am start -a android.intent.action.ASSIST`.

## Background work
- `FinnForegroundService`: types `microphone|dataSync|shortService`; handles Android 14/15 `onTimeout` by stopping gracefully and persisting state. WorkManager's `SystemForegroundService` is declared `dataSync` (6h/24h budget on Android 15 — long jobs must checkpoint).
- `agent_tasks/AgentTaskScheduler` + `AgentWorker`: immediate (expedited) and periodic WorkManager jobs.
- `NotificationHelper`: separate channels (progress, results, live), progress notifications with a Cancel action routed to `TaskManager.cancelTask`.
- `DeviceMonitor`: heavy jobs deferred when battery <20% unplugged or Power Saver (unless user opts in and level >10%); `getRecommendedThreadCount` lowers inference threads from thermal status MODERATE upward.
- `TaskManager` + `TaskDatabaseHelper`: tasks persisted in SQLite; `TasksScreen` lists running/finished tasks with cancel/clear.

## Privacy
- No telemetry/analytics SDKs, no crash reporters.
- `ModelDownloader` is the only network code path; every request is recorded in `NetworkLedger` (URL, purpose, bytes, outcome) and shown in `PrivacyLedgerScreen` (Settings > Privacy).
- `network_security_config.xml` blocks cleartext and the INTERNET permission is used solely for model downloads.
- Enforced by `PrivacyNoNetworkTest` (source scan for network APIs outside `ModelDownloader`, telemetry deps, cleartext).

## Tests
- JVM: `./gradlew :app:testDebugUnitTest --tests '*PrivacyNoNetworkTest'`
- Instrumented: `BackgroundInstrumentedTest` (SQLite persistence, cancel, ledger, ASSIST resolution).

## Gaps
- Navigation entries (Tasks screen, Privacy ledger, AssistantCard in Settings) live in `ui/` owned by another agent and must be wired there.
