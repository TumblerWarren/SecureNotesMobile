package app.securenote.vault

import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.*
import java.util.UUID
import kotlinx.coroutines.*

@OptIn(ExperimentalCoroutinesApi::class)
class PdfClient(private val context: Context) : AutoCloseable {
    private val connected = CompletableDeferred<Messenger>()
    private var opening = CompletableDeferred<Int>()
    private var rendering = CompletableDeferred<Bitmap>()
    private var request = 0L
    private var bound = false
    private var closed = false
    private val replies =
        Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    if (closed) {
                        msg.data.getParcelable<Bitmap>("bitmap")?.recycle()
                        return
                    }
                    when (msg.what) {
                        PdfService.OPEN -> opening.complete(msg.arg1)
                        PdfService.PAGE -> {
                            val bitmap = msg.data.getParcelable<Bitmap>("bitmap") ?: return
                            if (
                                msg.data.getLong("request") != request ||
                                    !rendering.complete(bitmap)
                            )
                                bitmap.recycle()
                        }
                        PdfService.ERROR -> {
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

            override fun onServiceDisconnected(name: ComponentName) {
                val error = java.io.IOException("PDF renderer stopped")
                connected.completeExceptionally(error)
                opening.completeExceptionally(error)
                rendering.completeExceptionally(error)
            }
        }

    suspend fun open(store: VaultStore, id: String): Int {
        bound =
            context.bindService(
                Intent(context, PdfService::class.java)
                    .setData(Uri.parse("vault-pdf://${UUID.randomUUID()}")),
                connection,
                Context.BIND_AUTO_CREATE,
            )
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
        return withTimeout(15000) { opening.await() }
    }

    suspend fun render(page: Int, width: Int): Bitmap {
        check(!closed)
        val deferred = CompletableDeferred<Bitmap>()
        rendering = deferred
        request++
        connected
            .await()
            .send(
                Message.obtain(null, PdfService.PAGE, page, width).apply {
                    replyTo = replies
                    data = Bundle().apply { putLong("request", request) }
                }
            )
        return try {
            withTimeout(15000) { deferred.await() }
        } catch (e: Exception) {
            deferred.cancel()
            throw e
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (connected.isCompleted && !connected.isCancelled)
            runCatching { connected.getCompleted().send(Message.obtain(null, PdfService.CLOSE)) }
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
        connected.cancel()
        opening.cancel()
        rendering.cancel()
    }
}
