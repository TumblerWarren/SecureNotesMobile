# Moving to the new package

The production package is `app.securenote.vault`. Android treats it as a separate app from both `com.anonymous.SecureNotesMobile` and `com.anonymous.SecureNotesMobile.vault`; it cannot automatically read either app's private storage.

To move a vault from an earlier native release, create an encrypted backup in that app and store it outside the app. Install the new signed APK, then restore the backup into its empty vault using the password valid when the backup was created or the recovery key. Verify the restored content before removing an earlier app or its data. Keep an independent backup.

The original Expo app has a different storage format. Consult the legacy importer documentation before transferring its data.
