# Paper UI

Approved visual direction: [Paper concept board](paper-reference.png). The board is a generated design reference, not a screenshot of the running app.

Paper uses ivory (#F6F1E7), warm white surfaces, ink (#272B28), terracotta (#A9513F) and subtle neutral dividers. System serif headings and writing text pair with system sans-serif controls. All icons are local vector paths; no remote fonts, decorative blur or new rendering libraries are required.

The implementation includes:

- A paged adaptive library grid with pinned rows, encrypted note snippets, file-type filters and an Add menu. Category filtering and search run in the encrypted index; previews load only for visible cards. Scroll position survives opening an item within the same session.
- Vault, Notes, Media and Settings navigation; filters for All, Notes, Documents and Media. The collection has loading, empty, error/retry and delete-confirmation states.
- An editorial note title, native serif writing canvas, compact block menus and a bottom formatting toolbar. Tables, drawings, links and encrypted attachments retain the encrypted block storage. The [notes-editor follow-up](../NOTES.md) adds session drafts, selection-safe formatting, improved drawing history and safer autosaves.
- Paper frames and controls for PDF/text readers, adaptive image viewing and a styled native Media3 playback controller. Backgrounding still revokes access and stops playback.
- An internal EPUB reader with the same serif reading surface, terracotta links, chapter contents, font controls and section navigation. Reading progress is kept in the encrypted index; see [supported EPUB features](../EPUB.md).
- Matching password, recovery, settings, status bars and launcher artwork. Dialogs retain the editing restrictions; screenshot protection and native descriptor revocation remain enabled.

The native crypto, file format, SQLCipher schema and encrypted-object format are unchanged. New DAO queries add category selection and bounded note snippets without schema migration.

## Validation status

The user requested that builds remain paused. No Gradle build, emulator/device test, benchmark run, CI push or deployment has been performed for this UI change. Only source formatting/parsing, XML well-formedness and source review have been checked. The existing round-trip test and profile journey have been updated for the Add menu and type filters; the UI test also covers the styled native audio controller and background lock. These updated tests have not been executed.

When builds are authorized, compile the debug/test APKs, execute the Android suite, inspect the UI on small screens and with larger fonts, and run release/lint/benchmark compilation. Review the real library, note and PDF screens against the concept board; the generated board is not evidence of runtime appearance.
