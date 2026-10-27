package app.securenote.vault

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException

/** Fixed synthetic hierarchy only; packaged exclusively in the instrumentation APK. */
class SyntheticFolderProvider : ContentProvider() {
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parts = uri.pathSegments
        return if (parts.lastOrNull() == "children")
            queryChildDocuments(parts[parts.size - 2], projection, sortOrder)
        else queryDocument(parts.last(), projection)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        openDocument(uri.lastPathSegment!!, mode, null)

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment in listOf("root", "photos")) Document.MIME_TYPE_DIR
        else "application/octet-stream"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun onCreate() = true

    private val columns =
        arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

    private fun cursor(ids: List<String>, projection: Array<out String>?): Cursor {
        val selected = projection ?: columns
        return MatrixCursor(selected).apply {
            ids.forEach { id ->
                addRow(
                    selected.map { column ->
                        when (column) {
                            Document.COLUMN_DOCUMENT_ID -> id
                            Document.COLUMN_DISPLAY_NAME -> id
                            Document.COLUMN_MIME_TYPE ->
                                when (id) {
                                    "root",
                                    "photos" -> Document.MIME_TYPE_DIR
                                    "photo.jpg" -> "image/jpeg"
                                    "clip.mp4" -> "video/mp4"
                                    else -> "application/pdf"
                                }
                            Document.COLUMN_SIZE -> payload(id).size.toLong()
                            Document.COLUMN_LAST_MODIFIED -> 1L
                            Document.COLUMN_FLAGS -> 0
                            else -> null
                        }
                    }
                )
            }
        }
    }

    private fun payload(id: String) = "Synthetic encrypted $id".toByteArray()

    fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(arrayOf("root_id", "document_id", "title", "flags")).apply {
            addRow(arrayOf("root", "root", "Synthetic", 0))
        }

    fun queryDocument(documentId: String, projection: Array<out String>?) =
        cursor(listOf(documentId), projection)

    fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ) =
        cursor(
            if (parentDocumentId == "root") listOf("photos", "broken.pdf")
            else if (parentDocumentId == "photos") listOf("photo.jpg", "clip.mp4") else emptyList(),
            projection,
        )

    fun isChildDocument(parentDocumentId: String, documentId: String) =
        parentDocumentId == "root" ||
            parentDocumentId == "photos" && documentId in listOf("photo.jpg", "clip.mp4")

    fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (documentId == "broken.pdf") throw FileNotFoundException("Synthetic unavailable source")
        require(mode == "r")
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {
                    it.write(payload(documentId))
                }
            }
        }
            .start()
        return pipe[0]
    }
}
