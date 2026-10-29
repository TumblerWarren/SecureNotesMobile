# Release signing

Production releases are built only from master after verify and visual-audit pass. The signed-release job requires these repository Actions secrets:

- ANDROID_SIGNING_KEYSTORE_BASE64: base64 of the private PKCS12 keystore.
- ANDROID_SIGNING_STORE_PASSWORD
- ANDROID_SIGNING_KEY_ALIAS
- ANDROID_SIGNING_KEY_PASSWORD

Production application ID: `app.securenote.vault`. The original app retains `com.anonymous.SecureNotesMobile`; preview and benchmark IDs use the new package prefix. This separation prevents Android from treating a new-key vault APK as an incompatible update to the original app. Each app has separate private storage. Existing data remains in its original app, including builds with the former .vault package, and is not automatically transferred. Restore an encrypted backup to migrate.

The owner has authorized a new RSA-4096 key. Its private backup is provided separately, outside this repository. Keep that backup offline; future APK updates require the same signing key. A new key cannot update an app installed with a different certificate. Do not uninstall a vault containing data to work around a signature mismatch.

The key is decoded only into RUNNER_TEMP and removed after the job. Gradle reads credentials from environment providers; no passwords or private keys belong in git. Release APKs must pass apksigner verify before upload or GitHub Release publication. Missing secrets stop the job. Pull requests never receive the signing secrets or publish releases. CI can compile unsigned release outputs for lint/minification checks, but those files are excluded from downloadable artifacts.

Before publication, CI installs a disposable, differently signed legacy-package fixture, creates a sentinel file in its private storage, installs and launches the signed `.vault` APK, and checks that both packages and the original sentinel remain. This runs only on a fresh emulator and never touches a real legacy vault.

Debug preview APKs use Android debug signing and the separate .nativepreview package. They are test artifacts, not production releases. Installable signed production APKs are uploaded as signed-android-release and attached to the GitHub Release after master builds pass.
