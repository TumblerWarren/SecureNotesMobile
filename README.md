# SecureNote Vault

SecureNote is a native Android vault for encrypted notes and files. Supported images, PDF and EPUB documents, music and video can be opened inside the app.

[Download the latest signed APK](https://github.com/TumblerWarren/SecureNotesMobile/releases/latest) · [Build 93 release notes](native-android/docs/RELEASE-NOTES.md) · [Native Android documentation](native-android/README.md)

The vault stores its encrypted database and encrypted files in app-private storage. You can make a manual encrypted backup to a local or cloud document provider, and export selected files as readable copies. Keep a separate backup and the backup-time password or recovery key. See [vault transfers](native-android/docs/VAULT-TRANSFERS.md) and [security boundaries](native-android/docs/SECURITY.md).

The new `app.securenote.vault` package installs separately from earlier app packages and has separate storage; existing vault data is not automatically transferred. Restore an encrypted backup to migrate. Keep the original app and data until migration is verified. The original Expo source and its [README](native-android/docs/LEGACY-README.md) remain for reference.

Emulator checks have run, but independent security review and physical-device performance measurements remain outstanding.
