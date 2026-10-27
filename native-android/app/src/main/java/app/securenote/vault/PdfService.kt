package app.securenote.vault

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.*
import java.util.concurrent.ConcurrentHashMap

/** Isolated sessions are separated by binding-intent identity. No session receives keys. */
class PdfService : Service() {
    private lateinit var thread: HandlerThread
    private val sessions = ConcurrentHashMap<String, Session>()

    private class Session {
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        var renderer: PdfRenderer? = null

        fun close() {
            closed.set(true)
            renderer?.close()
            renderer = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("pdf-render").apply { start() }
    }

    override fun onBind(intent: Intent): IBinder {
        val key = checkNotNull(intent.dataString)
        val session = Session()
        sessions[key] = session
        return Messenger(
                object : Handler(thread.looper) {
                    override fun handleMessage(msg: Message) {
                        if (session.closed.get()) {
                            msg.data.getParcelable<ParcelFileDescriptor>("fd")?.close()
                            return
                        }
                        try {
                            when (msg.what) {
                                OPEN -> {
                                    session.renderer?.close()
                                    session.renderer = null
                                    val fd =
                                        msg.data.getParcelable<ParcelFileDescriptor>("fd")
                                            ?: error("Missing document")
                                    session.renderer =
                                        try {
                                            PdfRenderer(fd)
                                        } catch (e: Exception) {
                                            fd.close()
                                            throw e
                                        }
                                    msg.replyTo.send(
                                        Message.obtain(null, OPEN, session.renderer!!.pageCount, 0)
                                    )
                                }
                                PAGE -> {
                                    val renderer = checkNotNull(session.renderer)
                                    renderer.openPage(msg.arg1).use { page ->
                                        val width = msg.arg2.coerceIn(128, 2048)
                                        val height =
                                            (width.toLong() * page.height / page.width)
                                                .coerceIn(1, 4096)
                                                .toInt()
                                        require(width.toLong() * height * 4 <= 24L * 1024 * 1024)
                                        val image =
                                            Bitmap.createBitmap(
                                                width,
                                                height,
                                                Bitmap.Config.ARGB_8888,
                                            )
                                        try {
                                            image.eraseColor(Color.WHITE)
                                            page.render(
                                                image,
                                                null,
                                                null,
                                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                            )
                                            msg.replyTo.send(
                                                Message.obtain(null, PAGE).apply {
                                                    data =
                                                        Bundle().apply {
                                                            putParcelable("bitmap", image)
                                                            putLong(
                                                                "request",
                                                                msg.data.getLong("request"),
                                                            )
                                                        }
                                                }
                                            )
                                        } finally {
                                            image.recycle()
                                        }
                                    }
                                }
                                CLOSE -> session.close()
                            }
                        } catch (e: Exception) {
                            runCatching {
                                msg.replyTo?.send(
                                    Message.obtain(null, ERROR).apply {
                                        data =
                                            Bundle().apply {
                                                putLong("request", msg.data.getLong("request"))
                                            }
                                    }
                                )
                            }
                        }
                    }
                }
            )
            .binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        sessions.remove(intent.dataString)?.let { s ->
            s.closed.set(true)
            Handler(thread.looper).post { s.close() }
        }
        return false
    }

    override fun onDestroy() {
        Handler(thread.looper).post {
            sessions.values.forEach { it.close() }
            sessions.clear()
            thread.quitSafely()
        }
        super.onDestroy()
    }

    companion object {
        const val OPEN = 1
        const val PAGE = 2
        const val CLOSE = 3
        const val ERROR = 4
    }
}
