package app.securenote.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Directory identities and job checkpoints are stored only in the encrypted index. */
internal class FolderImporter(private val context: Context, private val store: VaultStore) {
    private data class Document(
        val id: String,
        val name: String,
        val mime: String,
        val size: Long,
        val modified: Long,
    )

    suspend fun run(job: FolderImportJob, progress: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val dao = store.dao
            val tree = Uri.parse(job.source)
            val resolver = context.contentResolver
            fun document(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            fun query(uri: Uri): List<Document> {
                val columns =
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    )
                return resolver.query(uri, columns, null, null, null)?.use { c ->
                    val result = ArrayList<Document>()
                    while (c.moveToNext()) {
                        store.checkSession()
                        require(result.size < 20000) { "Directory has too many entries" }
                        result.add(
                            Document(
                                c.getString(0),
                                c.getString(1).orEmpty().take(255),
                                c.getString(2) ?: "application/octet-stream",
                                if (c.isNull(3)) -1 else c.getLong(3),
                                if (c.isNull(4)) -1 else c.getLong(4),
                            )
                        )
                    }
                    result
                } ?: error("Folder permission unavailable")
            }
            var completed = job.completed
            var failed = 0
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val root = query(document(rootId)).single()
            data class Pending(val doc: Document, val parent: String?, val depth: Int)
            val queue = ArrayDeque<Pending>()
            queue.add(Pending(root, job.destination, 0))
            val visited = mutableSetOf<String>()
            var discovered = 0
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                store.checkSession()
                val (doc, parent, depth) = queue.removeLast()
                require(depth <= 64 && ++discovered <= 100000) { "Folder import limit reached" }
                if (!visited.add(doc.id)) continue
                val old = dao.importEntry(job.id, doc.id)
                if (doc.mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val folder = old?.vaultId?.let { dao.folder(it) }
                    val id =
                        if (folder != null) folder.id
                        else {
                            var name = doc.name.ifBlank { "Imported folder" }.take(90)
                            var suffix = 1
                            val base = name
                            while (
                                dao.sibling(parent, name.lowercase(java.util.Locale.ROOT)) != null
                            ) name = "$base (${suffix++})"
                            val id = UUID.randomUUID().toString()
                            store.transaction {
                                dao.saveFolder(
                                    VaultFolder(
                                        id,
                                        parent,
                                        name,
                                        name.lowercase(java.util.Locale.ROOT),
                                    )
                                )
                                dao.putImportEntry(FolderImportEntry(job.id, doc.id, id, "folder"))
                            }
                            id
                        }
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc.id)
                    query(children).asReversed().forEach {
                        require(queue.size < 20000)
                        queue.add(Pending(it, id, depth + 1))
                    }
                } else {
                    val signature = "${doc.name}/${doc.size}/${doc.modified}"
                    if (
                        old != null &&
                            old.signature == signature &&
                            dao.display(old.vaultId) != null
                    )
                        continue
                    progress("Imported $completed files · reading ${doc.name}")
                    var id: String? = null
                    try {
                        val stream =
                            resolver.openInputStream(document(doc.id)) ?: error("File unavailable")
                        id =
                            store.importStream(
                                doc.name.ifBlank { "Untitled file" },
                                if (doc.name.endsWith(".epub", true)) "application/epub+zip"
                                else doc.mime,
                                stream,
                                parent,
                            ) { result ->
                                putImportEntry(FolderImportEntry(job.id, doc.id, result, signature))
                                putImportJob(job.copy(completed = completed + 1, failed = failed))
                            }
                        completed++
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        store.checkSession()
                        id?.let { store.delete(it) }
                        failed++
                        dao.putImportJob(job.copy(completed = completed, failed = failed))
                    }
                }
            }
            if (failed == 0) {
                dao.deleteImportJob(job.id)
                runCatching {
                    resolver.releasePersistableUriPermission(
                        tree,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            progress(
                "Imported $completed files" +
                    if (failed > 0) " · $failed failed; resume to retry" else ""
            )
            failed
        }
}
