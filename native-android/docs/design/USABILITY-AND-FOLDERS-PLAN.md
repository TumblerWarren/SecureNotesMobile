# Paper usability, reader and folder redesign

The [app-wide validation register](APP-WIDE-VALIDATION.md) records the systematic screen/state review, priorities, measured checks and release acceptance evidence. Use its issue IDs to track implementation; the specification below supplies design and folder-encryption details.

Research and code review: 27 October 2026. Status: proposed; no application changes or builds in this planning pass. Code baseline: local `99e075e`, matching the application tree built in GitHub commit `d0879350b0973a7de76dd8bb6ac307e8edce765e`.

## Direction and evidence

Keep the ivory, ink and terracotta palette. Replace the spacious landing-page treatment with layouts suited to repeated reading, editing and file management. Rework presentation and navigation while retaining encrypted object storage, SQLCipher metadata, native editors, isolated parsers and session revocation.

This is a source review plus primary-source research, not a new visual device audit. The user's observed spacing issues are consistent with the code below. The previous 19 passing emulator tests verify functional flows; they do not establish visual quality across devices.

| Confirmed issue | Existing source | Proposed correction |
| --- | --- | --- |
| Every block reserves a 48 dp options row, followed by 16 dp list spacing | `NoteEditor.kt`, `NativeNoteEditor` | Integrate a block handle into the active block; remove the separate row |
| Short text blocks reserve at least two lines; native text also adds padding and line spacing | `NoteEditor.kt`, `RichTextBlock` | Content-sized paragraphs with deliberate paragraph spacing; keep a useful empty-note editing area |
| Toolbar buttons have `enabled` but no selected/mixed state, and icon colors are hardcoded | `NoteEditor.kt`, `PaperEditorTool` | State-aware toggle controls with distinct selected, mixed and disabled treatments |
| Cards use a common 188 dp minimum, even for short notes and audio files | `PaperLibrary.kt`, `PaperItemCard` | Type-specific layouts; notes list by default, optional grid; audio/document rows |
| Library outer margin is 24 dp, with additional card padding; large headings precede content | `PaperLibrary.kt` | Compact 16 dp outer margin and smaller, collapsible heading |
| PDF has 16 dp padding, fit-whole-page bitmap display and fixed 768/1536 render widths | `MainActivity.kt`, `PdfViewer` | Viewport-width rendering, continuous scrolling and zoom-aware tiles |
| Category filters exist, but no folder entities or item membership | `Database.kt` | Real encrypted folder hierarchy plus independent type filters |

## Research translated into decisions

1. Android recommends 16 dp as a standard compact-screen margin and adapting structure to available width. Use that as a starting point, then validate reading comfort. Source: [Android content structure](https://developer.android.com/design/ui/mobile/guides/layout-and-content/content-structure).
2. Android recommends touch targets of at least 48 × 48 dp. Remove unnecessary containers and duplicate padding, rather than reducing tap targets. Source: [Android touch targets](https://support.google.com/accessibility/android/answer/7101858).
3. Compose distinguishes ordinary actions from toggle buttons and exposes appearance changes for selection. Formatting must follow the actual editor state, rather than a remembered last tap. Source: [Compose icon buttons](https://developer.android.com/develop/ui/compose/components/icon-button).
4. OneNote's Android command-bar work emphasizes access to formatting while typing. Use that interaction principle, with a small formatting group and a separate insertion sheet. Source: [Microsoft's Android command-bar update](https://techcommunity.microsoft.com/blog/microsoft_365blog/whats-new-in-onenote-android/3896328). The article was discoverable in search; a direct fetch timed out, so this is supporting context rather than a detailed visual specification.
5. Acrobat offers continuous and single-page navigation plus full-screen reading. These are useful interaction references, not evidence that its engine or cloud features belong in this vault. Source: [Acrobat viewing modes](https://helpx.adobe.com/acrobat/mobile/view-manage-files/viewing-modes.html).
6. Drive demonstrates named folders, subfolders and a destination picker with “Move here.” Adopt this familiar management flow within the encrypted vault. Source: [Drive folder organization](https://support.google.com/drive/answer/2375091?co=GENIE.Platform%3DAndroid&hl=en).
7. The current Android PDF release page lists `1.0.0-beta01`; its Compose viewer remains experimental. Release history records compatibility down to API 28, whereas this app supports API 26. It also records newer Compose tooling requirements and added autofill behavior. A replacement therefore needs a pinned-version spike and capability/security checks, not a dependency swap. Sources: [PDF releases](https://developer.android.com/jetpack/androidx/releases/pdf), [Android PDF overview](https://developer.android.com/develop/ui/views/layout/pdf/pdf-viewer).

All dimensions and product choices below are proposals for this app, not claims that a competitor mandates them.

## 1. Shared layout and navigation

- Spacing tokens: 4, 8, 12, 16, 24 and 32 dp. Compact outer margins: 16 dp; dense media grid gutters: 4–8 dp. Apply a single outer content margin, without stacking it at every container.
- Keep controls at least 48 dp. Use 20–24 dp icons inside the hit area. Disabled controls use disabled colors throughout, including custom icons.
- One top app bar per screen: Back, title and relevant actions. Move repetitive security/status copy into details or settings. Keep Lock reachable; do not hide it with reader chrome.
- Proposed primary destinations: Vault, Notes, Folders, Settings. Media types remain filters rather than adding many bottom-navigation buttons.
- Vault exposes All, Images, Videos, Audio, PDFs, EPUB and Other files. Use a scrollable filter strip on phones; preserve a clear selected indicator. Notes also remain accessible in All.
- Remove the large greeting from populated screens. A compact title, search entry point and sort/view controls should leave the first content visible on a small phone.
- Apply system and keyboard insets once, with explicit ownership. The note toolbar sits above the keyboard; library navigation is absent inside readers/editors.
- On wide windows, use a folder/list pane beside content rather than stretching a paragraph across the entire display. A capped prose width is intentional for long-form reading; images and PDFs use the available viewer area.

## 2. Notes arrangement and editor

### Library

Default to content-sized rows: title, up to two preview lines and compact date/folder metadata. Put the menu in the row, with no dedicated footer. Offer a grid preference with naturally sized cards and consistent alignment. Show pinned notes once in a compact section. Preserve sorting and scroll position for each folder/filter.

### Writing surface

- Reduce title size relative to the current display heading; use a 12–16 dp gap before the first paragraph.
- Use 8 dp as the initial inter-block gap. Remove the always-present block options row. Reveal an accessible handle for the active block; its menu provides move/delete. Provide a discoverable “Manage blocks” mode so these operations do not rely on long press alone.
- Ordinary paragraphs grow with text. Keep line height intentional and measured in sp/dp; stop mixing raw-pixel padding with density-scaled spacing. Preserve user-authored blank lines; do not trim stored text to make the layout denser.
- Avoid nested scrolling within ordinary long paragraphs. Retain block virtualization and draft retention; large-text behavior needs profiling before changing the current line cap.
- Keep tables horizontally scrollable inside their own bounds. Put row/column controls in a contextual sheet. Drawing previews use an appropriate aspect ratio and a clear Open canvas action. Attachments show file name, type and size in compact rows.

### Toolbox behavior

Use a compact formatting bar: Bold, Italic, Underline, List, More; place Table, Drawing, Attachment and Link in a reachable Insert/More sheet. At narrow widths or large fonts, overflow less-used actions instead of shrinking touch targets.

Introduce `EditorSelectionState` containing active block ID, selection range, revision and per-tool `on / off / mixed / unavailable` states. Recompute from native span and paragraph state on cursor movement, selection, text edits, formatting changes and restored focus. Include pending insertion styles for a collapsed caret and define boundary behavior explicitly. Range selections use complete coverage, including combined bold/italic spans; partial coverage produces mixed state. Mixed → apply to the whole selection; fully applied → remove that formatting only.

Selected tools use a tonal fill, border and selected/toggle semantics. Mixed tools use a distinct mark and spoken “mixed” state. Disabled controls look disabled. Table/attachment insertion are momentary actions and never stay highlighted. In the canvas, pen/eraser/navigation are mutually exclusive; color, stroke width and opacity show current values; undo/redo indicate availability.

Tool clicks preserve the target editor and selection while menus are open. Do not replace the native Editable or serialize HTML just to update button appearance. Dispose state and callbacks on lock.

## 3. PDF and other viewers

### PDF first release

- Fit width by default, with continuous vertical pages and 4–8 dp page separation. Keep Fit page as an option. White borders inside the PDF itself remain document content.
- Double-tap zoom, pinch zoom with clamped panning, page-number jump, a thumbnail sheet and tap-to-hide reading controls. Provide visible accessible alternatives to gestures.
- Save last page and within-page position in encrypted metadata. Store normalized anchors rather than raw screen pixels; restore appropriately after rotation.
- Render based on measured viewport pixels. Keep a low-resolution page visible while requesting higher-resolution visible tiles; do not enlarge a fixed bitmap and call it high-resolution zoom.
- Extend `PdfClient`/`PdfService` with validated page dimensions and bounded tile requests. Prioritize visible work; cancel superseded requests. Preserve service isolation, revocable descriptors, timeouts and per-session reply validation.
- Use a byte-bounded shared PDF bitmap budget, initially 32 MiB subject to device profiling. Include thumbnails in the budget and account separately for displayed/native buffers. Prefetch at most the adjacent page when budget permits. Never cache plaintext pages to disk.

### Later PDF capability spike

Evaluate the framework and pinned AndroidX APIs for search, outlines and text selection across supported OS versions. Do not promise OCR or reflow from bitmap rendering. Keep an API 26–27 fallback. Reject any integration requiring decrypted temporary files, broad URI access, external viewers, plaintext saved state, enabled content capture/autofill or uncontrolled external actions. Review licenses, native dependencies and actual parser-process behavior before choosing a different engine.

### Other screens

Images/videos use the viewer area with overlay controls and intentional aspect-ratio letterboxing. Audio uses a compact queue/list and an expandable player. EPUB gets reading-width/font controls and a compact progress bar. Settings and import forms share the same alignment and field spacing. Review empty, loading and error states alongside populated layouts.

## 4. Real folders and media segregation

Support both automatic type views and user-created folders. “Images” means all encrypted images matching the current scope; “Travel / Kerala” is a real location that may contain photos, videos, notes and PDFs. A file has one parent folder; filters do not copy or relocate it.

Required flows:

- Create, rename, color, move and remove folders; nested folders and breadcrumbs.
- Create notes or import into the current folder. Imports launched from a global type view default to Unfiled with an explicit destination choice.
- Long-press multi-selection, Select all in scope, Move to, and destination browsing with New folder and Move here. Revalidate selection and destination at commit time.
- Folder-scoped media filters and search; explicit “Search entire vault” option. Search results show their folder path.
- Sort by name, modified date or size where applicable; list/grid preferences appropriate to type.
- Back returns to the previous folder and scroll position; folder navigation state is cleared at lock.
- Removing a nonempty folder initially means “Remove folder, keep contents”: promote direct children to its parent, resolving naming conflicts before committing. Do not silently delete media. Recursive content deletion, if later added, is a separate explicit action with affected counts and existing deletion semantics.

### Storage and migration

Propose schema 4: `folders(id, parentId, name, normalizedName, color, created, updated)` and nullable `items.folderId`. Null denotes Unfiled. Store these only in SQLCipher; encrypted objects continue using opaque IDs, never readable folder paths on disk.

Index folder parent/name and item folder/sort fields. Enforce sibling naming rules, referential integrity and cycle prevention within transactions. Allow bulk updates in bounded batches inside a single logical transaction; one invalid destination aborts the move. Renaming/moving changes metadata, not ciphertext or attachment IDs.

The 3→4 migration must account for Room table reconstruction, FTS external-content triggers and existing foreign keys if adding item constraints. Existing items remain Unfiled, with stable IDs, keys, notes, drawings, previews and reading progress. Verify migration from schema 3 and earlier supported paths, rollback-on-failure and interrupted operations. Counts must be grouped queries rather than one query per folder; global views remain paged.

## 5. Modules and implementation order

Use logical packages initially, without an unnecessary whole-project rewrite:

| Phase | Components | Reviewable outcome |
| --- | --- | --- |
| 1 | `ui/design`, spacing tokens, shared app bar, insets policy | Annotated layouts for library, note editor, canvas, folder and PDF; compact and wide sizes |
| 2 | `ui/library`, `ui/notes`, selection-state adapter | Dense content layouts, accurate tool states, keyboard/focus fixes |
| 3 | Folder entities/DAO, `FolderRepository`, migration, `ui/folders` | End-to-end create/import/move/search/remove-folder flows |
| 4 | `reader/pdf`, tile scheduler and isolated renderer protocol | Continuous width-fit reading, zoom, thumbnails and encrypted resume state |
| 5 | Remaining viewers, settings, accessibility and performance review | Consistent behavior and recorded validation results |

Folder schema work may start once navigation is agreed; no PDF library replacement is required for the initial reader improvements.

## 6. Extended spacing audit, including Settings

Scope extension requested after the initial plan. These are source-confirmed layout mechanisms and proposed fixes; actual gap dimensions on a device must be checked with rendered bounds and screenshots. Do not label every spacer or intentional aspect-ratio border a defect.

| Screen / component | Finding | Planned correction and check |
| --- | --- | --- |
| Settings: legacy import → Change password | `SettingsDialog` applies `Arrangement.spacedBy(16.dp)` to all children. Both actions also have Material button sizing/padding. There is no special flexible spacer between these two actions; the uniform gap and button interiors create the separation. | Replace the modal settings form with a full-screen Settings destination. Use compact full-width rows grouped into Security and Import & storage. Use separators within groups and 16–24 dp only between groups. Retain at least 48 dp action targets. |
| Settings: unused left/right area | Settings is an `AlertDialog`, whose width and content padding constrain the nested column. Action buttons size to their labels, while descriptive paragraphs span the text slot. | One 16 dp screen margin, common leading alignment for labels/descriptions, trailing chevrons, full-row hit areas. On wide windows use a settings/detail pane. |
| Settings: password form | Three fields and a save action expand inside the settings dialog and share the same general-purpose spacing. | Open a dedicated Change password screen/sheet with keyboard-aware scrolling, full-width fields, 12 dp field gaps and a visible submit action. Keep progress/result visible before leaving the form; verify password fields clear on lock and disposal. |
| Legacy import password dialog | Its column has no explicit gap between the explanatory paragraph and password field. This is a density inconsistency in the opposite direction. | Use 12–16 dp between explanation and field; keep the dialog for this short task, with properly separated Import/Cancel actions. |
| Notes paragraphs | Two-line minimum, native padding, 16 dp inter-block gaps and a 48 dp trailing options row accumulate. | Remove the empty options row, size ordinary paragraphs to content and check short/long/table/drawing combinations. Preserve authored blank lines. |
| Library header and cards | Heading → count → search → filters adds multiple spacers; all cards reserve at least 188 dp; grid bottom padding is 96 dp in addition to a separate navigation row. | Shorten the populated header, use type-specific rows/cards, and calculate bottom clearance from the actual floating action button and system insets. Check that the last item is reachable and unobscured without a permanent large empty tail. |
| Audio player | A 160 dp artwork placeholder, 20/28/20 dp spacers and a separate fixed-height 160 dp player stack consume substantial height. | Use a compact player with integrated seek/transport controls. Real artwork may expand on larger screens; a generic icon should not force an album-art-sized empty panel. Verify small-height landscape and large fonts. |
| Video player | A weighted video area sits above a separate title/metadata panel with 24 dp side/bottom padding and a 20 dp top gap. | Put playback metadata in collapsible overlays/details, maximizing playback space. Keep aspect ratio intact; do not crop video just to eliminate letterboxing. |
| PDF | Viewer padding plus whole-page fitting produces a small page and unused viewport area. | Continuous fit-width layout, small page gutters, overlay navigation and optional Fit page. Distinguish PDF-authored margins from app-added margins. |
| EPUB / plain text | Fixed 24 dp text margins and multiple reader/header/footer areas can narrow compact screens. | Reader-specific 12/16/24 dp margin choices, compact controls and intentional wide-screen prose limits. Preserve readability rather than indiscriminately removing margins. |
| Canvas, recovery, lock and add/import dialogs | Not yet visually verified in this audit. The canvas has its own safe-area padding, while the app also manages system/IME insets. | Inspect each independently, including tool rows, dialogs and keyboard states. Record whether insets are consumed correctly before changing them; nested inset modifiers alone do not prove duplicate padding. |

Settings structure: a concise title followed by Security rows (Change password, current lock behavior), Import & storage rows (Import files, Import folder, Import old database), and a compact About/storage explanation. Keep recovery information discoverable. Long security explanations belong in detail views rather than between everyday actions. Selecting an import action from Settings must use the same destination-aware flow as the library.

For each screen, record a before image, component bounds, proposed spacing and an after image at the same window size/font scale. Include populated, empty, busy, error and keyboard-visible states. The audit is complete only when this visual evidence exists; scanning `padding()` calls is a starting point.

## 7. Whole-folder import, encryption and arrangement

This is required scope in addition to the folder hierarchy in section 4. The earlier plan described moving existing encrypted objects but did not fully define importing and encrypting an external folder.

### User flows

1. **Import folder into vault:** select a source folder through Android's directory picker; unlock again when returning if the picker caused the app to lock; choose a vault destination; preserve subfolders by default; start importing. Provide an explicit flatten option only if needed, with collision handling shown before import.
2. **Create a vault folder, then import:** files selected from Gallery/Documents go directly into that folder. Nested folder creation, notes and attachments participate in the same organization model.
3. **Arrange existing encrypted content:** select individual items or a group and Move to a folder. Offer a deliberate Group by type operation for Images, Videos, Audio, PDFs, EPUB and Other, with a preview. Type filters remain available without reorganizing anything. Do not automatically undo the user's custom arrangement on future imports.
4. **Import progress:** show completed/total files when known, discovered files while scanning, byte progress where reported, current stage, cancellation and per-file failures. Unknown sizes must not become fake percentage progress. Finish with a summary and Open destination.

Example: importing device `Travel/` with `Travel/Photos/a.jpg`, `Travel/Clips/b.mp4` and `Travel/Bookings/ticket.pdf` creates the equivalent hierarchy inside the chosen vault destination. The Images filter can then show the photo without moving it out of Travel.

The confirmation must say that the operation encrypts a copy inside the vault. External source files remain available in their original location. Do not call the operation successful encryption of the original device folder or silently remove source files. If optional source removal is ever added, it is a separate operation after verified import with provider-specific failure handling.

### Encryption and storage design

- Keep each imported file as an individually authenticated encrypted object with its own key, using the existing streaming encryption design. This allows seeking inside video/PDF/audio without decrypting an entire directory archive.
- Folder names, hierarchy, item membership, source-to-destination mapping and import-job records live in the encrypted SQLCipher database. Physical object storage keeps opaque IDs; external folder names must not become on-disk paths or plaintext job logs.
- The folder is protected by the vault session/password. The proposed feature does not yet specify independent passwords for each folder; that would require a separate key hierarchy and selective-lock design.
- Complete and durably commit each encrypted object before publishing its item row and folder membership. Use small metadata transactions per file or bounded batch; never hold one database transaction open while streaming an entire source directory.
- Preserve empty subfolders where the source provider exposes them. A failed file remains a failed job entry rather than a blank or playable placeholder. Completed files stay valid when the user cancels; show partial completion and let the user remove the imported batch explicitly if desired.
- Failed/interrupted writes remove temporary ciphertext; startup orphan cleanup handles crashes between object creation and item publication. Test crash points so cleanup does not delete objects owned by an active import.

### Android integration and performance

Use `ActivityResultContracts.OpenDocumentTree` with scoped provider access and `ContentResolver`/`DocumentsContract` enumeration. Android supports selected-directory access including subdirectories, with directory restrictions on Android 11 and later. Source: [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).

Add an `ImportCoordinator`, directory enumerator, encrypted job DAO and destination-aware repository methods. Capture the destination before launching the picker; validate it after re-unlock. If it was removed, ask for a replacement destination. Attachment imports also capture their owning note rather than depending on whichever screen is now open.

Enumerate incrementally with a bounded queue, bounded nesting and visited document IDs; tolerate provider errors, renamed/missing files, unsupported virtual documents and unknown lengths. Initially stream one file at a time and throttle UI progress; benchmark before increasing concurrency. Use provider child queries instead of repeatedly listing the same directory. Close descriptors promptly and release persistable URI grants once no retained job needs them.

Pause/cancel the active work on lock, revoke keys/readers, and resume only after unlock. For the first version, an interrupted file restarts while completed entries are skipped using encrypted job state and source identity/version checks. Do not keep plaintext keys in a background worker to support unattended import. Repeated imports use a documented Keep both/Skip collision policy; never silently overwrite a vault item. Directory names are display metadata, never trusted filesystem paths.

### Required additions to delivery and validation

Extend phase 1 to include a Settings screen and the full cross-screen audit above. Extend phase 3 to include folder-tree import, encrypted job records, type-based arrangement, destination-aware file/attachment import and resumable partial completion. These are acceptance requirements, not optional follow-up ideas.

Test nested and empty folders, mixed media, duplicate names, unknown-size/cloud-backed providers, a large directory, a file larger than 4 GiB, destination deletion, lock during enumeration/encryption, process death, partial permission loss and cancellation. Verify hierarchy and contents after reopening the vault. Verify that filenames/job metadata do not appear in logs/plaintext preferences and that folder moves do not rewrite encrypted media. Recheck immediate lock behavior when entering and returning from Android pickers.

## Acceptance checks

- Compare before/after layouts at 320, 360 and 412 dp widths; test landscape, a wide/foldable window, RTL, long names and font scales 1.0/1.3/2.0. No clipped actions or overlapping touch targets. The first normal library item must be discoverable without a decorative header consuming the viewport.
- Short paragraphs have no artificial options row or forced second line. The caret stays visible with the keyboard open. Toolbar buttons track caret/range/mixed state, including after reopening a note, switching blocks and dismissing an insertion sheet.
- Dense layout tests retain readable type and accessible targets. Screenshot comparisons use synthetic content in an isolated test setup; production screenshot protection remains enabled. Never claim screenshot coverage from existing functional tests alone.
- Folder migrations preserve encrypted contents and links. Test duplicate names, nested moves, cycles, bulk moves, source/destination removal, lock during a move and imports that outlive navigation.
- Folder moves perform no media decrypt/re-encrypt. A large synthetic library remains paged and avoids per-folder count queries. Establish baseline scroll/frame/memory measurements before setting device-specific targets.
- PDF test set includes mixed page sizes/rotation, a scanned document, a very tall page, a large page count and malformed input. Check fit-width, zoom clarity, page jumping, cache bounds, cancellation and session revocation during active rendering.
- Measure notes typing/cursor and library/PDF scrolling on a representative physical Android device using frame timing and memory traces. Test cached as well as first-open behavior. Emulator success alone is not a performance measurement.
- Run GitHub build, lint, core and device tests after implementation. Add selection-state and folder-transaction tests plus targeted layout assertions. The latest green build covers the previous implementation, not this proposal.
