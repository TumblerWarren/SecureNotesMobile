# Native notes editor

The native Android rewrite already replaced the old Expo notes editor with Kotlin/Compose and native Android text editing. A report about the old app does not establish a reproduced bug in the native preview. The Paper UI and this notes follow-up remain uncompiled and untested while the user's build pause is active.

## Source changes after review

- An editor session retains immutable rich-text snapshots and encoded table drafts above the lazy block list. Recycling a view therefore does not restore an older database value while its save is pending. Drawing state and undo history also live in that session. Opening a note waits for queued writes before loading its blocks.
- Native formatting changes spans in the existing Editable instead of calling setText. Bold, italic and underline can toggle for a selection or at the insertion point. Link insertion preserves the original target range, applies the link to selected text and rejects a stale target. Only HTTP/HTTPS links are accepted; the editor does not open them externally.
- Bullet commands act on selected/current lines without adding an unconditional blank paragraph. Enter continues a bullet; Enter on an empty bullet exits the list. Imported Android bullet spans are normalized to the editable bullet representation.
- Text snapshots copy approved formatting spans only, excluding composing markers, text watchers and native views. HTML serialization runs after the save debounce on a background dispatcher instead of on every keystroke. Existing encrypted HTML blocks remain compatible; no new database schema or cryptographic format is introduced.
- A queued write remains pending until it commits. A new keystroke cancels its debounce timer, rather than cancelling a database write already in progress. Completion removes only that exact pending snapshot, preserving a newer edit that arrived during the save. Failed saves remain available for a subsequent edit/flush to retry. Normal lock still revokes readers first and drains captured saves before closing the encrypted database when possible; abrupt termination or storage failure can still lose changes.
- Reordering updates positions only and cannot write stale text back over a newer edit. Removing the last block creates an empty paragraph atomically, preventing the retained legacy body from reappearing on reopening. Initial block conversion is transactional. Renaming also updates the note's timestamp.
- Tables validate their shape, retain short-row values through rectangular padding and expose their existing 20-row/12-column limits through disabled controls. A malformed table stays unchanged and displays an error instead of becoming an editable blank fallback.
- Drawings retain undo history for pen strokes, erase and clear operations. Erasing tests line segments as well as sampled vertices. Taps create dots; cancelled gestures restore their prior state. Drawing points and pen width are validated, and malformed drawings stay unchanged. Undo is limited to 20 entries and a 200,000-point history budget; strokes retain the existing 5,000-point/50,000-total-point bounds. The eraser removes a whole intersected stroke. Undo history is session-only and is not persisted to disk.
- Cancelling an attachment picker clears its pending note association. Native text controls disable saved text state, external text actions, autofill, content capture, IME learning requests and fullscreen extracted editing. Editor disposal/lock detaches native callbacks and clears native text and draft references.

## Verification status

`NoteEditingTest` adds unexecuted Android regression source for native Editable/selection stability, style toggling and HTML round trips, unsaved-draft recreation, internal link targets, bullet continuation/exit, transient-span exclusion, table preservation, segment erasing, undo across recreated drawing views, position-only reordering and last-block removal/reopening. Existing UI round-trip coverage remains in place.

Only Kotlin source formatting/parsing, static inspection and whitespace checks have been performed. No Gradle task, build, device/emulator test or CI run has been started for these changes. Once builds resume, execute the Android regressions and existing UI/security tests, then verify IME composition, selection handles, scrolling and recycling, rapid edits/reordering, save failures, picker cancellation and lock/reopen behavior on a physical device. No measured typing latency or production-readiness claim is made.

## Canvas foundation implementation

The first canvas upgrade milestone adds a secure full-screen Paper dialog with an explicit Edit drawing preview; fixed page aspect and proportional stroke widths; zoom/pan in Navigate mode and Fit; pen, translucent highlighter and straight-line tools; custom hexadecimal color, width and opacity controls; blank/lined/grid/dot paper; redo with invalidation on a new edit; and visible input-limit feedback. A version-1 JSON envelope stores aspect, paper and strokes inside the existing encrypted block. Previous arrays remain readable; their viewport interpretation is frozen from the first measured inline preview. No claim is made to recover an old drawing's unknown original dimensions.

Drawing encoding now runs after debounce on a background dispatcher over captured immutable stroke lists. The existing SQLCipher block storage and lock/save queue remain in use. Test source adds envelope round trips, unknown-version rejection and redo invalidation; these tests have not been executed.

This milestone uses the existing native Canvas renderer. Jetpack Ink adoption, pressure/tilt and palm-specific routing, lasso and selection transforms, additional editable shapes/text/images, partial erasing, per-object database persistence, multiple pages and layers remain outstanding from the research proposal. Undo currently covers stroke edits; paper/tool/view changes do not enter stroke history. This is not the complete proposed canvas system. Builds, device tests and CI remain paused.


## Saving and allocation optimization follow-up

The [notes performance follow-up](NOTES-PERFORMANCE.md) supersedes the foundation's whole-drawing-block persistence: new saves use per-stroke SQLCipher rows and a revisioned block manifest (database version 3). It also adds reusable live input/Path buffers, finished-path caching, deferred table encoding, duplicate query suppression, selective search aggregation and bounded save coalescing. The older drawing formats remain readable. All runtime, schema migration and performance verification is still pending under the build pause.
