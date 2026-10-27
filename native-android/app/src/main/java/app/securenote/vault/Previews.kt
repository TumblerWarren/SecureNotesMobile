package app.securenote.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Only encrypted DB blobs persist. Decoded bitmaps have one bounded process-local cache. */
class Previews(private val context: Context, private val store: VaultStore) : AutoCloseable {
    private val cache =
        object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    private val jobs = Semaphore(2)
    private val authority = Any()

    suspend fun thumbnail(item: ItemSummary): Bitmap? = jobs.withPermit {
        withContext(Dispatchers.IO) {
            val version = store.version()
            store.checkVersion(version)
            cache.get(item.id)?.let {
                return@withContext it
            }
            store.dao
                .preview(item.id)
                ?.let { bytes ->
                    try {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } finally {
                        bytes.fill(0)
                    }
                }
                ?.let { bitmap ->
                    try {
                        putBitmap(item.id, bitmap, version)
                        return@withContext bitmap
                    } catch (e: Exception) {
                        bitmap.recycle()
                        throw e
                    }
                }
            val bitmap =
                try {
                    when {
                        item.mime.startsWith("image/") -> {
                            val (meta, reader) = store.reader(item.id)
                            proxyDescriptor(context, reader, meta.size).use { fd ->
                                val bounds =
                                    BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, bounds)
                                var sample = 1
                                while (
                                    bounds.outWidth / sample > 384 ||
                                        bounds.outHeight / sample > 384
                                ) sample *= 2
                                BitmapFactory.decodeFileDescriptor(
                                    fd.fileDescriptor,
                                    null,
                                    BitmapFactory.Options().apply {
                                        inSampleSize = sample
                                        inPreferredConfig = Bitmap.Config.ARGB_8888
                                    },
                                )
                            }
                        }
                        item.mime == "application/pdf" ->
                            PdfClient(context).use { client ->
                                client.open(store, item.id)
                                client.render(0, 256)
                            }
                        item.mime.startsWith("video/") && Build.VERSION.SDK_INT >= 27 -> {
                            val (meta, reader) = store.reader(item.id)
                            proxyDescriptor(context, reader, meta.size).use { fd ->
                                val r = MediaMetadataRetriever()
                                try {
                                    r.setDataSource(fd.fileDescriptor)
                                    r.getScaledFrameAtTime(
                                        0,
                                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                        256,
                                        256,
                                    )
                                } finally {
                                    r.release()
                                }
                            }
                        }
                        else -> null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: return@withContext null
            try {
                store.checkVersion(version)
                ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                    val bytes = out.toByteArray()
                    try {
                        store.dao.putPreview(Preview(item.id, bytes))
                    } finally {
                        bytes.fill(0)
                    }
                }
                putBitmap(item.id, bitmap, version)
                bitmap
            } catch (e: Exception) {
                bitmap.recycle()
                throw e
            }
        }
    }

    fun getBitmap(key: String): Bitmap? = cache.get(key)

    fun putBitmap(key: String, bitmap: Bitmap, version: Long) {
        synchronized(authority) {
            store.checkVersion(version)
            cache.put(key, bitmap)
        }
    }

    fun trim() {
        cache.trimToSize(8 * 1024 * 1024)
    }

    override fun close() {
        synchronized(authority) {
            cache.snapshot().values.forEach {
                runCatching { it.eraseColor(android.graphics.Color.BLACK) }
            }
            cache.evictAll()
        }
    }
}
