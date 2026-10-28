package app.securenote.vault

import android.content.*
import android.net.Uri
import android.os.*
import android.util.Base64
import java.io.DataInputStream
import java.util.UUID
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

class LegacyImporter(private val context: Context, private val store: VaultStore) {
    suspend fun import(uri: Uri, password: ByteArray, progress: (Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val ready = CompletableDeferred<Messenger>()
            val pipe = ParcelFileDescriptor.createReliablePipe()
            val input = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipe[0]))
            val untrack = store.track(input)
            var bound = false
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        ready.complete(Messenger(binder))
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        ready.completeExceptionally(java.io.IOException("Legacy reader stopped"))
                        runCatching { input.close() }
                    }
                }
            val importedObjects = mutableListOf<String>()
            try {
                bound =
                    context.bindService(
                        Intent(context, LegacyService::class.java)
                            .setData(Uri.parse("vault-import://${UUID.randomUUID()}")),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                check(bound)
                val remote = withTimeout(15000) { ready.await() }
                context.contentResolver.openFileDescriptor(uri, "r")!!.use { source ->
                    pipe[1].use { sink ->
                        remote.send(
                            Message.obtain(null, 1).apply {
                                data =
                                    Bundle().apply {
                                        putParcelable("source", source)
                                        putParcelable("sink", sink)
                                        putByteArray("password", password)
                                    }
                            }
                        )
                    }
                }
                password.fill(0)
                store.transaction {
                    var count = 0
                    while (true) {
                        ensureActive()
                        when (input.readInt()) {
                            0 -> break
                            1 -> {
                                val row =
                                    List(6) {
                                        val length = input.readInt()
                                        require(length in 0..16 * 1024 * 1024)
                                        val bytes = ByteArray(length)
                                        input.readFully(bytes)
                                        bytes.toString(Charsets.UTF_8).also { bytes.fill(0) }
                                    }
                                require(++count <= 100000)
                                importRow(row, importedObjects)
                                progress(count)
                            }
                            else -> error("Legacy password, schema, or file is invalid")
                        }
                    }
                    count
                }
            } catch (e: Exception) {
                // The transaction rolls back metadata. Encrypted orphan files are removed on next
                // unlock.
                throw e
            } finally {
                password.fill(0)
                untrack()
                runCatching { input.close() }
                runCatching { pipe[1].close() }
                if (bound) context.unbindService(connection)
            }
        }

    private suspend fun importRow(row: List<String>, objects: MutableList<String>) {
        val id = UUID.randomUUID().toString()
        val html = row[2]
        store.dao.insert(
            VaultItem(
                id = id,
                name = row[1].ifBlank { "Imported note" }.take(255),
                mime = "application/x-securenote",
                size = 0,
                updated = row[3].toLongOrNull() ?: System.currentTimeMillis(),
                pinned = (row[4].toLongOrNull() ?: 0L) != 0L,
                body = html,
            )
        )
        // Retain original HTML/drawing inside encrypted storage for lossless preservation.
        val doc = Jsoup.parseBodyFragment(if (row[5] == "drawing") "<img src=\"$html\">" else html)
        var position = 0L
        val safe = Safelist.basic().removeTags("img").removeProtocols("a", "href", "ftp", "mailto")
        suspend fun visit(node: org.jsoup.nodes.Node) {
            if (node is org.jsoup.nodes.TextNode) {
                if (node.text().isNotBlank())
                    store.dao.saveBlock(
                        NoteBlock(
                            UUID.randomUUID().toString(),
                            id,
                            position++,
                            "text",
                            org.jsoup.nodes.Entities.escape(node.text()),
                            node.text(),
                        )
                    )
                return
            }
            val element = node as? org.jsoup.nodes.Element ?: return
            when (element.tagName()) {
                "img",
                "audio",
                "video",
                "source" -> {
                    val src = element.attr("src")
                    if (src.startsWith("data:") && src.contains(";base64,")) {
                        val mime = src.substring(5).substringBefore(';').lowercase()
                        val safeMime =
                            if (
                                mime.startsWith("image/") ||
                                    mime.startsWith("audio/") ||
                                    mime.startsWith("video/")
                            )
                                mime
                            else "application/octet-stream"
                        val raw = Base64.decode(src.substringAfter(";base64,"), Base64.DEFAULT)
                        val objectId =
                            try {
                                store.importBytes("Imported attachment", safeMime, raw)
                            } finally {
                                raw.fill(0)
                            }
                        objects.add(objectId)
                        store.dao.saveBlock(
                            NoteBlock(
                                UUID.randomUUID().toString(),
                                id,
                                position++,
                                "attachment",
                                objectId,
                            )
                        )
                    } else element.childNodes().forEach { visit(it) }
                }
                "table" -> {
                    val rows = org.json.JSONArray()
                    for (tr in element.select("tr").take(20)) {
                        val cells = org.json.JSONArray()
                        tr.select("th,td").take(12).forEach { cells.put(it.text()) }
                        rows.put(cells)
                    }
                    store.dao.saveBlock(
                        NoteBlock(
                            UUID.randomUUID().toString(),
                            id,
                            position++,
                            "table",
                            rows.toString(),
                            element.text(),
                        )
                    )
                    // Preserve embedded media as attachments as well as retaining original HTML.
                    element.select("img,audio,video").forEach { visit(it) }
                }
                else -> {
                    if (element.select("table,img,audio,video,source").isNotEmpty())
                        element.childNodes().forEach { visit(it) }
                    else {
                        val clean = Jsoup.clean(element.outerHtml(), safe)
                        if (element.text().isNotBlank())
                            store.dao.saveBlock(
                                NoteBlock(
                                    UUID.randomUUID().toString(),
                                    id,
                                    position++,
                                    "text",
                                    clean,
                                    element.text(),
                                )
                            )
                    }
                }
            }
        }
        doc.body().childNodes().forEach { visit(it) }
    }
}
