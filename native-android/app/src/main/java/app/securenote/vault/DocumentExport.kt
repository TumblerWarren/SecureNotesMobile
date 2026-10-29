package app.securenote.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.securenote.vault.core.ExportNames
import java.io.OutputStream
import kotlinx.coroutines.*

internal interface ExportTarget {
    val name: String

    suspend fun directory(preferred: String): ExportTarget

    suspend fun file(preferred: String, mime: String, write: suspend (OutputStream) -> Unit): String
}

/** Only freshly created documents are opened or cleaned up. The chosen folder is never deleted. */
internal class DocumentExport(private val context: Context, private val store: VaultStore) {
    private val resolver = context.contentResolver

    private data class Created(val uri: Uri, val directory: Boolean)

    private val created = mutableListOf<Created>()
    private val knownIds = mutableSetOf<String>()

    suspend fun run(tree: Uri, write: suspend (ExportTarget) -> Unit) =
        withContext(Dispatchers.IO) {
            store.checkSession()
            require(DocumentsContract.isTreeUri(tree)) { "Choose a writable destination folder." }
            val id = DocumentsContract.getTreeDocumentId(tree)
            knownIds.add(id)
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            var complete = false
            try {
                write(Directory(tree, root, ""))
                ensureActive()
                store.checkSession()
                complete = true
            } finally {
                if (!complete)
                    withContext(NonCancellable) {
                        created.asReversed().forEach { record ->
                            runCatching {
                                val empty =
                                    !record.directory ||
                                        resolver
                                            .query(
                                                DocumentsContract.buildChildDocumentsUriUsingTree(
                                                    tree,
                                                    DocumentsContract.getDocumentId(record.uri),
                                                ),
                                                arrayOf(
                                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                                                ),
                                                null,
                                                null,
                                                null,
                                            )
                                            ?.use { !it.moveToFirst() } == true
                                if (empty) DocumentsContract.deleteDocument(resolver, record.uri)
                            }
                        }
                    }
            }
        }

    private inner class Directory(
        private val tree: Uri,
        private val uri: Uri,
        override val name: String,
    ) : ExportTarget {
        private val names: ExportNames

        init {
            store.checkSession()
            val existingNames = mutableListOf<String>()
            val children =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree,
                    DocumentsContract.getDocumentId(uri),
                )
            val columns =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                )
            (resolver.query(children, columns, null, null, null)
                    ?: error("Destination folder cannot be read"))
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        store.checkSession()
                        require(existingNames.size < 100000) {
                            "Destination has too many entries. Choose another folder."
                        }
                        knownIds.add(cursor.getString(0))
                        existingNames.add(cursor.getString(1).orEmpty())
                    }
                }
            names = ExportNames(existingNames)
        }

        private suspend fun create(preferred: String, mime: String): Pair<Uri, String> {
            currentCoroutineContext().ensureActive()
            store.checkSession()
            val requested =
                names.reserve(preferred, mime == DocumentsContract.Document.MIME_TYPE_DIR)
            val returned =
                DocumentsContract.createDocument(resolver, uri, mime, requested)
                    ?: error("Destination cannot create files")
            require(returned.authority == tree.authority) {
                "Provider returned an invalid destination."
            }
            val document =
                DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getDocumentId(returned),
                )
            // A provider must create a new document. Refuse known existing URIs before any write.
            require(knownIds.add(DocumentsContract.getDocumentId(document))) {
                "Provider returned an existing document. Nothing was overwritten."
            }
            created.add(Created(document, mime == DocumentsContract.Document.MIME_TYPE_DIR))
            currentCoroutineContext().ensureActive()
            store.checkSession()
            val actual =
                resolver
                    .query(
                        document,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null,
                        null,
                        null,
                    )
                    ?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: error("Created document cannot be read")
            require(actual == ExportNames.safe(actual)) { "Provider returned an invalid filename." }
            require(
                ExportNames.key(actual) == ExportNames.key(requested) || !names.contains(actual)
            ) {
                "Provider returned a duplicate filename. Nothing was overwritten."
            }
            names.observe(actual)
            return document to actual
        }

        override suspend fun directory(preferred: String): ExportTarget {
            val (child, actual) = create(preferred, DocumentsContract.Document.MIME_TYPE_DIR)
            return Directory(tree, child, actual)
        }

        override suspend fun file(
            preferred: String,
            mime: String,
            write: suspend (OutputStream) -> Unit,
        ): String {
            val (document, actual) = create(preferred, mime)
            (resolver.openOutputStream(document, "wt") ?: error("Destination cannot be written"))
                .use { output ->
                    val release = store.track(output)
                    try {
                        write(output)
                        currentCoroutineContext().ensureActive()
                        store.checkSession()
                    } finally {
                        release()
                    }
                }
            return actual
        }
    }
}
