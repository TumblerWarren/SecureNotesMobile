package app.securenote.vault

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FolderRegressionTest {
    @Test
    fun folderMovesPreserveCiphertextAndRejectCyclesAndInvalidBatches() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "test-folders-${UUID.randomUUID()}"
        val store = VaultStore(context, directory)
        try {
            store.create("Synthetic folder password 123".toByteArray()).fill(0)
            val dao = store.dao
            val parent = VaultFolder("parent", null, "Travel", "travel")
            val child = VaultFolder("child", parent.id, "Photos", "photos")
            dao.saveFolder(parent)
            dao.saveFolder(child)
            val bytes = "Encrypted folder payload".toByteArray()
            val id = store.importStream("ticket.txt", "text/plain", bytes.inputStream(), child.id)
            val before = checkNotNull(dao.objectKey(id)).copyOf()
            dao.moveItems(listOf(id), parent.id)
            assertArrayEquals(before, dao.objectKey(id))
            assertEquals(listOf(id), dao.members(parent.id))
            try {
                dao.saveFolder(parent.copy(parentId = child.id))
                fail("Cycle accepted")
            } catch (_: IllegalArgumentException) {}
            try {
                dao.moveItems(listOf(id, "missing"), child.id)
                fail("Invalid batch accepted")
            } catch (_: IllegalArgumentException) {}
            assertEquals(listOf(id), dao.members(parent.id))
            dao.dissolveFolder(parent.id)
            assertEquals(null, dao.folder(child.id)?.parentId)
            assertTrue(dao.members(parent.id).isEmpty())
            store.close()
            store.unlock("Synthetic folder password 123".toByteArray())
            val (_, reader) = store.reader(id)
            reader.use {
                val buffer = java.nio.ByteBuffer.allocate(bytes.size)
                it.read(0L, buffer)
                assertArrayEquals(bytes, buffer.array())
            }
            before.fill(0)
        } finally {
            store.close()
            File(context.noBackupFilesDir, directory).deleteRecursively()
        }
    }

    @Test
    fun importCheckpointAndMembershipRollbackTogether() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "test-import-checkpoint-${UUID.randomUUID()}"
        val store = VaultStore(context, directory)
        try {
            store.create("Synthetic checkpoint password".toByteArray()).fill(0)
            val dao = store.dao
            dao.putImportJob(FolderImportJob("job", "content://synthetic", null))
            var generated = ""
            try {
                store.importStream(
                    "test.bin",
                    "application/octet-stream",
                    byteArrayOf(1, 2, 3).inputStream(),
                ) { id ->
                    generated = id
                    putImportEntry(FolderImportEntry("job", "source", id, "signature"))
                    error("Synthetic checkpoint failure")
                }
                fail("Import unexpectedly succeeded")
            } catch (_: IllegalStateException) {}
            assertNull(dao.display(generated))
            assertNull(dao.importEntry("job", "source"))
            assertEquals(0, dao.importJob("job")?.completed)
        } finally {
            store.close()
            File(context.noBackupFilesDir, directory).deleteRecursively()
        }
    }

    @Test
    fun formatStateFollowsSelectionAndDistinguishesMixedCoverage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val edit = NoteEditText(InstrumentationRegistry.getInstrumentation().targetContext)
            try {
                edit.load(android.text.SpannedString("One two"))
                edit.setSelection(0, 3)
                edit.toggle("bold")
                assertEquals(FormatState.On, edit.formatState("bold"))
                var changes = 0
                edit.onSelection = { changes++ }
                edit.setSelection(0, 7)
                assertEquals(FormatState.Mixed, edit.formatState("bold"))
                assertTrue(changes > 0)
                edit.toggle("bold")
                assertEquals(FormatState.On, edit.formatState("bold"))
                edit.toggle("bold")
                assertEquals(FormatState.Off, edit.formatState("bold"))
            } finally {
                edit.dispose()
            }
        }
    }

    @Test
    fun mediaQueueStaysInFolderAndDynamicSourceRejectsExternalUris() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "test-media-queue-${UUID.randomUUID()}"
        val store = VaultStore(context, directory)
        try {
            store.create("Synthetic media queue password".toByteArray()).fill(0)
            store.dao.saveFolder(VaultFolder("a", null, "Music", "music"))
            store.dao.saveFolder(VaultFolder("b", null, "Other", "other"))
            val bytes = byteArrayOf(10, 20, 30, 40, 50, 60)
            val selected = store.importStream("one.wav", "audio/wav", bytes.inputStream(), "a")
            store.importStream("outside.wav", "audio/wav", bytes.inputStream(), "b")
            store.importStream("movie.mp4", "video/mp4", bytes.inputStream(), "a")
            assertEquals(listOf(selected), store.dao.mediaSiblings(selected, true).map { it.id })
            val source = VaultMediaSource(store)
            try {
                assertEquals(
                    3L,
                    source.open(
                        androidx.media3.datasource.DataSpec.Builder()
                            .setUri("vault://$selected")
                            .setPosition(2)
                            .setLength(3)
                            .build()
                    ),
                )
                val actual = ByteArray(3)
                assertEquals(3, source.read(actual, 0, 3))
                assertArrayEquals(byteArrayOf(30, 40, 50), actual)
            } finally {
                source.close()
            }
            val external = VaultMediaSource(store)
            try {
                val rejected = runCatching {
                    external.open(
                        androidx.media3.datasource.DataSpec.Builder()
                            .setUri("https://example.invalid/file.mp4")
                            .build()
                    )
                }.exceptionOrNull()
                assertTrue(rejected is IllegalArgumentException)
            } finally {
                external.close()
            }
            val summary = store.dao.folderSummaries(null).first().first { it.folder.id == "a" }
            assertEquals(2, summary.fileCount)
            assertEquals(12L, summary.totalSize)
        } finally {
            store.close()
            File(context.noBackupFilesDir, directory).deleteRecursively()
        }
    }
}
