# Notes performance research and implementation

Research and source changes: 2026-10-27. Builds, migration validation, Android tests and benchmarks remain paused at the user's request. These are implemented algorithms and unexecuted regression sources, not measured speed or reliability results.

## Guidance used

There is no single industry-standard mobile notes implementation. The relevant platform practices are:

- [Android memory guidance](https://developer.android.com/topic/performance/memory/manage-app-memory): reduce allocation in frequently executed rendering/input paths and investigate allocations with profiling. This informed the reusable live point buffer/Path and revision-based finished-path cache.
- [Android coroutine practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices): make expensive operations main-safe and keep their lifetime explicit. This informed background encoding for text, tables and drawing payloads, captured immutable snapshots and session-scoped caches.
- [Room asynchronous query guidance](https://developer.android.com/training/data-storage/room/async-queries): table invalidation can rerun observable queries even when results are unchanged; suppress duplicate emissions. This informed `distinctUntilChanged` before drawing hydration and caching by document revision.
- [SQLite transactions](https://www.sqlite.org/lang_transaction.html) and [SQLite's performance FAQ](https://www.sqlite.org/faq.html): group related changes atomically instead of paying transaction overhead per row. This informed the drawing payload/manifest/pruning transaction. The existing FULL synchronization and SQLCipher settings are preserved.

Our choices of a 300 ms debounce, a two-second maximum coalescing interval, stroke granularity and cache bounds are application decisions, not timing guarantees supplied by these sources.

## Implemented changes

### Input and rendering

`LiveDrawingStroke` owns a reusable ArrayList and Path. An input sample appends a point/segment instead of copying every previous point and rebuilding the complete live Path. One point-list copy is made when the gesture commits. That copy stays independent when the live buffer is cleared or reused. Live revisions are read by the draw phase so normal pen movement does not require recomposing the toolbar.

Finished strokes and inline previews cache Paths by stable stroke identity and viewport size. A new stroke builds its own Path without rebuilding all previously finished geometry. Removing strokes prunes their cached Paths. Resizing invalidates the size-dependent cache. Input distance filtering now accounts for both viewport axes.

This does not eliminate all allocation or render work. Offset values, committed snapshots and per-command lists still allocate. Eraser intersection still scans candidates without a spatial index, and the renderer still submits the page's strokes. Those are potential later improvements to justify with device profiles, not claims of zero allocation or constant-time rendering.

### Incremental encrypted drawing persistence

Database version 3 adds `drawing_strokes`, keyed by `(blockId, id)` with cascading deletion. Migration 2→3 creates the table without rewriting existing drawing JSON; Room can chain 1→2→3. Existing arrays/version-1 documents remain readable and convert on their next successful edit/save.

Each stroke has a stable ID. A version-2 block manifest stores paper/aspect, ordered stroke IDs and a deterministic content revision. Geometry/style payloads are separate rows inside the same SQLCipher database. The encoder reuses immutable, unchanged stroke payloads; changed payloads get new digests. The DAO reads ID/digest stamps and upserts only added/changed payloads. Removed rows are pruned in bounded batches. Payload changes, pruning, manifest and note timestamp commit in one transaction. A stale callback cannot recreate rows for a deleted drawing block.

Manifests still cost O(number of strokes) to encode/write, stamp comparisons are O(number of strokes), and changed strokes are re-encoded in full. This is incremental by stroke, not by individual sample. Digests support change detection; encryption/authentication remains the responsibility of SQLCipher and the vault's existing storage policy.

Stroke payloads and their current manifest are read together in a database transaction, avoiding missing-stroke races between an earlier observable block emission and a newer save. A content revision changes even when recoloring retains the same object IDs, preventing stale hydrated-cache hits. Hydration and legacy reconstruction run off the main thread, and unchanged drawing documents reuse cached results when another note block changes. Missing, duplicate or unsupported data fails rather than being replaced with an empty drawing.

The active-note encoder cache is discarded on note preparation and lock; captured pending-save closures retain only what they need until the existing save drain finishes. No plaintext snapshot or preview file is introduced. No cryptographic costs, backup policy or durability settings are weakened.

### Text, tables and search

Rich text already serializes only after debounce on a worker. Tables now retain immutable table drafts directly in the editor session and likewise defer JSON/search-text encoding until the save executes. Cell input no longer serializes the whole table merely to retain its draft.

Text-changing saves rebuild the combined note search text. Drawing-only and formatting-only saves update content/timestamps without running that aggregation. Room's existing FTS synchronization triggers may still perform index maintenance when an item row is touched; this change does not claim zero FTS writes. Whole-note text aggregation still exists for actual text edits and should be profiled on large notes before replacing the search schema.

### Save scheduling

Pending snapshots are coalesced per key with a 300 ms quiet interval. Continuous edits reach a save attempt after at most two seconds of coalescing, instead of postponing every save indefinitely. Database/gate contention, encoding time and storage errors can extend actual completion time; this is not a two-second persistence guarantee.

Only debounce timers are cancelled by later edits. A save already waiting for or holding the gate remains pending until it commits. Completion removes only the exact snapshot it saved. A newer edit survives, failed saves remain available for retry, and the existing normal-lock drain captures pending work before closing the encrypted database. Sudden process termination or permanent storage failure can still lose unsaved changes. No background plaintext journal or Android worker holding an unlocked vault key has been added.

## Verification status and remaining measurements

Kotlin formatter/parser checks and whitespace review were performed only. `NoteOptimizationTest` adds unexecuted Android regression source covering reusable-buffer snapshot independence; stable payload reuse; same-ID style changes invalidating the manifest revision; encrypted stroke save/reopen; erase and undo round trips; search preservation; cascading deletion; and late-save rejection after deletion.

When builds are authorized, compile with Room/KSP, generate and review the version-3 exported schema, validate encrypted 1→2→3 and 2→3 migrations using real versioned fixtures, then execute the notes/UI/security tests. Migration correctness has not yet been established by execution. Do not distribute the new schema against important data before that verification.

Profile a physical 4 GB midrange phone with long continuous typing, 20×12 tables, mixed notes and 1k/10k/50k-point drawings. Record UI frame time, input-to-display latency separately, GC/allocation rate, CPU/native/graphics memory, encoding time, transaction duration and changed-row counts. Exercise lock during a pending save, save failures, rapid reopen, undo/redo, repeated style changes and deletion. Use synthetic content in traces. The existing PERFORMANCE.md targets remain targets; no benchmark result is fabricated by this source update.
