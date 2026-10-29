package app.securenote.vault

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
            if (plan.backup) {
                store.backup(output)
                return@withContext
            }
            val first = checkNotNull(store.dao.display(plan.ids.first()))
            if (plan.ids.size == 1 && first.mime != "application/x-securenote") {
                copyFile(first.id, output)
                return@withContext
            }
            ZipOutputStream(output).use { zip ->
                for (id in plan.ids.distinct()) {
                    ensureActive()
                    store.checkSession()
                    val item = checkNotNull(store.dao.display(id))
                    val base = "$id/"
                    if (item.mime != "application/x-securenote") {
                        zip.putNextEntry(ZipEntry(base + safeName(item.name)))
                        copyFile(id, zip)
                        zip.closeEntry()
                    } else {
                        store.ensureBlocks(id)
                        val blocks = JSONArray()
                        val attachments = mutableSetOf<String>()
                        for (block in store.dao.blocks(id)) {
                            val value =
                                JSONObject()
                                    .put("id", block.id)
                                    .put("kind", block.kind)
                                    .put("content", block.content)
                            if (block.kind == "drawing") {
                                val strokes = JSONArray()
                                store.dao.drawingStrokes(block.id).forEach {
                                    strokes.put(
                                        JSONObject().put("id", it.id).put("content", it.content)
                                    )
                                }
                                value.put("strokes", strokes)
                            }
                            if (block.kind == "attachment") attachments.add(block.content)
                            blocks.put(value)
                        }
                        val note =
                            JSONObject()
                                .put("format", "SecureNote note export v1")
                                .put("title", item.name)
                                .put("blocks", blocks)
                        zip.putNextEntry(ZipEntry(base + "note.json"))
                        val bytes = note.toString(2).toByteArray()
                        try {
                            zip.write(bytes)
                        } finally {
                            bytes.fill(0)
                        }
                        zip.closeEntry()
                        for (attachment in attachments) {
                            val file = checkNotNull(store.dao.display(attachment))
                            zip.putNextEntry(
                                ZipEntry(base + "attachments/$attachment/" + safeName(file.name))
                            )
                            copyFile(attachment, zip)
                            zip.closeEntry()
                        }
                    }
                }
            }
        }

    companion object {
        fun safeName(name: String) =
            name
                .replace(Regex("[\\\\/\\p{Cntrl}]"), "_")
                .take(180)
                .ifBlank { "file" }
                .let { if (it == "." || it == "..") "file" else it }
    }
}
