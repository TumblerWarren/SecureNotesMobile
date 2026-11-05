package app.securenote.vault

import androidx.room3.Room
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackerPersistenceTest {
    @Test
    fun versionFourMigrationPreservesNotesAndFoldersAndCascadesTrackerDeletion() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "tracker-migration-${UUID.randomUUID()}.db")
        val key = ByteArray(32) { (it + 1).toByte() }
        System.loadLibrary("sqlcipher")
        try {
            val schema =
                JSONObject(
                        instrumentation.context.assets
                            .open("app.securenote.vault.VaultDatabase/4.json")
                            .bufferedReader()
                            .use { it.readText() }
                    )
                    .getJSONObject("database")
            SQLCipherDriver(key, null, null).open(file.path).use { connection ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun sql(value: String) =
                        value.replace("\u0024{TABLE_NAME}", entity.getString("tableName"))
                    connection.execSQL(sql(entity.getString("createSql")))
                    val indexes = entity.optJSONArray("indices")
                    if (indexes != null)
                        for (j in 0 until indexes.length()) {
                            connection.execSQL(sql(indexes.getJSONObject(j).getString("createSql")))
                        }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) connection.execSQL(setup.getString(i))
                connection.execSQL(
                    "INSERT INTO items(rowId,id,name,mime,size,updated,fileKey,body,pinned,searchText) VALUES(1,'old-note','Existing note','application/x-securenote',0,1,NULL,'Original body',0,'Original body')"
                )
                connection.execSQL(
                    "INSERT INTO folders(id,parentId,name,normalizedName,color) VALUES('old-folder',NULL,'Existing folder','existing folder',0)"
                )
                connection.execSQL(
                    "INSERT INTO folder_items(itemId,folderId) VALUES('old-note','old-folder')"
                )
                connection.execSQL("PRAGMA user_version=4")
            }
            val db =
                Room.databaseBuilder<VaultDatabase>(context, file.path)
                    .setDriver(SQLCipherDriver(key, null, null))
                    .addMigrations(TrackerMigration)
                    .build()
            try {
                assertEquals("Original body", db.items().originalBody("old-note"))
                assertEquals(listOf("old-note"), db.items().members("old-folder"))
                val category =
                    TrackerCategory("reading", "Reading", TrackerFormat.reading().encode(), 1)
                db.trackers().saveCategory(category)
                db.trackers().saveEntry(TrackerEntry("entry", category.id, "Book", "{}", 1))
                assertEquals("Book", db.trackers().entries(category.id).first().single().title)
                db.trackers().deleteCategory(category.id)
                assertNull(db.trackers().findEntry("entry"))
                assertEquals("Original body", db.items().originalBody("old-note"))
            } finally {
                db.close()
            }
        } finally {
            key.fill(0)
            file.delete()
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
        }
    }

    @Test
    fun formatEditsPreserveValuesAndRejectInvalidChangesAcrossUnlockAndBackup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-trackers-${UUID.randomUUID()}"
        val restoredName = "test-trackers-restore-${UUID.randomUUID()}"
        val store = VaultStore(context, name)
        val restored = VaultStore(context, restoredName)
        val password = "Synthetic tracker password 123"
        try {
            store.create(password.toByteArray()).fill(0)
            val repository = TrackerRepository(store)
            val number = TrackerField(label = "Chapters", type = TrackerFieldType.NUMBER)
            val status =
                TrackerField(
                    label = "Status",
                    type = TrackerFieldType.STATUS,
                    options = listOf("Planned", "Finished"),
                )
            val checkbox = TrackerField(label = "Reviewed", type = TrackerFieldType.CHECKBOX)
            val image = TrackerField(label = "Cover", type = TrackerFieldType.IMAGE)
            // A metadata-only fixture exercises image-reference validation without decoding pixels.
            val imageId = "tracker-cover"
            store.dao.insert(
                VaultItem(
                    id = imageId,
                    name = "cover.png",
                    mime = "image/png",
                    size = 0,
                    updated = 1,
                )
            )
            var category =
                TrackerCategory(
                    "books",
                    "Books",
                    TrackerFormat(listOf(number, status, checkbox, image)).encode(),
                    1,
                )
            repository.saveCategory(category)
            val entry =
                TrackerEntry(
                    "book",
                    category.id,
                    "A book",
                    TrackerValues.encode(
                        mapOf(number.id to "12", status.id to "Planned", image.id to imageId)
                    ),
                    1,
                )
            repository.saveEntry(entry)
            expectInvalid {
                repository.saveEntry(
                    entry.copy(values = TrackerValues.encode(mapOf(number.id to "-1")))
                )
            }
            expectInvalid {
                repository.saveEntry(
                    entry.copy(values = TrackerValues.encode(mapOf(status.id to "Unknown")))
                )
            }
            expectInvalid {
                repository.saveEntry(
                    entry.copy(values = TrackerValues.encode(mapOf(image.id to "missing-image")))
                )
            }
            expectInvalid {
                repository.saveCategory(
                    category.copy(
                        format =
                            TrackerFormat(listOf(number.copy(type = TrackerFieldType.TEXT)))
                                .encode()
                    )
                )
            }
            expectInvalid {
                repository.saveCategory(category.copy(id = "duplicate", name = "books"))
            }
            category =
                category.copy(
                    format =
                        TrackerFormat(
                                listOf(checkbox, image, status.copy(options = listOf("Finished")))
                            )
                            .encode()
                )
            repository.saveCategory(category)
            repository.setCheckbox(entry.id, checkbox.id, true)
            val changed = checkNotNull(store.trackerDao.findEntry(entry.id))
            repository.saveEntry(changed.copy(title = "Renamed book"))
            // The editor clears an image with an explicit empty string. Omitting the key would
            // preserve the old reference because repository saves retain hidden field values.
            val renamed = checkNotNull(store.trackerDao.findEntry(entry.id))
            repository.saveEntry(renamed.copy(values = TrackerValues.encode(mapOf(image.id to ""))))
            assertEquals(
                "",
                TrackerValues.decode(checkNotNull(store.trackerDao.findEntry(entry.id)).values)[
                        image.id],
            )
            assertEquals(
                "12",
                TrackerValues.decode(checkNotNull(store.trackerDao.findEntry(entry.id)).values)[
                        number.id],
            )
            assertEquals(
                "Planned",
                TrackerValues.decode(checkNotNull(store.trackerDao.findEntry(entry.id)).values)[
                        status.id],
            )
            store.close()
            store.unlock(password.toByteArray())
            assertEquals(
                "true",
                TrackerValues.decode(checkNotNull(store.trackerDao.findEntry(entry.id)).values)[
                        checkbox.id],
            )
            val reopenedValues =
                TrackerValues.decode(checkNotNull(store.trackerDao.findEntry(entry.id)).values)
            assertEquals("", reopenedValues[image.id])
            assertEquals(
                "12",
                reopenedValues[number.id],
            ) // Removed field survives the partial save.
            assertEquals(
                "Planned",
                reopenedValues[status.id],
            ) // Removed status choice survives too.
            val backup = ByteArrayOutputStream()
            store.backup(backup)
            restored.restore(ByteArrayInputStream(backup.toByteArray()), password.toByteArray())
            restored.unlock(password.toByteArray())
            assertEquals("Renamed book", restored.trackerDao.findEntry(entry.id)?.title)
            assertEquals(category.format, restored.trackerDao.findCategory(category.id)?.format)
            val restoredValues =
                TrackerValues.decode(checkNotNull(restored.trackerDao.findEntry(entry.id)).values)
            assertEquals("", restoredValues[image.id])
            assertEquals("12", restoredValues[number.id])
            restored.close()
            store.close()
            try {
                repository.deleteCategory(category.id)
                fail("Locked tracker write was accepted")
            } catch (_: IllegalStateException) {}
        } finally {
            restored.close()
            store.close()
            File(context.noBackupFilesDir, name).deleteRecursively()
            File(context.noBackupFilesDir, restoredName).deleteRecursively()
        }
    }

    @Test
    fun formatsRoundTripStableIdentitiesAndRejectAmbiguousFields() {
        val original = TrackerFormat.reading()
        val edited =
            TrackerFormat(original.fields.reversed().map { it.copy(label = "New ${it.label}") })
        assertEquals(edited, TrackerFormat.decode(edited.encode()))
        assertEquals(original.fields.map { it.id }.toSet(), edited.fields.map { it.id }.toSet())
        try {
            TrackerFormat(
                    listOf(
                        TrackerField(label = " Status ", type = TrackerFieldType.TEXT),
                        TrackerField(label = "status", type = TrackerFieldType.TEXT),
                    )
                )
                .validate()
            fail("Duplicate field labels accepted")
        } catch (_: IllegalArgumentException) {}
        try {
            TrackerFormat(
                    listOf(
                        TrackerField(
                            label = "Status",
                            type = TrackerFieldType.STATUS,
                            options = listOf("Done", " done "),
                        )
                    )
                )
                .validate()
            fail("Duplicate status labels accepted")
        } catch (_: IllegalArgumentException) {}
    }

    private suspend fun expectInvalid(action: suspend () -> Unit) {
        try {
            action()
            fail("Invalid tracker change accepted")
        } catch (_: IllegalArgumentException) {}
    }
}
