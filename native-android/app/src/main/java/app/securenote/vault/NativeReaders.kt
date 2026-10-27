@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.securenote.vault

import android.content.Context
import android.net.Uri
import android.os.*
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import app.securenote.vault.core.EncryptedObjects
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking

fun proxyDescriptor(
    context: Context,
    reader: EncryptedObjects.Reader,
    length: Long,
): ParcelFileDescriptor {
    val thread = HandlerThread("vault-descriptor").apply { start() }
    return try {
        context
            .getSystemService(StorageManager::class.java)
            .openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY,
                object : ProxyFileDescriptorCallback() {
                    override fun onGetSize(): Long = length

                    override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
                        try {
                            reader.read(offset, ByteBuffer.wrap(data, 0, size)).coerceAtLeast(0)
                        } catch (e: Exception) {
                            throw ErrnoException("vault-read", OsConstants.EIO)
                        }

                    override fun onRelease() {
                        reader.close()
                        thread.quitSafely()
                    }
                },
                Handler(thread.looper),
            )
            .also { descriptor ->
                try {
                    // Cached FUSE pages would bypass onRead and its session check after lock.
                    // O_DIRECT applies to the shared open-file description, including IPC dup FDs.
                    NativeIo.makeUncached(descriptor.fd)
                } catch (e: Exception) {
                    descriptor.close()
                    throw e
                }
            }
    } catch (e: Exception) {
        thread.quitSafely()
        reader.close()
        throw e
    }
}

class VaultMediaSource(private val store: VaultStore, private val itemId: String) :
    BaseDataSource(false) {
    private var reader: EncryptedObjects.Reader? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val (item, r) = runBlocking { store.reader(itemId) }
        reader = r
        position = dataSpec.position
        if (position > item.size) {
            close()
            throw java.io.EOFException()
        }
        remaining =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) item.size - position
            else minOf(dataSpec.length, item.size - position)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n =
            checkNotNull(reader)
                .read(
                    position,
                    ByteBuffer.wrap(buffer, offset, minOf(length.toLong(), remaining).toInt()),
                )
        if (n < 0) throw java.io.EOFException("Truncated media")
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri = Uri.parse("vault://$itemId")

    override fun close() {
        reader?.let {
            it.close()
            reader = null
            transferEnded()
        }
    }
}
