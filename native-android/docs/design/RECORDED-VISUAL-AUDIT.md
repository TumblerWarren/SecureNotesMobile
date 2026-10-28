# Recorded visual audit

The previous green build and eight selected screenshots were insufficient evidence for app-wide visual quality. This review records the current interfaces before proposing another layout revision.

## Completed evidence

[Download videos, full-resolution captures, layout trees and offline gallery](https://github.com/TumblerWarren/SecureNotesMobile/actions/runs/40628318457/artifacts/12790707302). Extract the archive and open `reports/visual-walkthrough/index.html` (or locate `index.html` inside the extracted folder).

[Workflow](https://github.com/TumblerWarren/SecureNotesMobile/actions/runs/40628318457), application/test source `9ccf4130a1e203a0108c365b7c8665749fd49987`:

| Configuration | Captures | Recording duration | Capture failures |
| --- | ---: | ---: | ---: |
| Portrait, normal text | 52 | 66.5 s | 0 |
| Portrait, actual Android 2× text | 52 | 53.8 s | 0 |
| Landscape, normal text | 52 | 53.5 s | 0 |

All **156 final captures** were inspected through 39 labelled contact sheets. All three capture tests passed, recording durations were checked against the capture indices, and the separate functional job passed its 28 emulator tests plus build/release/lint checks. These successes establish evidence collection and functional checks; they do not close the visual defects below. This revision adds recording/review infrastructure and the redesign plan; production UI layouts are unchanged.

The videos show actual synthetic interactions, including drawing and media play/pause. The protected video picture stays black by design. Recovery-key display uses a synthetic all-zero placeholder, not a generated or real recovery key. Image names describe intended checkpoints; for example the normal-portrait search checkpoint caught the keyboard hidden, while the large-text and landscape search captures show it open. Keyboard-open views are also captured in notes and password/recovery forms.

## Evidence collection

`VisualWalkthroughTest` runs the actual `VaultApp` composables, native text editors and readers in a separate test activity with disposable synthetic encrypted content. It captures portrait, 2× text and landscape workflows. Each visited state produces a whole-display PNG plus a Compose semantics tree with layout bounds. Screen recordings show transitions and interactions. Contact sheets and a per-configuration index make omissions visible. A failed capture is an audit failure rather than a silently skipped screen.

The harness resides in the test APK. It never opens the production vault, and production screenshot blocking stays enabled. Only synthetic dialog windows have their secure flag cleared by the test harness for recording. The native protected video surface stays protected, so its picture can appear black in recordings; surrounding player controls remain reviewable. These recordings do not prove the behavior of the production Activity's lifecycle or authentication.

The final host matches MainActivity's resize and system-bar appearance settings, and changes Android's actual system font scale so dialogs and native views use the requested text size.

Coverage includes populated and scrolled library, grid, sort, selection, move/group dialogs, search with keyboard, empty results, Settings top/bottom, password form with keyboard, folders and their dialogs, notes with keyboard and selected formatting, block controls, table expansion, drawing preview/editor/properties, PDF navigation/thumbnails/jump/zoom, image zoom, EPUB contents, text, audio/video controls, unsupported documents, deletion and locking. This is an enumerated workflow sample, not proof that every possible application state was exercised. System file-provider grant flows and physical-device behavior need separate validation.

## Confirmed baseline problem

The previous normal portrait library capture already shows a substantial density problem: the first item begins around the middle of the display. Brand, action buttons, title, item count, search, filters and a section label consume successive rows. Reducing the outside margin alone does not resolve this stack.

The source also retains an 80 dp trailing library pad, 48 dp card-menu controls in a dedicated metadata footer and nested folder lists inside the library heading. These are construction facts; their effects in each configuration must be assessed against the new captures rather than assumed acceptable.

## Review method

For each captured state, record the visible symptom, screenshot filename, affected bounds and proposed change. Distinguish useful reading whitespace from empty chrome. Judge populated and empty states separately. Keep essential touch targets at least 48 dp; reduce redundant rows, duplicate metadata and nested padding before reducing target sizes. Check selected tools visually, not just through semantics. Check the final item, action reachability, keyboard resize, long labels and landscape height.

The resulting plan must identify structural changes and list before/after capture pairs for verification. Passing functional tests is not the visual acceptance criterion. No claim that the remaining spacing issues are fixed is made by adding this harness.

The rich-text loader already removes the two paragraph terminators added by Android HTML serialization. The initial trailing-newline hypothesis was ruled out as the cause of the reported spacing. Do not trim user-authored line breaks or rewrite the editor on that assumption.

## Findings from the recorded baseline

Evidence below refers to capture filename suffixes in the offline gallery. Findings are observations of synthetic content linked to source construction; V15 explicitly identifies a pilot-only transient. The pilot used system-default text for some dialogs even in its simulated large-text run; that limitation was identified and corrected before the final system-font run. Pilot table/drawing screenshots showed incorrectly constructed fixtures and are excluded from application findings.

| ID | Priority | Recorded evidence | Concrete improvement |
| --- | --- | --- | --- |
| V01 | P1 | `portrait-*-library-top`, `landscape-*-library-top`: brand, actions, title, count, search, tabs and section label stack above the first item. The initial landscape view contains no item. | One collection app bar with title, count and overflow; search below it; one compact type-filter row. Move Select/Sort/View into the app bar or overflow. On short-height landscape screens use inline search and side navigation; merely shrinking the portrait stack is insufficient. Remove the generic Items heading. Keep 48 dp action targets. |
| V02 | P1 | `large-09-search-keyboard`, `landscape-09-search-keyboard`: search is clipped or absent above the keyboard because the header/navigation/creation chrome occupies the remaining height. The large-text Add bar consumes a further row. | Hide Add and collection navigation while search has keyboard focus; use a close-search action and IME Search. Keep results immediately below the search/filter controls. Show creation actions again after leaving search. |
| V03 | P1 | `large-*-folder-contents`: Parent, the entire breadcrumb and New folder compete in one row; the path wraps a letter per line and pushes content away. | App bar shows the current folder name with bounded wrapping. Put a horizontally scrollable ancestor breadcrumb on its own line and New folder in the action menu. Use an icon for Up. |
| V04 | P1 | `portrait-*-folders-top`, `*-folder-contents`: child folders are embedded in the library heading above all search/filter controls. Landscape folder rows occupy almost the entire content area. | One scrollable collection containing folder rows and file rows; put search above that collection. Remove the nested capped folder list from the heading. Folder contents and Unfiled need explicit scopes. |
| V05 | P1 | `landscape-*-folders-top`, pilot `landscape-*-folder-menu`: the floating Add button overlaps a folder's menu target. The attempted menu click opened Add instead. | Reserve a dedicated creation action outside row hit areas, or place it in the app bar on short-height screens. Verify pointer hit targets as well as semantics and paint bounds. |
| V06 | P2 | `*-library-scrolled`, `portrait-*-library-grid`: file badges, names, folder labels and metadata each consume separate rows. A full 48 dp menu footer stretches cards; dates wrap in narrow grid cards. | Compact rows for notes/documents/audio: leading type icon, weighted title/snippet, trailing menu. Combine date/type/size into one metadata line. Keep media thumbnails in the media grid; allow optional note cards without a separate menu footer. |
| V07 | P2 | `portrait-*-settings-top`: a multi-line auto-lock explanation plus spacing separates Change password from Import. At actual large text, leading glyphs in action rows are clipped and explanatory text dominates the page. | Group the primary settings actions closely. Make auto-lock a short summary row with an expandable explanation. Use consistent section separators and 16 dp section spacing, with one horizontal inset owner. |
| V08 | P1 | `large-13-password-form`, `large-14-password-keyboard`, `landscape-14-password-keyboard`: Settings/Change password titles fragment across many lines at actual system large text. The focused password field is clipped or hidden with the keyboard even with the corrected host resize mode. | Compact icon Back/Lock app bar; independent form scrolling and explicit focused-field visibility. Keep Save reachable above the IME or through a clear Done action. Check every field, not just the first. |
| V09 | P1 | `landscape-*-add-dialog`: the Add dialog displays New note but clips later import actions, with no usable scrolling path in the current content column. Other menus need the same height audit. | Replace the oversized Add dialog with a concise, scrollable action sheet and aligned icon/label rows. Constrain dialog text regions to available height and keep confirmation actions visible. |
| V10 | P1 | `*-image-zoom`, `*-pdf-zoomed`: zoomed image/PDF content paints over the app bar and outside its assigned viewport. PDF zoom can hide Done and the other toolbar actions entirely. | Clip native image/PDF containers and their drawing canvases to the reader viewport before applying transforms. Bound pan and zoom; place controls in a separate, stable layer. Verify Fit, pan, rotate and zoom at both orientations. |
| V11 | P2 | `*-pdf-thumbnails`: one large page occupies almost the entire chooser, with its page label partially cut by the scroll edge. | Responsive thumbnail grid with bounded page aspect ratio, page number, current-page selection and useful visible neighbours. Keep page jump and zoom actions discoverable from one reader toolbar. Preserve native rendering and bounded tile caches. |
| V12 | P2 | `*-epub-reading`: generous HTML paragraph separation combines with native line spacing, producing large gaps and few paragraphs per viewport. | A reader typography policy with bounded paragraph spacing and configurable text size. Preserve intentional breaks and publisher structure; avoid removing user-authored whitespace. Validate headings, lists and images alongside paragraphs. |
| V13 | P2 | `*-audio-player`: the filename is repeated in the app bar and a large heading, with awkward mid-word extension wrapping at large text. Landscape puts audio transport controls below the initial viewport. In landscape video, the picture is reduced to a thin strip above the transport panel. | Shared media chrome; one title, compact artwork/metadata and a grouped transport/scrubber panel. Landscape video should dedicate more height to the picture and use accessible overlay controls. Protected video remains protected. |
| V14 | P2 | `*-note-reading`, `*-note-keyboard`, `*-note-bold-selected`: normal paragraphs fit reasonably and bold has a visible tonal selection. The persistent Add paragraph row and toolbar labels compete with content on shorter screens. | Keep the verified bold selection behavior. Use a compact editing action strip, insertion menu and contextual block controls; put longer tool names in accessible descriptions. Do not treat the remaining blank page below a short note as a padding defect or add filler. Table and drawing defects are listed separately below. |
| V15 | P2 | Pilot initial `portrait-*-library-top` briefly showed Empty note for a populated note; later frames showed its snippet. This transient was not visible in the final settled initial capture. Source confirms one asynchronous snippet query per visible note card. | Include the bounded snippet in the paged metadata query or represent loading separately. Show Empty note only after the data is known to be empty. This also removes avoidable per-card reads and visual layout churn. |
| V16 | P1 | `large-28-drawing-editor`, `large-30-drawing-properties`, `landscape-30-drawing-properties`: Undo/Redo and property labels wrap or disappear; opening properties consumes the entire canvas area. | A bounded primary icon strip, one undo/redo pair and a separate properties sheet. Preserve a usable canvas at every size; make the properties content scroll rather than expanding above it. Use visible selected fills/outlines for pen/eraser/navigation and color. |
| V17 | P1 | `*-drawing-stroke`: a single short line displays Stroke limit reached. Source `NoteDrawing.kt` uses the same else branch for a full stroke and for a point rejected because it is too close to its predecessor. | Separate capacity rejection from distance filtering. Ignore nearby samples without a warning, report capacity only at the actual limit, and clear stale notices on a new gesture. Verify tap, slow/jittery stroke, ordinary release and genuine capacity boundaries. |
| V18 | P2 | `*-table`, `*-table-wide`: fixed 150 dp fields cut the second column at normal portrait width; floating coordinate labels make the table look like a stack of forms. Adding a column produces no visible change until the user scrolls horizontally. | Responsive column widths for small tables, a clear grid with aligned row heights, accessible cell coordinates without permanent floating labels, and a visible horizontal-overflow cue. Bring a newly added column into view. Keep existing encrypted table snapshots/autosave. |
| V19 | P1 | `*-lock-screen`, `*-recovery-form`, `*-recovery-keyboard`: decorative/header content pushes essential fields below the fold; keyboard-open recovery views can show neither the focused password input nor a usable action. Large text and landscape amplify this. | Compact returning-user/recovery headers, fields before optional explanation, focused-field bring-into-view and reachable IME/submission actions. Keep the full recovery instructions and key available by scrolling, without truncation. |
| V20 | P2 | `large-46-unsupported-document`, `landscape-46-unsupported-document`: filename and explanation overflow a non-scrolling preview-unavailable column. At large text, even the extension is split onto its own line. | A compact title/type summary and scrollable explanation with shared reader margins. Bound filename wrapping and preserve its extension through a type label. |

The pilot's light status-bar icons are excluded as an application finding: MainActivity explicitly selects dark system-bar icons, while the original test host did not. The corrected host matches those settings. Its keyboard resize mode also now matches MainActivity. Neither host mismatch is evidence of a production defect.

## Delivery sequence

1. **Containment and reachability:** V02/V03/V05/V08/V09/V10. Fix keyboard obstructions, path collapse, covered actions, dialog scrolling, native reader overdraw, drawing canvas loss and the false stroke-limit warning first (V16/V17/V19).
2. **Collection structure:** V01/V04/V06/V15. Replace the stacked header and tall generic cards with a compact collection shell, unified folder/file list, media-specific grids and stable snippet metadata.
3. **Settings and action sheets:** V07 plus consistent dialog chrome. Use short summaries, shared insets, clear selected states and scrolling that respects keyboard/system bars.
4. **Editor and readers:** V11–V14 and V18/V20, alongside the drawing workspace fixes. Group tools around the active task and protect the usable canvas/reading area.
5. **Repeat the same recordings:** compare matching states before/after. Passing compilation or functional tests alone cannot close these findings.

## Proposed UI structure

Keep the selected [Paper direction](paper-reference.png): warm neutral surfaces, terracotta selection and serif document headings. Its compact pinned rows and clearer content hierarchy are more useful references than copying decorative empty space. The current implementation overuses generic cards and stacks additional controls above the reference layout.

- `CollectionScaffold`: one app bar, search mode, responsive navigation and one collection viewport. `VaultItemRow` handles notes/documents/audio; `MediaGridCard` handles visual media. `FolderBreadcrumb` owns bounded path display. File type filtering remains available without requiring another permanent multi-row header.
- `SettingsScreen`: a proper destination with grouped rows, short summaries and independent password form scrolling. It can share the main secure window instead of maintaining another full-screen dialog window.
- `ReaderScaffold`: shared bounds/insets for PDF, EPUB, images and text, with a stable control layer and explicitly clipped native content viewport. Preserve the existing native parsers, encrypted streams and service isolation.
- `DrawingWorkspace`: a primary tool strip and a separate properties sheet. Share a tool-button component with clear selected/mixed/disabled semantics and a visible selected fill or outline. Remove duplicated actions rather than shrinking their hit targets.

Use the existing Compose/Material 3 and native reader components. This redesign does not require a new rendering framework, a database migration or changes to the vault's encryption. Project bounded note snippets with paged metadata, keep stable item keys and reuse existing bounded media previews. Measure frame timing and allocations on representative hardware after the layout work; the current benchmark compilation is not a performance result.

## Visual acceptance checks

- On the captured compact portrait device at normal text, a populated collection shows at least two compact rows without first scrolling its header away. In landscape, at least one complete item is visible initially. At large text, prioritize a complete reachable item and readable controls rather than forcing the normal-text density.
- Folder paths never render a character per line. Current-folder identity, parent navigation and creation remain reachable with long names.
- No creation action covers a row/menu hit area or search input. No reader content draws into an app bar, system bar or transport control layer.
- Open the keyboard in search, every password field and note/table editing. The focused input remains visible; submission/insertion actions can be reached through a documented scroll or IME action.
- Every Add/import action is visible or reachable by scrolling in portrait, landscape and actual Android 2× text settings.
- Check the selected state of text tools, drawing tools/colors, filters and view modes visually. Disabled state and mixed formatting must remain distinguishable.
- Check first and last items, empty results, long titles, selected items, open menus and resumed reader positions. Preserve the native secure reader paths and the original encrypted content.
- Review before/after images side by side and inspect the actual recordings. Store capture failures explicitly; do not count failed or black protected-content frames as proof of a visually correct screen.

## Scope limits

The recordings use an emulator and test activity hosting the production UI with synthetic encrypted files. They are not recordings from the user's phone and do not include private content. The creation/onboarding flow, attachment picker, import progress/resume states, actual system document-provider permission journeys, exhaustive accessibility traversal, RTL, foldables and physical-device rendering/performance are not fully covered by this set. Protected video imagery is intentionally excluded by its secure surface. These are explicit coverage limits, not passed checks.
