# Container Sandbox & Agent Tool Interface

## Overview
DeepSystem exposes a structured agent tool interface that lets the on-device model
perform "computer-like" work (shell commands, Python, file I/O, web fetch) inside a
scoped Linux sandbox. The interface is implemented in the shared C++ core
(`finn::AgentToolDispatcher`) and surfaced to Android through a Kotlin manager.

## Architecture
- **Core (C++)**: `core/include/finn/agent_tools.h`, `core/src/agent_tools.cpp`
  - `SandboxConfig`, `SandboxPermissions`, `ToolCall`, `ToolResult`, `AgentToolDispatcher`
  - JSON tool-call parser for `bash_exec`, `python_run`, `file_read`, `file_write`, `web_fetch`
  - Subprocess backend with clean environment, scoped workspace, timeout, output truncation, audit log
- **C ABI**: `include/finn/finn_c_api.h`, `core/src/finn_c_api.cpp`
- **Android**: `LinuxSandboxManager`, `SandboxPermissionGate`, `SandboxTerminalSheet`

## Permission model
- `network_allowed` defaults to **false**. `web_fetch` is rejected unless explicitly granted;
  every granted request is recorded in `NetworkLedger`.
- `file_write_allowed` gates `file_write`.
- `process_spawn_allowed` gates `bash_exec`/`python_run`.
- `allowed_commands` optionally restricts the first token of shell commands.
- `timeout_ms` and `max_output_bytes` bound execution and output.
- Paths are resolved and confined to the workspace directory; traversal outside is denied.

## proot vs bubblewrap vs VM
- **proot** (used on Android when a rootfs is installed): rootless, no kernel privileges,
  good enough for running a Linux rootfs/userspace. NOT a VM and NOT kernel-level isolation;
  a determined process can escape via kernel interfaces. Honest: rootless proot is convenience,
  not a security boundary.
- **bubblewrap (bwrap)**: stronger namespace-based isolation on Linux hosts, still not a VM.
- **VM (Firecracker/QEMU)**: true isolation boundary; heavier, not feasible on mobile.
DeepSystem prefers proot on Android for "computer-like" tasks while being explicit that it is
not equivalent to a VM sandbox.

## Agent-to-container tool protocol
Tool calls are JSON objects:
```json
{"name":"bash_exec","arguments":{"command":"echo hi"}}
```
Multiple calls may be passed as an array or under `tool_calls`. Results are JSON:
```json
{"tool_call_id":"...","name":"bash_exec","success":true,"exit_code":0,"stdout":"hi\n",...}
```
Network access always requires explicit permission and is audited.