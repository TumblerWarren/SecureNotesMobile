package app.securenote.vault

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FolderExportTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver
        get() = context.contentResolver

    private val authority = "app.securenote.syntheticexports"

    private fun tree() =
        DocumentsContract.buildTreeDocumentUri(authority, "root-${UUID.randomUUID()}")

    private fun root(tree: Uri) =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun children(tree: Uri, parent: Uri = root(tree)): Map<String, Uri> {
        val uri =
            DocumentsContract.buildChildDocumentsUriUsingTree(
                tree,
                DocumentsContract.getDocumentId(parent),
            )
        return resolver
            .query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )!!
            .use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) put(
                        cursor.getString(1),
                        DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)),
                    )
                }
            }
    }

    private fun read(uri: Uri) = resolver.openInputStream(uri)!!.use { it.readBytes() }

    private fun create(parent: Uri, name: String, data: ByteArray): Uri {
        val uri =
            DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name)!!
        resolver.openOutputStream(uri, "wt")!!.use { it.write(data) }
        return uri
    }

    private suspend fun mustFail(block: suspend () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("Unsafe export must be rejected", rejected)
    }

    @Test
    fun filesAreFlatDuplicatesAreRenamedAndNoteAttachmentsHaveReadablePaths() = runBlocking {
        val directory = "test-folder-export-${UUID.randomUUID()}"
        val store = VaultStore(context, directory)
        val tree = tree()
        try {
            store.create("Synthetic export password 123".toByteArray())
            val a = store.importBytes("photo.jpg", "image/jpeg", "first image".toByteArray())
            val b = store.importBytes("photo.jpg", "image/jpeg", "second image".toByteArray())
            val note = store.newNote()
            store.dao.rename(note, "Project checklist")
            store.addBlock(note, "text", "Private note content")
            store.addBlock(note, "attachment", a)
            store.addBlock(note, "attachment", b)
            val existing = create(root(tree), "photo.jpg", "KEEP".toByteArray())
            val transfer = VaultTransfers(context, store)
            val plan = VaultExport(listOf(a, b, note), "export", folder = true)
            transfer.exportFolder(plan, tree)
            val files = children(tree)
            assertEquals(
                setOf("photo.jpg", "photo (2).jpg", "photo (3).jpg", "Project checklist"),
                files.keys,
            )
            assertEquals("KEEP", String(read(existing)))
            assertEquals("first image", String(read(files.getValue("photo (2).jpg"))))
            assertEquals("second image", String(read(files.getValue("photo (3).jpg"))))
            val noteFiles = children(tree, files.getValue("Project checklist"))
            assertEquals(setOf("note.json", "attachments"), noteFiles.keys)
            val attachments = children(tree, noteFiles.getValue("attachments"))
            assertEquals(setOf("photo.jpg", "photo (2).jpg"), attachments.keys)
            assertEquals("first image", String(read(attachments.getValue("photo.jpg"))))
            assertEquals("second image", String(read(attachments.getValue("photo (2).jpg"))))
            val json = JSONObject(String(read(noteFiles.getValue("note.json"))))
            val paths =
                (0 until json.getJSONArray("blocks").length())
                    .map { json.getJSONArray("blocks").getJSONObject(it) }
                    .filter { it.getString("kind") == "attachment" }
                    .map { it.getString("file") }
                    .toSet()
            assertEquals(setOf("attachments/photo.jpg", "attachments/photo (2).jpg"), paths)
            val output = ByteArrayOutputStream()
            transfer.export(plan.copy(folder = false, archive = true), output)
            val zipFiles = mutableMapOf<String, ByteArray>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    zipFiles[entry.name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
            assertTrue(
                zipFiles.keys.containsAll(
                    setOf("photo.jpg", "photo (2).jpg", "Project checklist/note.json")
                )
            )
            assertTrue(zipFiles.keys.none { it.contains(a) || it.contains(b) || it.contains(note) })
            assertEquals("first image", String(zipFiles.getValue("photo.jpg")))
            assertEquals("second image", String(zipFiles.getValue("photo (2).jpg")))
            // A late failure must clean only newly created outputs, preserving earlier exports.
            val before = children(tree)
            for (first in listOf(a, note)) {
                mustFail {
                    transfer.exportFolder(
                        VaultExport(
                            listOf(first, UUID.randomUUID().toString()),
                            "export",
                            folder = true,
                        ),
                        tree,
                    )
                }
                assertEquals(before, children(tree))
            }
            assertEquals(before, children(tree))
            assertEquals("KEEP", String(read(existing)))
            store.revoke()
            mustFail { transfer.exportFolder(plan, tree) }
            assertEquals(before, children(tree))
        } finally {
            store.close()
            DocumentsContract.deleteDocument(resolver, root(tree))
            java.io.File(context.noBackupFilesDir, directory).deleteRecursively()
        }
    }

    @Test
    fun providerReturningAnExistingDocumentCannotOverwriteOrDeleteIt() = runBlocking {
        val directory = "test-folder-export-${UUID.randomUUID()}"
        val store = VaultStore(context, directory)
        val tree = tree()
        try {
            store.create("Synthetic export password 123".toByteArray())
            val id = store.importBytes("new.txt", "text/plain", "PRIVATE".toByteArray())
            val existing = create(root(tree), "keep.txt", "KEEP".toByteArray())
            resolver.call(
                authority,
                "reuse-existing",
                DocumentsContract.getTreeDocumentId(tree),
                null,
            )
            mustFail {
                VaultTransfers(context, store)
                    .exportFolder(VaultExport(listOf(id), "export", folder = true), tree)
            }
            assertEquals("KEEP", String(read(existing)))
            assertEquals(setOf("keep.txt"), children(tree).keys)
        } finally {
            store.close()
            DocumentsContract.deleteDocument(resolver, root(tree))
            java.io.File(context.noBackupFilesDir, directory).deleteRecursively()
        }
    }
}
