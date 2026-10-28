package app.securenote.vault

import android.app.Service
import android.content.Intent
import android.os.*
import app.securenote.vault.core.EpubArchive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Only this isolated process parses EPUB archives/markup; it receives a revocable FD, never keys.
 */
class EpubService : Service() {
    private lateinit var worker: HandlerThread
    private val sessions = ConcurrentHashMap<String, Session>()

    private class Session {
        val closed = AtomicBoolean(false)
        @Volatile var input: ParcelFileDescriptor.AutoCloseInputStream? = null
        var book: EpubBook? = null

        fun check() {
            kotlin.check(!closed.get()) { "EPUB reader is closed" }
        }

        fun cancel() {
            closed.set(true)
            runCatching { input?.close() }
        }

        fun close() {
            cancel()
            runCatching { book?.close() }
            book = null
            input = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("epub-reader").apply { start() }
    }

    override fun onBind(intent: Intent): IBinder {
        val key = checkNotNull(intent.dataString)
        val session = Session()
        sessions[key] = session
        return Messenger(
                object : Handler(worker.looper) {
                    override fun handleMessage(msg: Message) {
                        if (session.closed.get()) {
                            msg.data.getParcelable<ParcelFileDescriptor>("fd")?.close()
                            return
                        }
                        if (msg.what == CLOSE) {
                            session.close()
                            return
                        }
                        try {
                            if (msg.what == OPEN) {
                                check(session.book == null)
                                val fd =
                                    checkNotNull(msg.data.getParcelable<ParcelFileDescriptor>("fd"))
                                val input = ParcelFileDescriptor.AutoCloseInputStream(fd)
                                session.input = input
                                val archive = EpubArchive(input.channel, session::check)
                                session.book =
                                    try {
                                        EpubBook(archive, session::check)
                                    } catch (e: Exception) {
                                        archive.close()
                                        throw e
                                    }
                            }
                            val book = checkNotNull(session.book)
                            val page =
                                when (msg.what) {
                                    OPEN ->
                                        book.go(
                                            msg.data
                                                .getInt("chapter")
                                                .coerceIn(book.chapters.indices),
                                            msg.data.getInt("section"),
                                        )
                                    GO -> book.go(msg.arg1, msg.arg2)
                                    MOVE -> book.move(msg.arg1)
                                    FOLLOW -> book.follow(checkNotNull(msg.data.getString("link")))
                                    else -> error("Invalid EPUB request")
                                }
                            page.use {
                                session.check()
                                val bundle =
                                    Bundle().apply {
                                        putLong("request", msg.data.getLong("request"))
                                        putString("html", page.html)
                                        putInt("chapter", page.chapter)
                                        putInt("section", page.section)
                                        putInt("sections", page.sections)
                                        putBoolean("previous", page.previous)
                                        putBoolean("next", page.next)
                                        putStringArrayList("imageKeys", ArrayList(page.images.keys))
                                        putParcelableArrayList(
                                            "images",
                                            ArrayList(page.images.values),
                                        )
                                        if (msg.what == OPEN) {
                                            putString("title", book.title)
                                            putString("author", book.author)
                                            putStringArrayList(
                                                "chapters",
                                                ArrayList(book.chapters.map { it.title }),
                                            )
                                            putStringArrayList(
                                                "tocTitles",
                                                ArrayList(book.contents.map { it.title }),
                                            )
                                            putIntArray(
                                                "tocChapters",
                                                book.contents.map { it.chapter }.toIntArray(),
                                            )
                                            putStringArrayList(
                                                "tocAnchors",
                                                ArrayList(book.contents.map { it.anchor }),
                                            )
                                            putIntArray(
                                                "tocDepths",
                                                book.contents.map { it.depth }.toIntArray(),
                                            )
                                        }
                                    }
                                msg.replyTo.send(Message.obtain(null, PAGE).apply { data = bundle })
                            }
                        } catch (e: Exception) {
                            session.close()
                            val explanation =
                                when {
                                    e.message?.contains("DRM-protected") == true ->
                                        "This EPUB uses DRM, which this reader does not support."
                                    e.message?.contains("Fixed-layout") == true ->
                                        "This reader supports reflowable EPUBs. Fixed-layout EPUBs are not supported."
                                    e.message?.contains("ZIP64") == true ->
                                        "ZIP64 EPUB archives are not supported by this reader."
                                    else ->
                                        "This EPUB could not be read. It may be damaged or exceed the reader’s size limits."
                                }
                            runCatching {
                                msg.replyTo?.send(
                                    Message.obtain(null, ERROR).apply {
                                        data =
                                            Bundle().apply {
                                                putLong("request", msg.data.getLong("request"))
                                                putString("message", explanation)
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
        sessions.remove(intent.dataString)?.let {
            it.cancel()
            Handler(worker.looper).post { it.close() }
        }
        return false
    }

    override fun onDestroy() {
        sessions.values.forEach { it.cancel() }
        Handler(worker.looper).post {
            sessions.values.forEach { it.close() }
            sessions.clear()
            worker.quitSafely()
        }
        super.onDestroy()
    }

    companion object {
        const val OPEN = 1
        const val GO = 2
        const val MOVE = 3
        const val FOLLOW = 4
        const val CLOSE = 5
        const val PAGE = 6
        const val ERROR = 7
    }
}
