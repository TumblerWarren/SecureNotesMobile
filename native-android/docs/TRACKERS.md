# Trackers

Open **Trackers → New category**, name the category, then choose its format:

- **Reading** starts with a thumbnail, required title, chapter count, and status.
- **Checklist** starts with a required title and a Done checkbox for each entry.
- **Custom** starts with the required title. Add text, count, status, checkbox, or thumbnail fields.

Use **+** inside a category to add an entry. Checkboxes can also be toggled directly in the entry list. Search matches titles and visible field values. Entry menus offer editing and confirmed deletion.

The category menu’s **Edit format** action can rename the category, add or remove fields, reorder fields, and edit status choices. Field IDs remain stable when renamed or reordered. Removed fields are hidden without erasing their saved values. A saved field’s type stays fixed; add a new field to use another type. Existing statuses remain readable after their choice is removed. Titles are required; counts must be nonnegative whole numbers. Formats support up to 20 custom fields.

Thumbnail images are imported through Android’s image picker into normal encrypted vault storage. Removing a thumbnail or deleting/cancelling an entry does not delete the imported image from the vault. Deleting that image separately leaves a placeholder in the tracker.

The feature follows the existing [Paper design](design/PAPER.md): shared portrait/landscape navigation, ivory surfaces, serif headings, terracotta actions, local vector icons, and secure editing sheets. Draft content stays in memory for the unlocked session; it is not saved in Android instance state. Category and entry changes require explicit Save. Cancelling category creation does not create a category.

Categories, formats, and values live in the SQLCipher index. Schema migration 4→5 adds tracker tables without replacing existing notes or folders. Whole-vault encrypted backups include trackers and imported images. Category deletion removes its entries after confirmation, while keeping vault images.

## Validation

Run `./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug` from `native-android` with the documented Android toolchain. Host tests cover format and value validation, templates, stable identities, and JSON round trips. `TrackerPersistenceTest` covers migration, validation, retained values, lock/unlock, and encrypted backup/restore. `TrackerUiTest` covers category/entry creation and cancellation, checklist toggles, custom fields, and format changes. Device tests require a built native library and an Android device or emulator; compilation alone does not execute them.
