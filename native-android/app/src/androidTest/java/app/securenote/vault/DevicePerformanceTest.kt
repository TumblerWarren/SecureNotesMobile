package app.securenote.vault

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic data only. Use -Pandroid.testInstrumentationRunnerArguments.importBytes=21474836480 for
 * 20 GiB.
 */
@RunWith(AndroidJUnit4::class)
class DevicePerformanceTest {
    private class Generated(private val length: Long) : InputStream() {
        private var position = 0L

        override fun read(): Int =
            if (position >= length) -1 else ((position++ / 65536) % 251).toInt()

        override fun read(bytes: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position == length) return -1
            val n = minOf(size.toLong(), length - position, 65536 - position % 65536).toInt()
            java.util.Arrays.fill(bytes, offset, offset + n, ((position / 65536) % 251).toByte())
            position += n
            return n
        }
    }

    @Test
    fun encryptedMetadataAndStreamingWorkload() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = "performance-${UUID.randomUUID()}"
        val store = VaultStore(context, root)
        val amount =
            InstrumentationRegistry.getArguments().getString("importBytes")?.toLongOrNull()
                ?: 64L * 1024 * 1024
        require(amount in 1024..20L * 1024 * 1024 * 1024)
        require(context.noBackupFilesDir.usableSpace > amount + 512L * 1024 * 1024) {
            "Insufficient fixture storage"
        }
        try {
            val start = System.nanoTime()
            store.create("Synthetic performance password".toByteArray()).fill(0)
            val unlockMs = (System.nanoTime() - start) / 1e6
            store.dao.insertAll(
                List(10000) { i ->
                    VaultItem(
                        id = UUID.randomUUID().toString(),
                        name = "Fixture $i",
                        mime = "application/x-securenote",
                        size = 0,
                        updated = i.toLong(),
                        body = "Synthetic text",
                        searchText = "synthetic searchable fixture $i",
                    )
                }
            )
            assertEquals(10000L, store.dao.count())
            assertEquals(10000L, store.dao.searchCount("synthetic"))
            val queries =
                List(30) {
                        val begin = System.nanoTime()
                        assertEquals(50, store.dao.firstPage().size)
                        (System.nanoTime() - begin) / 1e6
                    }
                    .sorted()
            val before = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val importStart = System.nanoTime()
            val id =
                store.importStream(
                    "Synthetic stream",
                    "application/octet-stream",
                    Generated(amount),
                )
            val importMs = (System.nanoTime() - importStart) / 1e6
            val after = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            val (meta, reader) = store.reader(id)
            assertEquals(amount, meta.size)
            reader.use {
                val position = amount - 128
                val bytes = ByteBuffer.allocate(128)
                assertEquals(128, it.read(position, bytes))
                assertArrayEquals(
                    ByteArray(128) { i -> (((position + i) / 65536) % 251).toByte() },
                    bytes.array(),
                )
            }
            instrumentation.sendStatus(
                0,
                Bundle().apply {
                    putString("syntheticWorkload", "10000 encrypted metadata records")
                    putDouble("createAndKdfMs", unlockMs)
                    putDouble("metadataP95Ms", queries[28])
                    putLong("importBytes", amount)
                    putDouble("importMs", importMs)
                    putLong("heapDeltaBytes", after - before)
                },
            )
        } finally {
            store.close()
            File(context.noBackupFilesDir, root).deleteRecursively()
        }
    }
}
