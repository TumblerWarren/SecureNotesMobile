package app.securenote.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSecurityTest {
    @Test
    fun nativeKdfIsDeterministicAndConsumesPassword() {
        val password = "Synthetic test password only".toByteArray()
        val salt = ByteArray(16) { it.toByte() }
        val key = Sodium.derive(password, salt)
        assertEquals(32, key.size)
        assertEquals(
            "550c0d82f44e66d54a3affe74b5b04527bf7e75bb1c7f3c2c52db5c3e44c2448",
            key.joinToString("") { "%02x".format(it) },
        )
        assertTrue(password.all { it == 0.toByte() })
        assertArrayEquals(key, Sodium.derive("Synthetic test password only".toByteArray(), salt))
        assertFalse(
            key.contentEquals(Sodium.derive("Different synthetic password".toByteArray(), salt))
        )
        key.fill(0)
    }

    @Test
    fun encryptedIndexRoundTripAndWrongPassword() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-vault-${UUID.randomUUID()}"
        val store = VaultStore(context, name)
        try {
            val recovery = store.create("Synthetic password for testing".toByteArray())
            val id = store.newNote()
            store.dao.saveNote(id, "SECRET_TEST_TITLE", "SECRET_TEST_BODY", 1)
            store.close()
            val file = File(context.noBackupFilesDir, "$name/index.db")
            val bytes = file.readBytes()
            assertFalse(
                bytes.copyOfRange(0, 16).toString(Charsets.ISO_8859_1).startsWith("SQLite format 3")
            )
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("SECRET_TEST_BODY"))
            var rejected = false
            try {
                store.unlock("Incorrect synthetic password".toByteArray())
            } catch (e: Exception) {
                rejected = true
            }
            assertTrue(rejected)
            store.unlock("Synthetic password for testing".toByteArray())
            assertEquals("SECRET_TEST_BODY", store.dao.originalBody(id))
            store.close()
            store.recover(recovery, "Replacement synthetic password".toByteArray())
            assertEquals("SECRET_TEST_BODY", store.dao.originalBody(id))
        } finally {
            store.close()
            File(context.noBackupFilesDir, name).deleteRecursively()
        }
    }

    @Test
    fun nativeLegacyParserHandlesCheckpointedWalAndUnicode() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "synthetic-legacy-${UUID.randomUUID()}.db")
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
                db.execSQL(
                    "CREATE TABLE notes(id TEXT,title TEXT,content TEXT,updatedAt INTEGER,isPinned INTEGER,type TEXT)"
                )
                db.execSQL(
                    "INSERT INTO notes VALUES(?,?,?,?,?,?)",
                    arrayOf<Any>("old-id", "Café 😀", "<p>Private fixture</p>", 123L, 1, "text"),
                )
            }
            val bytes = source.readBytes()
            bytes[18] = 2
            bytes[19] = 2
            val handle = LegacyMemory.openNative(bytes)
            try {
                val row =
                    checkNotNull(LegacyMemory.nextNative(handle)).map {
                        it.toString(Charsets.UTF_8)
                    }
                assertEquals("Café 😀", row[1])
                assertEquals("123", row[3])
                assertEquals("1", row[4])
                assertNull(LegacyMemory.nextNative(handle))
            } finally {
                LegacyMemory.closeNative(handle)
                bytes.fill(0)
            }
            val malformed = ByteArray(100)
            "SQLite format 3\u0000".toByteArray().copyInto(malformed)
            var rejected = false
            try {
                val invalid = LegacyMemory.openNative(malformed)
                LegacyMemory.closeNative(invalid)
            } catch (e: Exception) {
                rejected = true
            }
            assertTrue(rejected)
        } finally {
            source.delete()
            File(source.path + "-journal").delete()
        }
    }
}
