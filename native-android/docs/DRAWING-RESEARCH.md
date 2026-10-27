# Drawing canvas review and upgrade proposal

Research date: 2026-10-27. Reviewed local source at `bf127c1`. This is a proposal, not an implemented or benchmarked upgrade. The user's pause on builds, tests and CI remains active. No application source or dependencies are changed by this document. The working assumption is support for both handwriting and sketches/diagrams. The user asked to continue from the saved state after a possible reset; that does not change the research scope or the build pause.

## Recommendation

Replace the embedded-only sketch pad with a full-screen, native drawing editor backed by a versioned vector document. Keep a proportional, non-interactive preview and an explicit Edit drawing button inside notes. Use Jetpack Ink for freehand input/rendering, an application-owned document/history/geometry layer for editing, and SQLCipher for incremental persistence. Preserve the Paper appearance and existing vault session boundary.

Start with bounded pages that can zoom and pan, with an explicit page switcher. This supports handwriting and ordinary diagrams while keeping memory, recovery and file size manageable. Keep coordinates and document interfaces extensible enough for a bounded board mode later. An infinite canvas is possible but would add visible-region loading, larger-coordinate precision, navigation and recovery work before the basic drawing experience is reliable.

## Review of the current implementation

The current native implementation already replaced the old app's drawing UI. Earlier source fixes added tap dots, segment hit testing, undo for erase/clear, cancellation restoration and session drafts. These have not been executed on a device.

| Finding | Evidence in current source | Consequence / next action |
|---|---|---|
| Fixed 240 dp drawing area, four colors and a single pen style | `NoteDrawing.kt`, `DrawingBlock` toolbar and Canvas modifiers | Too small for handwriting and detailed sketches. Add a full editor, tool palette, paper templates and accessible controls. |
| x/y are independently normalized to current width/height | `normalized`, `path`, `hit` | A width/aspect change can stretch the same drawing. Store page dimensions and document-space coordinates; apply one uniform viewport transform. |
| Width is a separate screen-density-scaled value | `drawPath` / `drawCircle` | Stroke thickness and geometry need a documented common world-space unit before zoom or selection scaling is introduced. |
| Only positions/color/width are stored | `NoteStroke`, `encode` | No native record of event time, pressure, tilt or input tool. An advanced brush needs richer actual input data and a versioned brush preset. |
| One pointer is consumed as a drawing gesture | `awaitEachGesture` loop | No tool-aware finger/stylus routing or two-finger viewport navigation. Palm rejection and OS cancellation semantics require explicit native input integration and tests. |
| Immutable point list is copied and the current Path rebuilt while drawing | `current = current + point`, `remember(current, dimensions)` | Allocation/work increases along a stroke. This is a source-level performance concern, not a measured latency result. Use an incremental live-ink path/input buffer. |
| All completed strokes are submitted when the Canvas redraws | `strokes.forEachIndexed` | Cached Paths help, but there is no viewport culling or page partitioning. Keep active ink separate and index completed objects by bounds. |
| Eraser scans strokes/segments for each sampled location | `filterNot`, `NoteDrawing.hit` | Work scales with the entire drawing. Use a bounds index, then precise intersection against the swept eraser segment. |
| Whole-stroke deletion only, no redo or lasso | `hit`, history stack, toolbar | Add command-based undo/redo and selection. Partial erasing requires a separate geometry/persistence design. |
| 5,000 points per gesture and 50,000 total | `MAX_STROKE_POINTS`, `MAX_POINTS`, gesture completion | Input beyond a limit can stop extending or disappear without an explanation. Add visible limits and split long gestures into internal chunks sharing one undo command. Do not simply remove bounds. |
| Entire drawing serialized on the UI path after an edit | `save` calls `encode` before `queueBlock` | Database debounce does not eliminate serialization work per gesture. Capture a revision/command and serialize changed payloads on a worker. |
| Drawing writes rebuild the note's text index | `Items.editBlock` calls `updateNoteIndex` | Separate drawing-only content updates/timestamps from text-index rebuilding. Only textual canvas labels need searchable text updates. |
| Data is one JSON string inside a note block | `NoteEditor.kt` drawing branch, `NoteBlock.content` | Simple and encrypted, but expensive to rewrite as complexity grows. Add separate drawing/page/object rows and retain a reference in the block. |

Relevant source: [canvas](../app/src/main/java/app/securenote/vault/NoteDrawing.kt), [notes UI](../app/src/main/java/app/securenote/vault/NoteEditor.kt), [save queue](../app/src/main/java/app/securenote/vault/VaultModel.kt), [DAO](../app/src/main/java/app/securenote/vault/Database.kt).

## Research references and what to adopt

- [Samsung Notes' handwriting tools](https://www.samsung.com/us/support/answer/ANS10003634/) demonstrate tool/color settings, pinch zoom, separate stroke/area erasers and handwriting selection. Adopt explicit drawing/navigation modes and understandable eraser choices. S Pen-specific behavior is device-dependent; it is not a feature that can be assumed on every Android phone.
- [Goodnotes' lasso workflow](https://support.goodnotes.com/hc/en-us/articles/7353695644175-Select-Move-and-Edit-Content-With-the-Lasso-Tool) demonstrates selection, moving, alignment, resize and rotation. Adopt consistent object handles and selection filters. Keep duplication inside the vault; do not copy its clipboard/share/screenshot actions.
- [Google's Cahier sample](https://github.com/android/cahier) is the most relevant native implementation reference: Compose, Ink, drawing tools, undo/redo and local persistence. Its Apache-2.0 source can inform architecture, with attribution if code is reused. Its sharing, ordinary Room storage, external intents and lock-screen entry features are not the vault's security design. The [official technical walkthrough](https://android-developers.googleblog.com/2025/10/introducing-cahier-new-android-github.html) explains the live/finished ink split and repository boundaries.
- [Excalidraw](https://github.com/excalidraw/excalidraw) is a useful diagram interaction reference for shapes, arrows, object ordering and zoom. Its React/npm runtime and browser persistence are not a native Android drop-in. Its MIT license does not by itself make its default storage appropriate for this vault.
- [tldraw persistence](https://tldraw.dev/sdk-features/persistence) illustrates document snapshots and migrations, but its automatic `persistenceKey` writes to IndexedDB. Its [SDK licensing](https://tldraw.dev/community/license) also requires an appropriate production license. Offline license validation is supported; a network connection is not inherently required by a commercial/hobby license. The reason to avoid it here is the web integration/storage complexity and license dependency, not a claim that it necessarily uploads canvas content.

## Technology decision

| Option | Benefits | Work and fit | Decision |
|---|---|---|---|
| Expand the current Compose Path implementation | Full control, existing app integration, no new drawing dependency | We would own smoothing, pressure brushes, motion prediction, input batching and most geometry; still useful for simple shapes and previews | Keep a basic renderer/fallback and migrate document logic out of the composable. |
| Jetpack Ink plus our own document/tools/storage | Android-maintained freehand input, brush, geometry, rendering and compact input encoding | Does not supply a complete notebook, selection editor, vault lifecycle or storage policy; these remain ours | Recommended foundation, subject to lifecycle/security and storage round-trip validation. |
| Embed Excalidraw/tldraw in a WebView | Many diagram tools already exist | Adds a browser/JS boundary and requires replacing default persistence, clipboard, asset and export behavior; tldraw has production licensing requirements | Use as interaction references, not the default runtime. |

The [Ink release page](https://developer.android.com/jetpack/androidx/releases/ink) lists stable 1.0.0 and 1.1.0-alpha10 as of this research date. Start evaluation with pinned, aligned stable artifacts. The 1.1 line includes lifecycle fixes and experimental mesh erasing; stable does not mean free of relevant bugs. Verify the chosen version's detach/reattach and lock behavior before selecting the production baseline. Do not follow sample dependency versions blindly or mix Ink alpha and stable modules.

The same release history documents a partial-eraser serialization gap when the feature was introduced, followed by rendering improvements. That history is not proof the latest alpha still lacks serialization. It does mean mesh persistence must be checked against the exact chosen release rather than assumed. Partial erasing is not a feature we should promise merely because a preview looks correct.

## Proposed modules

These are logical packages initially, not a requirement to create many Gradle modules.

| Package | Responsibility | Candidate API/dependency |
|---|---|---|
| `drawing/document` | Drawing/page/object IDs, dimensions, z-order, revision, transforms, legacy decoding | Kotlin models and a bounded, versioned binary envelope |
| `drawing/input` | Tool routing, real event batches, pressure, cancellation, palm handling, viewport gestures | Android MotionEvent; `androidx.ink:ink-authoring` or its Compose adapter |
| `drawing/render` | Live ink, finished ink, vector shapes, text labels, selection overlays, previews | `ink-rendering`, `ink-strokes`, `ink-brush`; Android Canvas/Compose |
| `drawing/tools` | Pen/highlighter, stroke eraser, selection, shapes, viewport navigation | Stock Ink brushes; own tool state machine |
| `drawing/geometry` | View/world transforms, bounds index, lasso/eraser intersections, snapping | `ink-geometry` plus an application-owned spatial index |
| `drawing/history` | Undo/redo per completed action, cancellation and dirty revisions | Bounded command log with add/remove/replace/transform operations |
| `drawing/storage` | Incremental encrypted writes, format migration, snapshot loading, thumbnail lifecycle | Existing SQLCipher/Room3; `ink-storage` for input bytes |
| `drawing/ui` | Paper toolbar, color/width/opacity panels, page switcher and adaptive full-screen editor | Existing Compose/Material3; no remote assets/fonts |

The [Ink module guide](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-modules) covers authoring, rendering, geometry and storage roles. We can keep the present Android minimum of 26 as the product target during evaluation; Cahier demonstrates this target. Exact artifact requirements and native ABI/16 KiB compatibility must be checked when dependency resolution/builds resume. Low-latency front-buffer behavior varies by Android version; Android's [advanced stylus guide](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features) places that graphics path at API 29+, so it is not a latency promise for API 26–28.

## Document and input design

Store a page's logical width/height independently of the device. Render with a uniform scale and translation; convert input through the inverse matrix. Explicit selection transforms change objects; merely rotating/resizing the phone must not alter saved geometry. Use local/object coordinates and reject non-finite or extreme transforms.

Each object has a stable ID, kind, page ID, z-order, transform, bounds and revision. Freehand objects contain actual stroke inputs, a stock brush ID/version, size, color/opacity and the encoding version. Shape objects retain editable geometry. Text labels store text/style and participate in encrypted search. Image objects reference existing encrypted vault objects, never external URLs or plaintext files. Store the canonical drawing, not only a PNG: a thumbnail cannot restore editable strokes.

For freehand restoration, save the brush specification alongside encoded `StrokeInputBatch` data, as described in the [Ink persistence guide](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-persistent-storage). A stock-brush enum alone needs a pinned version/seed policy to avoid changing an old drawing's appearance on an engine upgrade. Custom brush import is outside the initial scope. Predicted points are temporary rendering assistance; persist actual input only.

Route interaction through an explicit state machine:

- In stylus mode, the stylus draws; fingers navigate. Palm events never become saved strokes. Offer a visible toggle for finger drawing on phones without a pen.
- In finger-drawing mode, one finger draws and two fingers navigate. A second finger cancels the provisional first-finger stroke before beginning a viewport gesture. Navigation itself is not an undoable document edit.
- An up event commits one completed action. OS cancellation, interrupted pointer ownership or vault lock cancels provisional input. Pen side buttons/eraser tips switch tools only where reported by the device.
- Capture batched historical positions and supported pressure/tilt. Android's [input guide](https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens) explains tool types, history and cancellation flags. Device palm rejection remains hardware/OS-dependent; a stylus-only drawing setting is an additional practical safeguard.

## Editing features and eraser correctness

First implement pen, pressure pen where available, marker/highlighter, custom color, favorites, width/opacity, stroke eraser, undo/redo, zoom/pan/fit, paper templates and a proportional inline preview. Highlighter blending must be tested for self-overlap and overlap with other objects. Offer a neutral constant-width brush for finger drawing and unsupported stylus axes.

Next implement lasso/rectangle selection, move, duplicate within the vault, delete, recolor, uniform resize, rotation, object ordering and editable line/arrow/rectangle/ellipse shapes. Selection should have a clear policy for fully-contained versus intersected objects. Layers can follow once ordering and selected-object transforms are reliable; a layer needs visible/locked state and active-layer editing semantics, not just an extra list in the toolbar.

Partial erasing needs a real editing representation:

- Prefer a verified geometry split/subtract path if the chosen Ink API can persist and restore its results faithfully.
- Otherwise persist original stroke data plus bounded, stroke-scoped erase geometry as application-owned operations, and replay/clip deterministically. That approach needs correct transforms, transparent compositing, layer scoping, history and eventual compaction. It is more work than stroke deletion.
- Do not implement erasing by drawing white: it fails on lined/dotted paper, images, transparency and later background changes.
- Use a swept eraser region between consecutive samples so fast gestures do not skip thin strokes. Query candidate object bounds before precise intersection. Rendering and reloading must agree after erase, selection transforms and undo/redo.

## Performance plan

1. Keep live ink separate from the finished scene. Reuse primitive/input buffers; avoid copying an ever-growing immutable point list per input sample. Keep UI toolbar state outside the sample-frequency path. Android's [Compose phase guidance](https://developer.android.com/develop/ui/compose/performance/phases) supports deferring rapidly changing work to drawing where practical.
2. Cache finished stroke geometry per object revision. Cull offscreen objects with a grid or R-tree selected by profiling. Keep a page/object index for hit testing. Add tiled render caches only when measured workloads justify them; an enormous bitmap for a zoomable page/board is not the storage model.
3. Encode changed stroke/object payloads on a worker after a completed command. Coalesce writes over the existing debounce period. Keep the last pending revision until commit and drain finished commands on normal lock where possible. An unfinished stroke may be cancelled on lock; show that behavior consistently.
4. Add encrypted `drawings`, `drawing_pages` and `drawing_objects` tables with bounded BLOB payloads and indexes on drawing/page/order. Store object changes and drawing revision in one transaction. A note block keeps a drawing ID. This needs an explicit database migration; it is not part of the current schema. Avoid rebuilding full note text search for non-textual stroke changes.
5. Use command deltas for undo/redo rather than a complete drawing snapshot for every action. Clear redo on a new committed edit. Apply a byte budget and command-count limit; keep undo history in RAM for the first version and clear it on lock. A local undo history is not an encrypted backup or recovery service.
6. Generate a bounded preview asynchronously after a settled revision, encode it directly into encrypted storage and share the vault's bounded preview budget. Revoke stale generation tasks by session/version and never publish a thumbnail from a revoked session.

Proposed acceptance targets, not measured results: under a 50,000-sample page workload on a physical 4 GB phone, p95 application frame work should fit the selected refresh rate (16.7 ms at 60 Hz; 8.3 ms at 120 Hz); input-to-display latency needs separate device measurement, since frame time is not pen latency. Aim for p95 completed-command save below 150 ms after coalescing, first usable preview below 300 ms and drawing-related CPU/native/graphics memory growth within a provisional 64 MiB budget above unlocked idle. That is an engineering budget to validate, not a feature guarantee. Sweep 1,000/10,000/50,000/100,000 samples and multiple pages; do not raise current limits until larger workloads pass. No per-sample database or JSON operations are acceptable in the proposed hot path.

## Security and lifecycle integration

Strokes, selection transforms, text labels, undo buffers and thumbnails are sensitive vault content. Keep canonical data inside SQLCipher; any large immutable assets use the existing encrypted object store. No plaintext snapshots, file-based PNG previews, clipboard exports, external drawing intents or network services are introduced. BLOB encoding is serialization, not encryption; SQLCipher or the authenticated object layer still supplies encryption at rest.

A renderer callback must capture the original vault session/version and verify it before queuing a completed action. Lock revokes authority first, rejects subsequent callbacks, cancels provisional strokes, detaches finished-stroke listeners, disposes render/native buffers, clears session history/selection and then lets the existing captured-save drain close the database. Reopening never accepts an old renderer callback just because a new session is unlocked. Ink's [authoring API](https://developer.android.com/reference/androidx/ink/authoring/InProgressStrokesView) provides cancellation/listener lifecycle primitives; it does not implement this application's authority policy.

Retain FLAG_SECURE on every editor window/dialog. Low-latency surfaces must be verified separately for screenshot, recording, recents and lock/reattach behavior. The [SurfaceView security contract](https://developer.android.com/reference/android/view/SurfaceView#setSecure(boolean)) describes secure surfaces and construction timing. Do not assume a third-party/internal surface automatically satisfies the vault policy or retrofit it through reflection. If the chosen authoring path cannot meet the lifecycle/capture boundary using supported APIs, use an application-owned normal Canvas rendering path with Ink's stroke/brush/renderer components until a secure fast path is demonstrated. Do not depend on restricted/internal flags to force a library rendering mode. There is no claim that every historical GPU/native buffer can be overwritten; the existing security contract still applies.

## Migration and compatibility

- Existing native JSON arrays remain readable. Convert one drawing transactionally when first edited: assign IDs, preserve point order, colors, current width interpretation and object order, and keep its original representation until the new document has a successful save/reopen round trip.
- Old native data does not record its original canvas aspect ratio. Do not invent a claim that the intended geometry is recoverable. Freeze a documented legacy viewport interpretation, preserve the current appearance at conversion, and let the user explicitly resize later. New drawings store dimensions from creation.
- Old Expo raster drawings imported as encrypted images remain images. A PNG cannot recover its original editable strokes or layers. It may be placed as a locked image object/background in a new canvas without modifying the encrypted original.
- Reject unknown versions and oversized/corrupt data without overwriting it. Add migration fixtures for both old point-array and color/width-object forms.
- Keep ARM64/x86_64, API 26+, the existing SQLCipher driver and native library alignment requirements. No cloud/account feature is required.

## Delivery sequence and acceptance

1. **Document/input foundation:** define world-space units and versioned schemas, old drawing fixtures, command semantics, session authority and dependency baseline. Prototype the renderer/lifecycle seam when builds resume. Confirm actual capture protection and cancellation before adding advanced tools.
2. **Useful editor:** full-screen Paper UI, inline preview, pen/highlighter, color/width/opacity, stroke eraser, undo/redo, templates, zoom/pan and explicit finger/stylus modes. Add visible limits and incremental saves.
3. **Editable sketches:** lasso/rectangle selection, transforms, shapes/arrows, text labels, vault-image placement and ordering. Complete partial-erasing storage/reload validation before enabling it.
4. **Larger notebooks:** multiple pages, thumbnails, optional layers and board mode after workload measurements. Handwriting recognition and PDF annotation are separate later projects, not implied by adopting Ink.

Before calling this complete, execute real Android tests for: stylus/finger switching; fast handwriting with historical samples; second-finger gesture cancellation; palm/cancel events; side buttons where available; aspect changes/fold/rotation; highlighter blending; erase across sparse/fast paths; redo invalidation; save failures; old-format migrations; screenshot/recents/recording protection; background/screen-off lock mid-stroke; stale handoff callbacks after re-unlock; and page/object/thumbnail round trips. Measure native/graphics allocations and long drawing sessions as well as CPU frame timing. Use synthetic data only in traces. Builds/tests are not authorized in this research pass and have not been run.
