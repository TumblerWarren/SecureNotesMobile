# SecureNote Vault

The Android application is being replaced with a native Kotlin vault in [`native-android/`](native-android/). It stores notes and arbitrary files locally with password-based encryption, and reads supported documents and media inside the app using Android components.

The rewrite includes encrypted search, rich-text notes, tables, drawings, attachments, internal image/PDF/text viewers, music/video playback, offline recovery, and a read-only importer for legacy encrypted note databases. See the [native build instructions](native-android/README.md), [security boundaries](native-android/docs/SECURITY.md), and [performance acceptance targets](native-android/docs/PERFORMANCE.md).

The debug build installs alongside the existing application. The source is under validation; device tests, physical-device performance measurements and independent security review are release gates. Keep another copy of important data during validation.

The original Expo application remains in the root directory for migration and reference. Its [original README](native-android/docs/LEGACY-README.md) is preserved. Production upgrades require the original signing key; no production deployment has been performed.
