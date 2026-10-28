# App-wide usability validation and delivery plan

27 October 2026. This report is the primary issue register for the redesign. Read alongside [the design and encrypted-folder specification](USABILITY-AND-FOLDERS-PLAN.md).

## Scope and evidence

Reviewed all application UI sources: MainActivity screens/dialogs, Settings, PaperLibrary, NoteEditor, NoteDrawing, EpubViewer, TiledImageView, PaperTheme and native player XML. Also traced selection, saving, password change, import and navigation through NoteEditingSession and VaultModel, and checked the existing test coverage. Application baseline is the tree validated in GitHub commit `d0879350b0973a7de76dd8bb6ac307e8edce765e`.

Evidence labels:

- **C — confirmed in code:** construction/state behavior is directly visible. This does not mean a screenshot was captured.
- **M — measured:** calculated from actual color or dimension constants.
- **R — rendering risk:** requires a running-device check; do not report it as a reproduced clipping defect.
- **G — missing capability:** the workflow/control/storage support is absent.

Priorities: P1 affects task completion, state clarity or essential accessibility; P2 affects layout, navigation and routine efficiency; P3 is optional polish. No claim that the app is visually validated follows from a green build. No app-code changes, new builds or emulator sessions were performed in this planning pass.

## Findings and proposed fixes

### Shared shell and design system

| ID | Priority / evidence | Finding and source | Planned correction |
| --- | --- | --- | --- |
| A01 | P1 C | `VaultApp` renders every nonempty `message` with `errorContainer`. `changePassword` and `importLegacy` also write success messages to it. Success therefore uses error treatment. | Typed success/info/error events; appropriately styled transient feedback and accessible announcements. |
| A02 | P1 C | `runWork` maps unrelated failures to one password/recovery/permissions/storage message. The user cannot tell what action to take. | Operation-specific errors with a relevant retry or recovery action; keep sensitive/internal details out of UI. |
| A03 | P2 C | App bars, content, dialogs and native controls use unrelated 8/12/16/24/32 dp rules. | Shared tokens and one margin/inset owner per screen; narrow-screen and keyboard variants. |
| A04 | P1 M | Muted `#736E65` on Background `#F6F1E7` calculates to **4.498055:1**; Accent on AccentSoft calculates to **4.148338:1**. Both fall below the proposed 4.5:1 normal-text acceptance threshold. The first must not be rounded to a pass. | Darken normal-text tokens sufficiently; verify every actual foreground/background pairing, including selected states. This is a token calculation, not a blanket claim about all rendered controls. |
| A05 | P2 R | Back text, item title, Lock text and menu share a compact item bar; long filenames/large fonts compete for space. | Icon navigation, useful title width, dedicated reachable Lock action; layout bounds tests at narrow widths and large fonts. |
| A06 | P1 C | Selection in library filters/navigation is conveyed through colors/lines; custom controls do not declare selected/toggle state. | Proper selectable/tab semantics, state descriptions and non-color selection cues. |
| A07 | P2 R | Root safe-area and IME modifiers plus dialog/native view layouts need joint measurement. Modifier nesting alone does not prove duplicated insets. | Check measured bounds above keyboard/system bars and foldable cutouts; assign explicit inset ownership. |

### Settings and entry/recovery

| ID | Priority / evidence | Finding and source | Planned correction |
| --- | --- | --- | --- |
| A08 | P2 C | `SettingsDialog` uses a 16 dp gap for paragraphs, Import and Change password alike. Buttons add their own padding; alert width reduces horizontal room. | Full-screen grouped settings rows, common leading alignment, no extra gap between adjacent rows. |
| A09 | P1 C | Save password clears inputs and dismisses Settings immediately after starting asynchronous work; validation failures arrive elsewhere. | Dedicated password form, pending state, inline failure, dismiss only after success. |
| A10 | P2 C | Settings's explanatory paragraphs dominate the view; the LegacyPasswordDialog has the opposite problem, no explicit paragraph-to-field gap. | Concise settings summaries, detail pages for explanations, consistent dialog field spacing. |
| A11 | P2 C/G | Lock/create/recovery use a large decorative stack; recovery-key input lacks `singleLine`/next-action behavior, and disabled submission offers little inline explanation. | Compact returning-user screen, field-level validation, explicit IME actions; retain understandable recovery instructions. Verify large-font recovery key wrapping. |

### Library and organization

| ID | Priority / evidence | Finding and source | Planned correction |
| --- | --- | --- | --- |
| A12 | P2 C | The library heading/count/search/filters stack precedes content; outer padding is 24 dp. | Compact populated-screen heading, 16 dp outer margin and smaller filter/header separation. |
| A13 | P2 C/M | Every card has a 188 dp minimum; grid minimum width is 145 dp. At 320 dp with 24 dp side margins and 12 dp gutters, two columns cannot fit. | Notes/document/audio rows by default; media-specific grids, optional notes grid and explicit responsive breakpoints. One column itself is not a defect; oversized empty cards are the concern. |
| A14 | P2 C | Only the first two pinned items get the Pinned section; further pinned items appear under Recent despite pinned-first sorting. | Query/render pinned grouping consistently; a compact Pinned group with See all, without duplicate entries. |
| A15 | P2 R | Library has a fixed 96 dp bottom pad while the navigation row is separate and the FAB overlays content. | Derive trailing clearance from actual obstruction; verify both last-item access and empty tail space. |
| A16 | P1 G | No folder entity, tree navigation, destination picker, multi-select move or folder import exists. Media is one combined category. | Deliver real encrypted folders and separate image/video/audio/PDF/EPUB views, including bulk organization and external folder-tree import. |
| A17 | P2 G | There is no user sort/view selector or folder-aware search scope. | Sort/view controls, explicit scope, paths in global results and retained position per scope. |
| A18 | P2 R | A whole card is clickable and contains an options button; search errors and empty states share the same layout constraints. | Verify separate accessible actions/focus order, long-title wrapping and distinct loading/empty/error/retry presentations. |

### Notes and canvas

| ID | Priority / evidence | Finding and source | Planned correction |
| --- | --- | --- | --- |
| A19 | P2 C | Every block includes a separate 48 dp menu row plus 16 dp inter-block spacing. Short text also has a two-line minimum. | Content-sized paragraphs and contextual block handle; remove the extra row without changing authored whitespace. |
| A20 | P1 C | `PaperEditorTool` has no selected/mixed state. `NoteEditText` has no selection-change state callback for the toolbar. | Derive selection state from spans, paragraph state and insertion styles; update on movement/edit/focus; use semantic toggles. |
| A21 | P1 C | Toolbar icons/text hardcode enabled-looking colors even when the button is disabled. | Centralize enabled/disabled/selected/mixed visual and spoken states. |
| A22 | P2 C/M | Eight tools each request at least 54 dp, plus 24 dp bar padding: **456 dp** before any additional layout effects. The whole set cannot be visible on common compact phones. | Keep a small formatting group and Insert/More sheet; visible overflow affordance and full-sized targets. |
| A23 | P1 C | Autosave has no Saving/Saved/Failed indicator. Queue failure says “will be retried,” but that failure branch does not itself schedule a retry. | Explicit save state and retry semantics; retain unsaved drafts, show actual retry outcome and test lock/disposal behavior. Do not imply persistence from a timer alone. |
| A24 | P2 C/R | Table cells are independent 150 dp outlined fields with repeated coordinate labels, inside another padded surface. Long paragraphs have a 24-line cap. | Compact table grid and contextual row/column actions; inspect gesture/focus behavior and long-paragraph nested scrolling before changing caps. |
| A25 | P2 C | Attachments use the generic text “Open encrypted attachment”; name/type/size are not shown. | Compact metadata row with stable attachment ID, file identity and clear open action. |
| A26 | P1 C | Canvas keeps `erasing`, `navigating` and `tool` separately. Navigate can become true while Eraser still reads “on”; selecting a color does not leave Navigate. | One mutually exclusive tool-mode state; color is a property, not another active mode. Tests cover every transition. |
| A27 | P1 R | Canvas stacks two scrolling tool rows, two always-visible sliders, labels and help above a weighted drawing area. Short-height/large-font layouts can leave very little drawing room. | One primary tool row; properties in a sheet; wide/landscape side dock where useful. Check canvas bounds and reachability rather than simply hiding controls. |
| A28 | P2 C | Undo/redo and Clear are mixed into the color row; chosen modes use text checkmarks, while some state remains implicit. | Stable history controls, obvious selected tools/color/width, contextual properties and immediate Undo after Clear. Keep the existing undoable clear operation. |

### Readers and playback

| ID | Priority / evidence | Finding and source | Planned correction |
| --- | --- | --- | --- |
| A29 | P1 C | PDF adds 16 dp borders and fits a page bitmap; zoom scales that bitmap. Render width switches between fixed constants rather than actual viewport/zoom demand. | Continuous fit-width layout, dimension-aware renders and zoom tiles with bounded in-memory cache. |
| A30 | P1 C/G | PDF pans without clamping offsets; it lacks page jump, thumbnails, reading-state persistence and a retry action. | Clamp transforms, add page navigation/resume/thumbnail sheet and local failure recovery. |
| A31 | P2 C | EPUB repeats book title/author before the reading frame and adds its own command bar plus footer below the item bar. | A single compact reader hierarchy, optional metadata/details and reading-mode chrome. |
| A32 | P2 R | PDF/EPUB footers scroll horizontally to avoid overflow, but controls can be off-screen with no overflow cue. Plain-text footer is not scrollable or adaptive. | Responsive reader controls and labelled overflow, page jump and large-font variants; test all navigation actions. |
| A33 | P1 C | Plain text retains old page text while its next async read runs; there is no explicit loading/error-with-retry state. | Model loading/current/error states; make page identity unambiguous during transitions. |
| A34 | P1 C/G | Image view is blank until decoding completes; failure is drawn as a fixed-position Canvas string rather than an accessible error state. Gestures lack visible alternatives. | Loading/error/retry overlay with semantics and accessible Fit/Zoom controls; preserve tile budgets. |
| A35 | P2 C/R | Audio uses a generic 160 dp artwork block, separate 160 dp player and large spacers. Video permanently reserves a separate metadata section. | Integrated compact audio controls and collapsible/overlay video details; preserve intentional aspect-ratio letterboxing. |
| A36 | P1 C/R | Native seek bar XML requests 32 dp height, below the proposed 48 dp target. Fixed controller sizing and 12 sp time labels need large-font bounds checks. | Larger effective seek target and height that accommodates text/controls. Verify effective Media3 bounds rather than assuming XML alone describes the final touch region. |

## Things checked that should not be “fixed” indiscriminately

- Media rewind/forward labels say 10 seconds and the player explicitly configures both increments to 10,000 ms. No mismatch found.
- Library heading can scroll away; the problem is its initial density, not an inherently fixed header.
- Video/image letterboxing and PDF-authored white margins are content/aspect-ratio effects. Do not crop documents or media to make screenshots appear fuller.
- Long-form text can intentionally have a maximum reading width on tablets. Full-width prose is not always more usable.
- A 48 dp hit target is intentional. Remove redundant containers, not accessibility space.
- Canvas Clear already supports undo. The issue is discoverability and feedback, not absence of undo.
- Existing EPUB image resize bounds are covered by a focused native-view test. That does not cover complete reader layout.

## Measurement and research basis

Contrast calculations use the actual opaque sRGB constants in PaperTheme and the standard relative-luminance formula. Target normal text at least 4.5:1, with room above the threshold. The W3C guidance explicitly disallows rounding a subthreshold ratio up to a pass: [contrast minimum](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html). Treat this as a design acceptance target for this native app, not a claim that a web conformance audit was performed.

Use actual control roles and selected/toggle/error/progress semantics so visual behavior is also testable and accessible: [Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics). Existing platform references and reader/folder research are linked in the companion plan.

## Delivery plan and ownership of validation

### Phase 1 — coherent shell and feedback

Implement shared spacing/typography/color/state tokens, one screen-header pattern, typed operation feedback, inset ownership and the full-screen Settings/password flow. Resolve A01–A11. Keep Lock reachable and preserve existing session behavior. Review populated and failure states together; attractive empty states alone are insufficient.

### Phase 2 — notes/library/canvas

Resolve A12–A15 and A18–A28: content-sized layouts, notes list/grid choices, consistent pin grouping, editor selection adapter, save feedback, attachment identity and one canvas mode controller. Preserve the native Editable, draft retention and incremental encrypted drawing persistence. Profile before changing long-paragraph virtualization/line limits.

### Phase 3 — folders and folder encryption

Resolve A16–A17 using the full companion specification, including:

1. Schema 3→4 migration and nested folder repository with cycle/duplicate checks.
2. Create/rename/move/remove-folder flows, breadcrumbs and scoped search.
3. Single/bulk movement of already-encrypted media, without rewriting ciphertext.
4. External directory selection, preserved subfolders, explicit destination, streamed per-file encryption, encrypted job records, partial failure and unlock-to-resume.
5. Optional deliberate grouping by media type, with a preview and no silent rearrangement.

Folder names and membership remain encrypted metadata. Imported originals remain outside the vault. Whole-folder import is required delivery scope, not a future placeholder.

### Phase 4 — viewers and media

Resolve A29–A36. Keep the current isolated renderer behind a new PDF presentation/scheduler first. A separate pinned-library compatibility spike is required before replacing the engine. Unify reader loading, error, retry and progress behavior; use media overlays and compact audio controls.

### Phase 5 — acceptance evidence

For each phase, validation is part of its completion, not deferred until the user reports defects. Record an issue ID, fixture, before/after evidence, device/window/font configuration and result. New findings enter this register without requiring user reproduction instructions.

Required screen/state coverage:

| Surface | Required states |
| --- | --- |
| Create/unlock/recover | Empty, invalid, long input, keyboard visible, busy, failure, success, screen-off |
| Recovery recording | Small width, large font, long key wrapping, confirmation and continuation |
| Library | Empty, one item, many mixed items, at least three pinned, long names, filter/search/loading/error/retry, last item under FAB |
| Notes | Empty/short/long, multiple paragraph blocks, mixed-format selection, toolbar overflow, keyboard, tables, drawing, attachments, saving/failed save |
| Canvas | Every tool transition, color/properties, undo/redo/Clear, large drawing, zoom/pan, short landscape and large fonts |
| Settings | Default rows, password form, invalid current password, mismatch, pending, success/failure, old database import |
| Folders/import | Nested/empty, duplicate names, bulk selection, destination changed/deleted, unknown sizes, progress, cancel, partial failure, lock and resume |
| PDF | Mixed page sizes, long/scanned/tall/rotated pages, fit/zoom/pan/page jump, rapid switching, loading/error/retry, lock while rendering |
| EPUB/text | Long title, contents, larger font, embedded images, internal links, section/page changes, loading/failure, resizing |
| Images | Large/tall/rotated/unsupported, loading/failure, zoom/Fit, TalkBack controls |
| Audio/video | Long metadata, prepare/play/pause/seek/buffering/end/failure, short landscape, large time labels, lock while playing |
| Shared dialogs | Long content, small viewport, keyboard, action reachability, TalkBack focus and Back behavior |

Run representative cases at 320/360/412 dp widths, a wide/split window, portrait/landscape, font scales 1.0/1.3/2.0 and RTL. Prioritize pairwise combinations and known boundary cases rather than pretending every possible combination was tested.

Use synthetic vault fixtures. Capture semantics/layout bounds on the real secure screens; capture screenshot comparisons only in isolated test renderings that do not weaken production FLAG_SECURE or expose real notes. If a screenshot is unavailable, record that fact and use bounds/focus/interaction evidence; do not substitute a generated mockup for a running-app screenshot.

Acceptance requirements:

- No clipped primary action, overlapping hit area or inaccessible last item; keyboard must not cover the active caret or form submit action.
- Selected, mixed, disabled, loading, failed and saved states are explicit and accurately reflect state transitions.
- No normal text uses a subthreshold foreground/background pairing; measure the actual surface combination.
- Before/after evidence shows denser layouts without reducing tap targets or deleting intentional document whitespace.
- Folder migration/import tests verify hierarchy, atomic item publication, ciphertext preservation on moves and immediate session revocation.
- Build/lint/core/device checks pass in GitHub Actions. Add focused state/bounds tests for the findings, not only broad happy-path tests.
- Measure typing, folder/library scroll and PDF scroll/zoom on a representative physical device. Record frame timing and memory; do not infer performance from emulator functional tests.

## Current validation result

Completed: app-wide source/state review, arithmetic checks for tool capacity and grid breakpoints, palette contrast measurements, existing-test coverage review and the prioritized plan above.

Still required: new rendered-device inspection and before/after evidence, TalkBack traversal, physical-device performance measurements and implementation-specific regression tests. The previous green GitHub run is the baseline, not proof these new acceptance requirements pass. No finding in this report has been marked fixed by writing the plan.
