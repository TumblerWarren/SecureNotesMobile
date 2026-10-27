package app.securenote.vault

import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FolderImportTest {
    @Test
    fun encryptedTreeImportPreservesHierarchyAndResumeDoesNotDuplicateCompletedFiles() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = "test-tree-import-${UUID.randomUUID()}"
            val store = VaultStore(context, directory)
            try {
                store.create("Synthetic directory import password".toByteArray()).fill(0)
                val uri =
                    DocumentsContract.buildTreeDocumentUri(
                        "app.securenote.syntheticfolders",
                        "root",
                    )
                val job = FolderImportJob("tree-job", uri.toString(), null)
                store.dao.putImportJob(job)
                val importer = FolderImporter(context, store)
                assertEquals(1, importer.run(job) {})
                val root = checkNotNull(store.dao.importEntry(job.id, "root"))
                val child = checkNotNull(store.dao.importEntry(job.id, "photos"))
                assertEquals(root.vaultId, store.dao.folder(child.vaultId)?.parentId)
                val photo = checkNotNull(store.dao.importEntry(job.id, "photo.jpg"))
                assertEquals(2, store.dao.members(child.vaultId).size)
                val checkpoint = checkNotNull(store.dao.importJob(job.id))
                assertEquals(2, checkpoint.completed)
                assertEquals(1, checkpoint.failed)
                assertEquals(1, importer.run(checkpoint) {})
                assertEquals(photo.vaultId, store.dao.importEntry(job.id, "photo.jpg")?.vaultId)
                assertEquals(2, store.dao.members(child.vaultId).size)
                store.close()
                store.unlock("Synthetic directory import password".toByteArray())
                val bytes = "Synthetic encrypted photo.jpg".toByteArray()
                val (_, reader) = store.reader(photo.vaultId)
                reader.use {
                    val buffer = ByteBuffer.allocate(bytes.size)
                    var offset = 0L
                    while (buffer.hasRemaining()) {
                        val n = it.read(offset, buffer)
                        check(n > 0)
                        offset += n
                    }
                    assertArrayEquals(bytes, buffer.array())
                }
            } finally {
                store.close()
                File(context.noBackupFilesDir, directory).deleteRecursively()
            }
        }
}
