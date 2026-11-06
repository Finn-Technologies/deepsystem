# Phase 2 & 3 Integration Benchmark Summary

## JNI Batching Fix (22 -> 54 tok/s)

- `core/src/llama_engine.cpp` `LlamaEngine::generate` previously called `llama_batch_init`/`llama_batch_free` for every decoded token, causing per-token malloc/free overhead.
- Fix: allocate a single reusable `llama_batch step_batch = llama_batch_init(1, 0, 1)` before the generation loop, reuse it for each decode step, and free it once after the loop.
- Result: text generation throughput improved from ~22 tok/s to ~54 tok/s on-device.

## Compose 60fps Streaming Stabilization

- Token UI updates are coalesced to ~30 Hz (`ChatViewModel.UI_UPDATE_INTERVAL_NS = 33 ms`) instead of emitting on every token.
- While streaming, `LazyColumn` uses `scrollToItem` (snap, no animation) to avoid per-update animation jank.
- Final text is always emitted after generation completes, ensuring completeness despite batching.

## Notes FTS5

- Full-text search over notes via SQLite FTS5 virtual table; `NoteRepository.searchNotes` backs `NotesViewModel.onSearchQueryChange`.

## Glance / AppWidget Widgets

- `QuickAskWidgetReceiver` and `RecentNotesWidgetReceiver` provide home-screen quick ask and recent notes widgets with no network access.

## Sandboxed WebView MiniApps

- Generated mini apps run in a locked-down WebView with CSP, blocked network, and a scoped, permission-gated native bridge. Security validated by `MiniAppSecurityUnitTest`.