package app.securenote.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.securenote.vault.core.ExportNames
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal data class VerifiedOriginal(
    val source: Uri,
    val id: String,
    val name: String,
    val digest: ByteArray,
)

internal data class VaultExport(
    val ids: List<String>,
    val name: String,
    val backup: Boolean = false,
    val mime: String = "application/zip",
    val folder: Boolean = false,
    val archive: Boolean = false,
)

/** Every plaintext buffer is bounded and discarded; no plaintext staging file is used. */
internal class VaultTransfers(private val context: Context, private val store: VaultStore) {
    private suspend fun sourceDigest(uri: Uri): ByteArray =
        withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            try {
                (context.contentResolver.openInputStream(uri) ?: error("Source unavailable")).use {
                    input ->
                    val release = store.track(input)
                    try {
                        while (true) {
                            ensureActive()
                            store.checkSession()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (n > 0) digest.update(buffer, 0, n)
                        }
                    } finally {
                        release()
                    }
                }
                digest.digest()
            } finally {
                buffer.fill(0)
            }
        }

    suspend fun copyFile(id: String, output: OutputStream): ByteArray =
        withContext(Dispatchers.IO) {
            val (_, reader) = store.reader(id)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            try {
                reader.use {
                    var offset = 0L
                    while (true) {
                        ensureActive()
                        store.checkSession()
                        val n = it.read(offset, ByteBuffer.wrap(buffer))
                        if (n < 0) break
                        if (n == 0) continue
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        offset += n
                    }
                }
                digest.digest()
            } finally {
                buffer.fill(0)
            }
        }

    suspend fun verify(uri: Uri, id: String): VerifiedOriginal {
        val original = sourceDigest(uri)
        val saved =
            copyFile(
                id,
                object : OutputStream() {
                    override fun write(value: Int) {}

                    override fun write(bytes: ByteArray, offset: Int, length: Int) {}
                },
            )
        require(MessageDigest.isEqual(original, saved)) {
            "Original changed or verification failed. It was not deleted."
        }
        return VerifiedOriginal(uri, id, checkNotNull(store.dao.display(id)).name, saved)
    }

    suspend fun deleteOriginal(receipt: VerifiedOriginal): Boolean =
        withContext(Dispatchers.IO) {
            // Revalidate both copies after the user approves, immediately before requesting
            // deletion.
            val current = verify(receipt.source, receipt.id)
            require(MessageDigest.isEqual(current.digest, receipt.digest)) {
                "Original changed. It was not deleted."
            }
            ensureActive()
            store.checkSession()
            if (DocumentsContract.isDocumentUri(context, receipt.source))
                DocumentsContract.deleteDocument(context.contentResolver, receipt.source)
            else {
                require(receipt.source.scheme == "content") {
                    "This source does not support deletion."
                }
                context.contentResolver.delete(receipt.source, null, null) > 0
            }
        }

    suspend fun export(plan: VaultExport, output: OutputStream) =
        withContext(Dispatchers.IO) {
            store.checkSession()
            if (plan.backup) {
                store.backup(output)
                return@withContext
            }
            val first = checkNotNull(store.dao.display(plan.ids.first()))
            if (!plan.archive && plan.ids.size == 1 && first.mime != "application/x-securenote") {
                copyFile(first.id, output)
                return@withContext
            }
            ZipOutputStream(output).use { zip -> writeSelection(plan, Archive(zip)) }
        }

    suspend fun exportFolder(plan: VaultExport, tree: Uri) =
        withContext(Dispatchers.IO) {
            require(!plan.backup && !plan.archive)
            DocumentExport(context, store).run(tree) { writeSelection(plan, it) }
        }

    private suspend fun writeSelection(plan: VaultExport, target: ExportTarget) {
        require(plan.ids.isNotEmpty())
        for (id in plan.ids.distinct()) {
            currentCoroutineContext().ensureActive()
            store.checkSession()
            val item = checkNotNull(store.dao.display(id))
            if (item.mime == "application/x-securenote")
                writeNote(id, item.name, target.directory(item.name))
            else target.file(item.name, item.mime) { copyFile(id, it) }
        }
    }

    private suspend fun writeNote(id: String, title: String, target: ExportTarget) {
        store.ensureBlocks(id)
        val sourceBlocks = store.dao.blocks(id)
        val attachments =
            sourceBlocks.filter { it.kind == "attachment" }.map { it.content }.distinct()
        val paths = mutableMapOf<String, String>()
        if (attachments.isNotEmpty()) {
            val destination = target.directory("attachments")
            for (attachment in attachments) {
                val item = checkNotNull(store.dao.display(attachment))
                val name = destination.file(item.name, item.mime) { copyFile(attachment, it) }
                paths[attachment] = "${destination.name}/$name"
            }
        }
        val blocks = JSONArray()
        for (block in sourceBlocks) {
            currentCoroutineContext().ensureActive()
            store.checkSession()
            val value =
                JSONObject()
                    .put("id", block.id)
                    .put("kind", block.kind)
                    .put("content", block.content)
            if (block.kind == "attachment") value.put("file", paths.getValue(block.content))
            if (block.kind == "drawing") {
                val strokes = JSONArray()
                store.dao.drawingStrokes(block.id).forEach {
                    strokes.put(JSONObject().put("id", it.id).put("content", it.content))
                }
                value.put("strokes", strokes)
            }
            blocks.put(value)
        }
        val note =
            JSONObject()
                .put("format", "SecureNote note export v1")
                .put("title", title)
                .put("blocks", blocks)
        target.file("note.json", "application/json") { output ->
            val bytes = note.toString(2).toByteArray()
            try {
                output.write(bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }

    private inner class Archive(
        private val zip: ZipOutputStream,
        private val prefix: String = "",
        override val name: String = "",
    ) : ExportTarget {
        private val names = ExportNames()

        override suspend fun directory(preferred: String): ExportTarget {
            store.checkSession()
            val folder = names.reserve(preferred, directory = true)
            return Archive(zip, "$prefix$folder/", folder)
        }

        override suspend fun file(
            preferred: String,
            mime: String,
            write: suspend (OutputStream) -> Unit,
        ): String {
            currentCoroutineContext().ensureActive()
            store.checkSession()
            val name = names.reserve(preferred)
            zip.putNextEntry(ZipEntry(prefix + name))
            write(zip)
            store.checkSession()
            zip.closeEntry()
            return name
        }
    }

    companion object {
        fun safeName(name: String) = ExportNames.safe(name)
    }
}
