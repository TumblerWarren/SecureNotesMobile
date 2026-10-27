package app.securenote.vault.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class VaultSession(root: ByteArray) : AutoCloseable {
    private val root = root.copyOf()
    private val active = AtomicBoolean(true)
    private val readers = CopyOnWriteArrayList<AutoCloseable>()

    fun check() {
        check(active.get()) { "Vault is locked" }
    }

    @Synchronized
    fun databaseKey(): ByteArray {
        check()
        return Crypto.subkey(root, "database")
    }

    @Synchronized
    fun <T> withRoot(block: (ByteArray) -> T): T {
        check()
        return block(root)
    }

    fun track(reader: AutoCloseable) {
        readers.add(reader)
        if (!active.get()) {
            readers.remove(reader)
            reader.close()
            error("Vault is locked")
        }
    }

    fun untrack(reader: AutoCloseable) {
        readers.remove(reader)
    }

    fun revoke() {
        active.set(false)
    }

    override fun close() {
        revoke()
        readers.forEach { runCatching { it.close() } }
        readers.clear()
        synchronized(this) { root.fill(0) }
    }
}

class EncryptedObjects(private val directory: File, private val vaultId: String) {
    init {
        directory.mkdirs()
    }

    private val pending = File(directory, ".pending").apply { mkdirs() }

    private fun path(id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(directory, "$id.svf")
    }

    fun import(
        input: InputStream,
        key: ByteArray,
        session: VaultSession,
        progress: (Long) -> Unit = {},
    ): Pair<String, Long> {
        session.check()
        val id = UUID.randomUUID().toString()
        val target = path(id)
        val partial = File(pending, "$id.part")
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        session.track(input)
        try {
            FileOutputStream(partial).use { file ->
                // Keep the file descriptor open after Tink finalizes the authenticated stream.
                val nonClosing =
                    object : java.io.FilterOutputStream(file) {
                        // FilterOutputStream's default bulk write loops through write(int).
                        // Delegate bulk writes directly to preserve bounded streaming throughput.
                        override fun write(bytes: ByteArray, offset: Int, length: Int) {
                            file.write(bytes, offset, length)
                        }

                        override fun write(bytes: ByteArray) {
                            file.write(bytes)
                        }

                        override fun close() {
                            flush()
                        }
                    }
                Crypto.stream(key).newEncryptingStream(nonClosing, Crypto.aad(vaultId, id)).use {
                    output ->
                    while (true) {
                        session.check()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        output.write(buffer, 0, n)
                        size = Math.addExact(size, n.toLong())
                        progress(size)
                    }
                }
                session.check()
                file.fd.sync()
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            // Atomic rename durability, in addition to file payload durability.
            session.check()
            return id to size
        } catch (e: Exception) {
            partial.delete()
            target.delete()
            throw e
        } finally {
            session.untrack(input)
            buffer.fill(0)
        }
    }

    fun open(id: String, key: ByteArray, session: VaultSession): Reader {
        session.check()
        val source = FileInputStream(path(id)).channel
        try {
            val channel =
                Crypto.stream(key).newSeekableDecryptingChannel(source, Crypto.aad(vaultId, id))
            // Tink selects the matching key on the first authenticated read, before size().
            val first = ByteBuffer.allocate(1)
            try {
                channel.read(first)
                channel.position(0)
            } finally {
                first.array().fill(0)
            }
            return Reader(channel, session).also(session::track)
        } catch (e: Exception) {
            source.close()
            throw e
        }
    }

    fun delete(id: String) {
        check(!path(id).exists() || path(id).delete()) { "Could not delete object" }
    }

    fun cleanupPartial() {
        pending.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    class Reader
    internal constructor(
        private val channel: SeekableByteChannel,
        private val session: VaultSession,
    ) : AutoCloseable {
        @Synchronized
        fun read(offset: Long, destination: ByteBuffer): Int {
            session.check()
            require(offset >= 0)
            channel.position(offset)
            return channel.read(destination).also { session.check() }
        }

        @Synchronized
        fun size(): Long {
            session.check()
            return channel.size()
        }

        @Synchronized
        override fun close() {
            channel.close()
            session.untrack(this)
        }
    }
}
