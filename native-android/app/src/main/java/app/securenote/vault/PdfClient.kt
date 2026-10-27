package app.securenote.vault

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class PdfClient(private val context: Context) : AutoCloseable {
    private val renderGate = Mutex()
    val dimensions = mutableMapOf<Int, Pair<Int, Int>>()
    private val connected = CompletableDeferred<Messenger>()
    private var opening = CompletableDeferred<Int>()
    private var rendering = CompletableDeferred<Bitmap>()
    private var request = 0L
    private var bound = false
    @Volatile private var closed = false
    private var authority: (() -> Unit)? = null
    private var untrack: (() -> Unit)? = null
    private val replies =
        Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (closed) {
                        msg.data.getParcelable<Bitmap>("bitmap")?.recycle()
                        return
                    }
                    try {
                        checkNotNull(authority).invoke()
                    } catch (e: Exception) {
                        msg.data.getParcelable<Bitmap>("bitmap")?.recycle()
                        opening.completeExceptionally(e)
                        rendering.completeExceptionally(e)
                        return
                    }
                    when (msg.what) {
                        PdfService.OPEN ->
                            if (msg.arg1 > 0) opening.complete(msg.arg1)
                            else opening.completeExceptionally(java.io.IOException("Empty PDF"))
                        PdfService.PAGE -> {
                            val bitmap = msg.data.getParcelable<Bitmap>("bitmap") ?: return
                            val pw = msg.data.getInt("pageWidth")
                            val ph = msg.data.getInt("pageHeight")
                            if (pw > 0 && ph > 0) dimensions[msg.data.getInt("page")] = pw to ph
                            if (
                                bitmap.width !in 1..2048 ||
                                    bitmap.height !in 1..4096 ||
                                    bitmap.allocationByteCount.toLong() > 24L * 1024 * 1024 ||
                                    msg.data.getLong("request") != request ||
                                    !rendering.complete(bitmap)
                            ) {
                                bitmap.recycle()
                                if (msg.data.getLong("request") == request && rendering.isActive)
                                    rendering.completeExceptionally(
                                        java.io.IOException("Invalid PDF page response")
                                    )
                            }
                        }
                        PdfService.ERROR -> {
                            if (
                                msg.data.getLong("request") != 0L &&
                                    msg.data.getLong("request") != request
                            )
                                return
                            val error = java.io.IOException("Cannot render PDF")
                            opening.completeExceptionally(error)
                            rendering.completeExceptionally(error)
                        }
                    }
                }
            }
        )
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!closed) connected.complete(Messenger(binder))
            }

            override fun onNullBinding(name: ComponentName) {
                onServiceDisconnected(name)
            }

            override fun onBindingDied(name: ComponentName) {
                onServiceDisconnected(name)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                val error = java.io.IOException("PDF renderer stopped")
                connected.completeExceptionally(error)
                opening.completeExceptionally(error)
                rendering.completeExceptionally(error)
            }
        }

    suspend fun open(store: VaultStore, id: String): Int {
        check(!closed && !bound)
        val version = store.version()
        authority = { store.checkVersion(version) }
        untrack = store.track(this)
        val didBind =
            context.bindService(
                Intent(context, PdfService::class.java)
                    .setData(Uri.parse("vault-pdf://${UUID.randomUUID()}")),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        synchronized(this) {
            if (closed) {
                if (didBind) context.unbindService(connection)
                error("PDF reader closed")
            }
            bound = didBind
        }
        check(bound)
        val remote = withTimeout(15000) { connected.await() }
        val (item, reader) = store.reader(id)
        proxyDescriptor(context, reader, item.size).use { fd ->
            remote.send(
                Message.obtain(null, PdfService.OPEN).apply {
                    replyTo = replies
                    data = Bundle().apply { putParcelable("fd", fd) }
                }
            )
        }
        val count = withTimeout(15000) { opening.await() }
        check(!closed)
        checkNotNull(authority).invoke()
        return count
    }

    suspend fun render(page: Int, width: Int, tileLeft: Int = -1, tileTop: Int = -1): Bitmap =
        renderGate.withLock {
            check(!closed)
            checkNotNull(authority).invoke()
            rendering.cancel()
            val deferred = CompletableDeferred<Bitmap>()
            rendering = deferred
            request++
            var received: Bitmap? = null
            try {
                val remote = withTimeout(15000) { connected.await() }
                check(!closed)
                checkNotNull(authority).invoke()
                remote.send(
                    Message.obtain(null, PdfService.PAGE, page, width).apply {
                        replyTo = replies
                        data =
                            Bundle().apply {
                                putLong("request", request)
                                putInt("tileLeft", tileLeft)
                                putInt("tileTop", tileTop)
                            }
                    }
                )
                val bitmap = withTimeout(15000) { deferred.await() }
                received = bitmap
                check(!closed)
                checkNotNull(authority).invoke()
                bitmap
            } catch (e: Exception) {
                received?.recycle()
                if (received == null && deferred.isCompleted && !deferred.isCancelled)
                    runCatching { deferred.getCompleted().recycle() }
                deferred.cancel()
                throw e
            }
        }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        if (connected.isCompleted && !connected.isCancelled)
            runCatching { connected.getCompleted().send(Message.obtain(null, PdfService.CLOSE)) }
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        connected.cancel()
        opening.cancel()
        rendering.cancel()
        untrack?.invoke()
        untrack = null
        authority = null
    }
}
