package app.securenote.vault.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class EpubArchiveTest {
    private class Memory(private val bytes: ByteArray) : SeekableByteChannel {
        private var cursor = 0L
        private var open = true

        override fun read(dst: ByteBuffer): Int {
            if (!open) throw ClosedChannelException()
            if (!dst.hasRemaining()) return 0
            if (cursor >= bytes.size) return -1
            val n = minOf(dst.remaining(), bytes.size - cursor.toInt())
            dst.put(bytes, cursor.toInt(), n)
            cursor += n
            return n
        }

        override fun position() = cursor

        override fun position(value: Long): SeekableByteChannel {
            require(value >= 0)
            cursor = value
            return this
        }

        override fun size() = bytes.size.toLong()

        override fun isOpen() = open

        override fun close() {
            open = false
        }

        override fun write(src: ByteBuffer): Int = error("Read only")

        override fun truncate(size: Long): SeekableByteChannel = error("Read only")
    }

    private fun zip(entries: Map<String, ByteArray>, stored: Boolean = false): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                (linkedMapOf(
                        "mimetype" to "application/epub+zip".toByteArray(),
                        "META-INF/container.xml" to "<container/>".toByteArray(),
                    ) + entries)
                    .forEach { (name, bytes) ->
                        val entry = ZipEntry(name)
                        if (stored || name == "mimetype") {
                            entry.method = ZipEntry.STORED
                            entry.size = bytes.size.toLong()
                            entry.crc = CRC32().apply { update(bytes) }.value
                        }
                        zip.putNextEntry(entry)
                        zip.write(bytes)
                        zip.closeEntry()
                    }
            }
            out.toByteArray()
        }

    @Test
    fun storedAndDeflatedEntriesSupportRandomAccessAndLimits() {
        for (stored in listOf(false, true)) {
            val first = "<p>Café 😀 — first chapter</p>".toByteArray()
            val last = "<p>Last chapter</p>".toByteArray()
            EpubArchive(
                    Memory(
                        zip(linkedMapOf("OPS/one.xhtml" to first, "OPS/two.xhtml" to last), stored)
                    )
                )
                .use { archive ->
                    assertContentEquals(last, archive.read("OPS/two.xhtml", 1024))
                    assertContentEquals(first, archive.read("OPS/one.xhtml", 1024))
                    assertFails { archive.read("OPS/one.xhtml", 4) }
                }
        }
    }

    @Test
    fun malformedTraversalTruncationCrcAndCompressionBombsFailClosed() {
        assertFails { EpubArchive(Memory(zip(mapOf("../escape" to byteArrayOf(1))))) }
        val good =
            zip(mapOf("OPS/one.xhtml" to "unique-corruption-marker".toByteArray()), stored = true)
        assertFails { EpubArchive(Memory(good.copyOf(good.size - 8))) }
        val marker = "unique-corruption-marker".toByteArray()
        val offset =
            (0..good.size - marker.size).first { at ->
                marker.indices.all { good[at + it] == marker[it] }
            }
        val changed = good.copyOf()
        changed[offset] = (changed[offset].toInt() xor 1).toByte()
        EpubArchive(Memory(changed)).use { assertFails { it.read("OPS/one.xhtml", 1024) } }
        EpubArchive(Memory(zip(mapOf("OPS/bomb.xhtml" to ByteArray(2 * 1024 * 1024))))).use {
            assertFails { it.read("OPS/bomb.xhtml", 4 * 1024 * 1024) }
        }
    }

    @Test
    fun publicationUrlsStayInsideArchiveRoot() {
        val base = "OPS/Text/chapter.xhtml"
        assertEquals(
            "OPS/Images/front cover.jpg" to "",
            EpubArchive.resolve(base, "../Images/front%20cover.jpg"),
        )
        assertEquals(base to "résumé", EpubArchive.resolve(base, "#r%C3%A9sum%C3%A9"))
        for (ref in
            listOf(
                "../../../private",
                "../../%2e%2e/private",
                "/absolute",
                "file:///private",
                "https://example.com/a",
                "//example.com/a",
                "..%5cprivate",
                "bad%00path",
            )) assertNull(EpubArchive.resolve(base, ref), ref)
    }

    @Test
    fun duplicateResourceNamesAreRejected() {
        val bytes =
            zip(
                linkedMapOf("OPS/a.xhtml" to "A".toByteArray(), "OPS/b.xhtml" to "B".toByteArray()),
                stored = true,
            )
        val name = "OPS/b.xhtml".toByteArray()
        for (at in 0..bytes.size - name.size) if (name.indices.all { bytes[at + it] == name[it] })
            bytes[at + 4] = 'a'.code.toByte()
        assertFails { EpubArchive(Memory(bytes)) }
    }

    @Test
    fun encryptedBookNeedsNoPlaintextExtractionAndRevokesCachedAccess() {
        val dir = Files.createTempDirectory("epub-encrypted-test").toFile()
        try {
            VaultSession(Crypto.random(32)).use { session ->
                val key = Crypto.fileKey()
                val objects = EncryptedObjects(dir, "book-test")
                val plain =
                    zip(mapOf("OPS/one.xhtml" to "<p>Private book fixture</p>".toByteArray()))
                val (id, _) = objects.import(plain.inputStream(), key, session)
                val reader = objects.open(id, key, session)
                val channel =
                    object : SeekableByteChannel {
                        private var cursor = 0L

                        override fun position() = cursor

                        override fun position(value: Long): SeekableByteChannel {
                            cursor = value
                            return this
                        }

                        override fun size() = reader.size()

                        override fun read(dst: ByteBuffer): Int =
                            reader.read(cursor, dst).also { if (it > 0) cursor += it }

                        override fun isOpen() = true

                        override fun close() = reader.close()

                        override fun write(src: ByteBuffer): Int = error("Read only")

                        override fun truncate(size: Long): SeekableByteChannel = error("Read only")
                    }
                EpubArchive(channel, session::check).use { archive ->
                    assertEquals(
                        "<p>Private book fixture</p>",
                        archive.read("OPS/one.xhtml", 1024).toString(Charsets.UTF_8),
                    )
                    assertTrue(dir.walk().filter { it.isFile }.all { it.extension == "svf" })
                    session.revoke()
                    assertFails { archive.read("OPS/one.xhtml", 1024) }
                }
                plain.fill(0)
                key.fill(0)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
