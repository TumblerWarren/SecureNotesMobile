# Internal EPUB reader

EPUB reading is implemented in source alongside the Paper UI. It has not been compiled or run: builds, device tests and CI remain paused at the user's request.

## Reading experience

Import an `.epub` through Add → Import files and open its card in the vault. EPUB MIME types and `.epub` filenames route to the internal reader, including existing files whose provider supplied a generic MIME type. The card uses a book icon and an EPUB label.

The native serif reader supports DRM-free, reflowable EPUB 2/3 publications, chapter contents from EPUB 3 navigation or EPUB 2 NCX, previous/next section navigation, basic text formatting, inline raster images and links to spine chapters. Internal anchors select the section containing the anchor. Chapters are divided into bounded scrolling sections to keep rendering and memory use predictable. Reader font size ranges from 16 to 28 sp. Chapter, section, scroll offset and font size are saved in the encrypted index and restored when reopening the book. A sudden process kill can lose a pending save; the existing save queue drains on normal lock when possible.

## Encryption and process boundary

1. The original book is an ordinary encrypted vault object, using the existing authenticated streaming format and per-file key.
2. The host opens the existing revocable, uncached native proxy descriptor. Only this descriptor is sent to a non-exported Android isolated service; no password, master key or file key crosses that boundary.
3. `EpubArchive` indexes the ZIP32 central directory and reads requested resources at their offsets. The reader never extracts the archive, creates a ZIP filesystem or writes plaintext book/resource files.
4. The isolated service parses publication metadata and one chapter at a time, strips active content and external URLs, divides the chapter into sections and decodes only the selected section's images. Only bounded sanitized text and sampled bitmaps return to the host.
5. An Android `TextView` renders the section. It has no WebView, external navigation, copy/selection action mode, autofill, content capture or saved text state. Font-only changes reuse the loaded section; ordinary recomposition avoids reparsing unchanged text.
6. Each request and reply checks the original vault session, including cached chapter output. Lock clears displayed content, closes the reader connection and revokes further descriptor reads. Reading position is kept in SQLCipher's existing file metadata body field, requiring no schema migration.

Screenshot and overlay protections remain inherited from the vault. Decrypted text, decoded images and native layout/graphics buffers necessarily exist in RAM while reading; disposal is best effort, as described in the [security contract](SECURITY.md).

## Compatibility and resource limits

This is a restricted reading subset, not a claim of full EPUB reading-system conformance. The reference formats are [W3C EPUB 3.3](https://www.w3.org/TR/epub-33/) and [EPUB Reading Systems 3.3](https://www.w3.org/TR/epub-rs-33/).

- DRM-protected content, fixed-layout/pre-paginated books, ZIP64 and split ZIP archives are unsupported. Recognized IDPF/Adobe font obfuscation is tolerated only for font resources; those fonts are not loaded.
- Publisher CSS, embedded fonts, JavaScript, forms, embedded audio/video and remote resources are not loaded. Tables become plain rows; SVG/MathML content becomes text or an illustration placeholder. Unsupported raster images use alternative text where available. Publisher layout, advanced typography and ordered-list formatting may differ.
- Only links within the publication's spine can navigate. External links are inert. The contents dialog lists spine chapters, using the first available navigation label for each chapter.
- ZIP resource names, local headers, sizes and CRCs are checked. Duplicate names, unsafe paths, encrypted ZIP entries, traversal outside the ZIP root, malformed data and excessive compression ratios fail closed. There is no filesystem extraction destination.
- Limits: 20,000 ZIP entries, 16 MiB central directory, 10,000 manifest resources, 1,024 spine chapters, 2 MiB per chapter/package/navigation document, 50,000 elements/100,000 nodes and 128 nesting levels. The container and encryption descriptors are limited to 256 KiB.
- Each chapter has at most 2,048 sections. Target sections contain about 12,000 text characters, at most 48,000 HTML characters and four images; a large block may be simplified and split.
- Each image resource is limited to 12 MiB of decompressed resource bytes and 64 million source pixels. Decoding samples it to at most 1,200 × 1,600 pixels. A section's decoded image budget is 12 MiB. ZIP decompression also limits individual output and expansion ratio. Over-limit books produce an error while their encrypted originals remain stored.

## Validation status

Added unexecuted source tests cover stored/deflated random ZIP reads, Unicode, traversal and external URL rejection, truncation, CRC corruption, duplicate names, compression bombs, encrypted-object reads and session revocation. Android test source covers a synthetic EPUB's contents, image pixels, script/external-link removal, internal navigation, encrypted progress persistence, reopening and cached-navigation rejection after revocation. A native rendering test checks that links stay internal, URL spans are removed and text actions/saved text are disabled. The integration source also checks that no EPUB/HTML/image extraction files remain in the test vault.

Only source formatting/parsing, XML well-formedness and static review are permitted while the build pause remains active. When authorized, compile and execute the new JVM/Android tests, add representative public-domain EPUB 2/3 fixtures, and verify on-device scrolling, native links, large-font layout, section transitions, position restoration and lock/unbind behavior. Malformed-book limits and descriptor revocation also need execution on supported Android versions before release.
