# SecureNote 2.0.1 — build 88

[Download signed APK](https://github.com/TumblerWarren/SecureNotesMobile/releases/download/android-build-88/SecureNote-2.0.1-build.88.apk) · [Release and checksum](https://github.com/TumblerWarren/SecureNotesMobile/releases/tag/android-build-88) · [Build checks](https://github.com/TumblerWarren/SecureNotesMobile/actions/runs/40918777385)

## Vault transfers

- Export one file using Android's Save dialog, or select a destination folder for multiple files. Original filenames are preserved; duplicate names receive numbered suffixes. ZIP remains optional.
- Exported notes use a named folder with structured note JSON and attachments. Exported copies are readable outside the vault.
- Create a manual encrypted vault backup using a local or cloud document provider. Restore it to an empty vault using the backup-time password or recovery key and a new password.
- After a verified import, optionally request deletion of selected original files. Providers may refuse deletion, and deletion does not guarantee secure erasure or removal of cloud copies.

The release includes encrypted notes, folders and media with in-app viewers and playback. See [vault transfers](VAULT-TRANSFERS.md) for details and limitations.

## Installation and verification

This signed release uses package `com.anonymous.SecureNotesMobile.vault` and installs beside the original app. Its storage is separate and original data is not automatically transferred. Keep the original app and data until migration is verified.

Build 88 passed GitHub Actions verification, emulator instrumentation and visual checks, signed APK verification, and a side-by-side installation check. Independent security review and physical-device performance measurements remain outstanding. Keep an independently stored encrypted backup; uninstalling or clearing app data removes the local vault.

APK SHA-256: `84eeb18a3a2ef97c9275b45d6d21bebd22f9c5d40377d0c30a69259f918d5b14`.
