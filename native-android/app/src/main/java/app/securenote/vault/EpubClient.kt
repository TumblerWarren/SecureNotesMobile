package app.securenote.vault

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*

internal data class EpubFrame(
    val html: String,
    val images: MutableMap<String, Bitmap>,
    val chapter: Int,
    val section: Int,
    val sections: Int,
    val previous: Boolean,
    val next: Boolean,
) : AutoCloseable {
    @Synchronized
    override fun close() {
        images.values.forEach {
            if (it.isMutable) runCatching { it.eraseColor(android.graphics.Color.BLACK) }
        }
        images.clear()
    }
}

internal data class EpubContent(
    val title: String,
    val chapter: Int,
    val anchor: String,
    val depth: Int,
) {
    val link: String
        get() = "vault-epub:$chapter#" + java.net.URLEncoder.encode(anchor, "UTF-8")
}

internal data class EpubCatalog(
    val title: String,
    val author: String,
    val chapters: List<String>,
    val contents: List<EpubContent>,
)

internal data class EpubPosition(
    val chapter: Int = 0,
    val section: Int = 0,
    val fontSize: Int = 18,
    val scroll: Int = 0,
) {
    fun encode(): String =
        org.json
            .JSONObject()
            .put("reader", "epub-v1")
            .put("chapter", chapter)
            .put("section", section)
            .put("fontSize", fontSize)
            .put("scroll", scroll)
            .toString()

    companion object {
        fun decode(value: String?): EpubPosition = runCatching {
            val data = org.json.JSONObject(value.orEmpty())
            require(data.getString("reader") == "epub-v1")
            EpubPosition(
                data.getInt("chapter").coerceIn(0, 1023),
                data.getInt("section").coerceIn(0, 2047),
                data.getInt("fontSize").coerceIn(16, 28),
                data.optInt("scroll", 0).coerceIn(0, 1000000),
            )
        }
            .getOrDefault(EpubPosition())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class EpubClient(private val context: Context) : AutoCloseable {
    private val connected = CompletableDeferred<Messenger>()
    private var pending: CompletableDeferred<EpubFrame>? = null
    private var request = 0L
    private var bound = false
    @Volatile private var closed = false
    private var authority: (() -> Unit)? = null
    private var untrack: (() -> Unit)? = null
    var catalog: EpubCatalog? = null
        private set

    private val replies =
        Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    val data = msg.data
                    data.classLoader = Bitmap::class.java.classLoader
                    val images = data.getParcelableArrayList<Bitmap>("images").orEmpty()
                    fun dispose() {
                        images.forEach { runCatching { it.recycle() } }
                    }
                    if (closed || data.getLong("request") != request || pending?.isActive != true) {
                        dispose()
                        return
                    }
                    try {
                        authority?.invoke()
                        if (msg.what == EpubService.ERROR) {
                            pending?.completeExceptionally(
                                IOException(
                                    data.getString("message")?.take(240) ?: "Cannot read EPUB"
                                )
                            )
                            return
                        }
                        require(msg.what == EpubService.PAGE)
                        val html = checkNotNull(data.getString("html"))
                        require(html.length <= 60000)
                        val keys = data.getStringArrayList("imageKeys").orEmpty()
                        require(
                            images.size <= 4 &&
                                keys.size == images.size &&
                                keys.toSet().size == keys.size
                        )
                        require(
                            images.sumOf { it.allocationByteCount.toLong() } <= 12L * 1024 * 1024
                        )
                        images.forEach { require(it.width in 1..1600 && it.height in 1..1600) }
                        data.getStringArrayList("chapters")?.let { chapters ->
                            require(chapters.size in 1..1024 && chapters.all { it.length <= 160 })
                            val titles = checkNotNull(data.getStringArrayList("tocTitles"))
                            val targets = checkNotNull(data.getIntArray("tocChapters"))
                            val anchors = checkNotNull(data.getStringArrayList("tocAnchors"))
                            val depths = checkNotNull(data.getIntArray("tocDepths"))
                            require(
                                titles.size in 1..2048 &&
                                    targets.size == titles.size &&
                                    anchors.size == titles.size &&
                                    depths.size == titles.size
                            )
                            val contents =
                                titles.indices.map { i ->
                                    require(
                                        titles[i].length <= 160 &&
                                            targets[i] in chapters.indices &&
                                            anchors[i].length <= 512 &&
                                            depths[i] in 0..8
                                    )
                                    EpubContent(titles[i], targets[i], anchors[i], depths[i])
                                }
                            catalog =
                                EpubCatalog(
                                    data.getString("title").orEmpty().take(255),
                                    data.getString("author").orEmpty().take(255),
                                    chapters,
                                    contents,
                                )
                        }
                        val count = data.getInt("sections")
                        require(count in 1..2048)
                        val chapter = data.getInt("chapter")
                        require(chapter in checkNotNull(catalog).chapters.indices)
                        val section = data.getInt("section")
                        require(section in 0 until count)
                        val frame =
                            EpubFrame(
                                html,
                                keys.zip(images).toMap().toMutableMap(),
                                chapter,
                                section,
                                count,
                                data.getBoolean("previous"),
                                data.getBoolean("next"),
                            )
                        if (pending?.complete(frame) != true) frame.close()
                    } catch (e: Exception) {
                        dispose()
                        pending?.completeExceptionally(
                            IOException("Invalid EPUB reader response", e)
                        )
                    }
                }
            }
        )
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!closed) connected.complete(Messenger(binder))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                val error = IOException("EPUB reader stopped")
                connected.completeExceptionally(error)
                pending?.completeExceptionally(error)
            }

            override fun onNullBinding(name: ComponentName) {
                onServiceDisconnected(name)
            }

            override fun onBindingDied(name: ComponentName) {
                onServiceDisconnected(name)
            }
        }

    private suspend fun send(message: Message): EpubFrame {
        check(!closed)
        checkNotNull(authority).invoke()
        val remote = withTimeout(20000) { connected.await() }
        check(!closed)
        checkNotNull(authority).invoke()
        pending?.cancel()
        val result = CompletableDeferred<EpubFrame>()
        pending = result
        request++
        message.data.putLong("request", request)
        message.replyTo = replies
        var received: EpubFrame? = null
        return try {
            remote.send(message)
            val frame = withTimeout(20000) { result.await() }
            received = frame
            check(!closed)
            checkNotNull(authority).invoke()
            frame
        } catch (e: Exception) {
            received?.close()
            if (received == null && result.isCompleted && !result.isCancelled)
                runCatching { result.getCompleted().close() }
            result.cancel()
            throw e
        } finally {
            if (pending === result) pending = null
        }
    }

    suspend fun open(store: VaultStore, id: String, position: EpubPosition): EpubFrame {
        check(!closed && !bound)
        val version = store.version()
        authority = { store.checkVersion(version) }
        authority?.invoke()
        untrack = store.track(this)
        val didBind =
            context.bindService(
                Intent(context, EpubService::class.java)
                    .setData(Uri.parse("vault-epub://${UUID.randomUUID()}")),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        synchronized(this) {
            if (closed) {
                if (didBind) context.unbindService(connection)
                error("EPUB reader is closed")
            }
            bound = didBind
        }
        check(bound)
        withTimeout(20000) { connected.await() }
        val (item, reader) = store.reader(id)
        return proxyDescriptor(context, reader, item.size).use { fd ->
            send(
                Message.obtain(null, EpubService.OPEN).apply {
                    data =
                        Bundle().apply {
                            putParcelable("fd", fd)
                            putInt("chapter", position.chapter)
                            putInt("section", position.section)
                        }
                }
            )
        }
    }

    suspend fun go(chapter: Int, section: Int = 0) =
        send(Message.obtain(null, EpubService.GO, chapter, section))

    suspend fun move(direction: Int) = send(Message.obtain(null, EpubService.MOVE, direction, 0))

    suspend fun follow(link: String): EpubFrame {
        require(
            link.length <= 4096 &&
                link.matches(Regex("vault-epub:[0-9]{1,4}#[A-Za-z0-9%+._~*\\-]*"))
        )
        return send(
            Message.obtain(null, EpubService.FOLLOW).apply {
                data = Bundle().apply { putString("link", link) }
            }
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (connected.isCompleted && !connected.isCancelled)
            runCatching { connected.getCompleted().send(Message.obtain(null, EpubService.CLOSE)) }
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        connected.cancel()
        pending?.cancel()
        pending = null
        catalog = null
        untrack?.invoke()
        untrack = null
        authority = null
    }
}
