# Other-feature bug, layout and performance review

Historical report: the build pause described below was subsequently lifted. [GitHub Actions run 40554407456](https://github.com/TumblerWarren/SecureNotesMobile/actions/runs/40554407456) passed compilation, lint, core checks and all 19 Android emulator tests for the later baseline. Current usability findings and outstanding visual validation are tracked in [App-wide validation](design/APP-WIDE-VALIDATION.md); passing those functional tests does not resolve the new layout findings.

Source review date: 2026-10-27. Scope: library/navigation, PDF, EPUB, images, audio/video, plain text, unlock/recovery and settings. This is a source review with fixes; it is not a visual inspection of a running app. The user's pause on builds, tests, emulators and CI remains active.

## Findings and fixes

| Area | Source finding | Implemented correction |
|---|---|---|
| Library | Its initial filter effect reset the shared grid scroll when returning from an item. | Reset only when the category/query actually changes during the library's lifetime. Returning with the same filter retains the supplied grid state. |
| Item menu | Item metadata was fetched once, leaving the pin/unpin label stale after pinning. | Observe the small metadata projection with duplicate suppression. File keys/bodies are not fetched for the app bar. |
| PDF authority | Native parsing can retain cached content; the client relied on descriptor revocation and UI disposal without checking session authority for every render/reply. | Capture the original store version, track the client in its session, check authority before requests and before delivering replies/results, and reject cached rendering after revocation. |
| PDF lifecycle | Service bind/close races and late render responses could leave work pending or retain undelivered bitmaps. | Synchronize bind registration and close, handle dead/null bindings, bound connection waits, cancel superseded deferred requests, validate returned page bounds/budget, and dispose undelivered results on cancellation. |
| PDF service | Already queued requests could run after unbind; page errors were not correlated to requests. | Mark a binding closed immediately on unbind, reject queued work/close incoming descriptors, and echo request IDs on errors. |
| PDF UI | A new page could display the old image while loading; a failed render could leave that old image under its error text. Opening also caught cancellation as a normal error. | Clear the page image/start a fresh error state before rendering; preserve coroutine cancellation; clear the UI reference immediately on lock. |
| PDF/EPUB footer | Previous/next/page/zoom controls could exceed narrow or large-font layouts. | Allow horizontal scrolling rather than forcing all controls into one constrained row. This needs device accessibility and touch-target verification. |
| EPUB images | Native image spans used display width, which can exceed the actual reader width in split windows. Cached text did not rerender on width changes. | Fit images to the measured native view and rebuild spans on width changes, preserving internal-link routing. Clear the cached link callback on disposal. |
| Audio layout | Fixed cover/control heights plus weighted spacers could overflow short/landscape windows. | Use a scrollable audio layout with ordinary spacing so the controls remain reachable. Long video titles are bounded to two lines. |
| Media feedback | Player errors were not presented in the Paper UI. | Surface a bounded generic playback error and clear the playback activity flag when playback fails. Codec support still depends on the device. |
| Image work queue | Rapid viewport changes could enqueue many obsolete tile decodes. | Limit outstanding requests to eight; skip work for an obsolete viewport and schedule further visible work as capacity returns. |
| Image cache pressure | A very large viewport could need more decoded visible tiles than the shared cache can retain, causing churn. | Increase sampling until the visible tile estimate fits a 24-tile budget within the existing 32 MiB cache target. This trades resolution for bounded working-set size on large windows. |
| Image failure path | Retrying a permanently undecodable tile after every completion could cause an invalidation loop. | Remember failed tile keys for the view's lifetime instead of repeatedly decoding them. Cancellation is still propagated. |
| Image fallback | Formats without a region decoder could go blank after their fallback bitmap was evicted from the shared cache. | Keep one bounded fallback reference for the active view and release it on close. The sampled fallback remains at most 2048×2048 pixels; it can outlive cache eviction while that view is active. Failed initialization now releases native resources. |
| Settings/passwords | Password fields did not all request password keyboard behavior; operations remained enabled during existing work. | Apply password keyboard options and disable conflicting change/import actions and inputs while busy. |

Reviewed without further changes: existing encrypted UTF-8 page reads, immediate pause/screen-off locking, native media-source reads, offline recovery validation, import source-retention messaging, EPUB external-link restrictions, and the encrypted preview/cache design. This does not establish that those paths are defect-free.

## Performance and security limits

PDF/EPUB bitmap rendering still necessarily uses native/graphics memory. UI reference disposal and session checks do not erase every historical decoder/GPU buffer. The existing security contract and absence of plaintext extraction/export remain unchanged.

Image decoding is single-worker per view, with at most eight queued/in-flight tile requests. The shared decoded cache target remains 32 MiB, but displayed Compose images, an active fallback bitmap, codec/native allocations and graphics copies can exist outside its accounting. No claim of a 32 MiB total process limit is made. A native decode already in progress is not guaranteed to stop instantly when panning or cancelling a coroutine.

The audio layout intentionally scrolls in constrained windows; this must be checked with TalkBack and large fonts. Horizontally scrollable reader footers preserve control reachability but require real-device navigation checks. Header text, recovery dialogs, native Media3 control bars and all other layouts still need visual inspection on small/landscape/split-window screens. No measured frame-time, decode-throughput or battery improvement is claimed.

## Verification status

Kotlin source formatting/parser and whitespace checks were performed. XML resources are checked for well-formedness only. The existing native integration test source now includes cached PDF rendering rejection after store revocation. `ReaderLayoutTest` adds native EPUB image-span bounds checks before and after resizing. These tests have not been executed.

When builds resume: compile all source/test APKs and lint; execute notes/schema migration and existing native/UI/security suites; test rapid PDF page/zoom switching and cancellation; return-to-library scroll and pin/unpin state; malformed images and repeated panning/cache trimming; long/unsupported media; keyboard/recovery/settings flows; EPUB resize/internal links/font changes; and background/screen-off lock during each viewer. Run visual checks at 320 dp width, landscape, split window and 1.0/1.5/2.0 font scales. Measure allocations, frame work, tile queue counts and playback/seek latency with synthetic data on a physical 4 GB device.
