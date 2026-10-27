package app.securenote.vault

import android.app.Service
import android.content.Intent
import android.os.*
import app.securenote.vault.core.LegacyCodec
import java.io.DataOutputStream

internal object LegacyMemory {
    init {
        System.loadLibrary("sqlcipher")
        System.loadLibrary("vault_kdf")
    }

    external fun openNative(snapshot: ByteArray): Long

    external fun nextNative(handle: Long): Array<ByteArray>?

    external fun closeNative(handle: Long)
}

/** Only this isolated process receives the old password and parses unauthenticated legacy SQL. */
class LegacyService : Service() {
    private lateinit var thread: HandlerThread
    private var source: ParcelFileDescriptor? = null
    private var sink: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("legacy-import").apply { start() }
    }

    override fun onBind(intent: Intent): IBinder =
        Messenger(
                object : Handler(thread.looper) {
                    override fun handleMessage(msg: Message) {
                        val password = msg.data.getByteArray("password") ?: return
                        source = msg.data.getParcelable("source")
                        sink = msg.data.getParcelable("sink")
                        val output = sink ?: return
                        DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(output)).use {
                            out ->
                            var handle = 0L
                            try {
                                val plain =
                                    ParcelFileDescriptor.AutoCloseInputStream(checkNotNull(source))
                                        .use { LegacyCodec.decrypt(it, password) }
                                try {
                                    handle = LegacyMemory.openNative(plain)
                                } finally {
                                    plain.fill(0)
                                }
                                var count = 0
                                while (true) {
                                    val row = LegacyMemory.nextNative(handle) ?: break
                                    require(++count <= 100000) { "Too many legacy notes" }
                                    out.writeInt(1)
                                    row.forEach { bytes ->
                                        out.writeInt(bytes.size)
                                        out.write(bytes)
                                        bytes.fill(0)
                                    }
                                }
                                out.writeInt(0)
                                out.flush()
                            } catch (e: Exception) {
                                runCatching {
                                    out.writeInt(-1)
                                    out.flush()
                                }
                            } finally {
                                password.fill(0)
                                if (handle != 0L) LegacyMemory.closeNative(handle)
                                source?.close()
                                source = null
                                sink = null
                            }
                        }
                    }
                }
            )
            .binder

    override fun onDestroy() {
        source?.close()
        sink?.close()
        thread.quitSafely()
        super.onDestroy()
    }
}
