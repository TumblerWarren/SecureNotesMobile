package app.securenote.vault.core

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Arrays
import kotlin.test.*
import org.junit.jupiter.api.Tag

@Tag("large")
class LargeFileTest {
    private class Generated(private val total: Long) : InputStream() {
        private var position = 0L

        override fun read(): Int = error("Use buffered reads")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position == total) return -1
            val n = minOf(len.toLong(), total - position, 65536 - (position % 65536)).toInt()
            Arrays.fill(b, off, off + n, ((position / 65536) % 251).toByte())
            position += n
            return n
        }
    }

    @Test
    fun boundedHeapAnd64BitOffsets() {
        assertTrue(Runtime.getRuntime().maxMemory() <= 128L * 1024 * 1024)
        val length = 4L * 1024 * 1024 * 1024 + 65537
        val dir = Files.createTempDirectory("vault-large-").toFile()
        try {
            val baseline = File(dir, "baseline")
            val copyStart = System.nanoTime()
            FileOutputStream(baseline).use { out ->
                Generated(length).use { it.copyTo(out, 65536) }
                out.fd.sync()
            }
            val copySeconds = (System.nanoTime() - copyStart) / 1e9
            assertEquals(length, baseline.length())
            assertTrue(baseline.delete())
            VaultSession(Crypto.random(32)).use { session ->
                val objects = EncryptedObjects(dir, "large-test")
                val key = Crypto.fileKey()
                val start = System.nanoTime()
                val (id, size) = objects.import(Generated(length), key, session)
                val seconds = (System.nanoTime() - start) / 1e9
                assertEquals(length, size)
                objects.open(id, key, session).use { reader ->
                    assertEquals(length, reader.size())
                    for (offset in listOf(0L, 2147483648L + 123, 4294967296L + 17, length - 128)) {
                        val buffer = ByteBuffer.allocate(128)
                        assertEquals(128, reader.read(offset, buffer))
                        assertContentEquals(
                            ByteArray(128) { (((offset + it) / 65536) % 251).toByte() },
                            buffer.array(),
                        )
                    }
                }
                println(
                    "Synthetic bytes=$length heapLimitMiB=128 plainCopySeconds=$copySeconds encryptedImportSeconds=$seconds throughputRatio=${copySeconds/seconds}"
                )
                objects.delete(id)
                key.fill(0)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
