package app.securenote.vault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import androidx.room3.*
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import app.securenote.vault.core.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import net.zetetic.database.sqlcipher.driver.SQLCipherDriver

class VaultStore(private val context: Context, directoryName: String = "vault-v1") : AutoCloseable {
    init {
        require(directoryName.matches(Regex("[a-zA-Z0-9-]+")))
    }

    private val directory = File(context.noBackupFilesDir, directoryName).apply { mkdirs() }
    private val headerFile = AtomicFile(File(directory, "header"))
    private var session: VaultSession? = null
    private var header: VaultHeader? = null
    private var databaseKey: ByteArray? = null
    private var database: VaultDatabase? = null
    private var objects: EncryptedObjects? = null
    private val closed = AtomicBoolean(true)
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private val authority = Any()
    val exists: Boolean
        get() = headerFile.baseFile.exists() || File(directory, "header.bak").exists()

    val isOpen: Boolean
        get() = !closed.get()

    val dao: Items
        get() {
            check(isOpen)
            return checkNotNull(database).items()
        }

    private fun header(): VaultHeader {
        val bytes =
            headerFile.openRead().use { input ->
                val b = ByteArray(VaultHeader.SIZE)
                var offset = 0
                while (offset < b.size) {
                    val n = input.read(b, offset, b.size - offset)
                    require(n > 0)
                    offset += n
                }
                require(input.read() == -1)
                b
            }
        return VaultHeader.decode(bytes)
    }

    private fun writeHeader(h: VaultHeader) {
        val stream = headerFile.startWrite()
        try {
            stream.write(h.encode())
            headerFile.finishWrite(stream)
        } catch (e: Exception) {
            headerFile.failWrite(stream)
            throw e
        }
    }

    suspend fun create(password: ByteArray): ByteArray =
        withContext(Dispatchers.IO) {
            check(!exists && !File(directory, "index.db").exists()) { "Vault already exists" }
            val ticket = epoch.get()
            val made = VaultHeader.create(password, Sodium)
            try {
                open(made.header, made.root, ticket)
                // Ensure database creation succeeds before committing the unlock header.
                database!!.useReaderConnection {
                    it.usePrepared("SELECT count(*) FROM items") { s -> s.step() }
                }
                ensureActive()
                check(epoch.get() == ticket)
                writeHeader(made.header)
                made.recovery
            } catch (e: Exception) {
                close()
                made.recovery.fill(0)
                if (!exists)
                    listOf("index.db", "index.db-wal", "index.db-shm").forEach {
                        File(directory, it).delete()
                    }
                throw e
            } finally {
                made.root.fill(0)
            }
        }

    suspend fun unlock(password: ByteArray) =
        withContext(Dispatchers.IO) {
            val ticket = epoch.get()
            val h = header()
            val root = h.unlock(password, Sodium)
            try {
                open(h, root, ticket)
            } finally {
                root.fill(0)
            }
        }

    suspend fun recover(secret: ByteArray, password: ByteArray) =
        withContext(Dispatchers.IO) {
            val ticket = epoch.get()
            val h = header()
            val root = h.recover(secret)
            try {
                open(h, root, ticket)
                val next = h.changePassword(root, password, Sodium)
                ensureActive()
                check(epoch.get() == ticket)
                writeHeader(next)
                header = next
            } catch (e: Exception) {
                close()
                throw e
            } finally {
                root.fill(0)
                password.fill(0)
            }
        }

    private suspend fun open(h: VaultHeader, root: ByteArray, ticket: Long) {
        check(epoch.get() == ticket) { "Unlock cancelled" }
        closeResources()
        val s = VaultSession(root)
        val key = s.databaseKey()
        // Raw SQLCipher key syntax avoids a second expensive password KDF per connection.
        val raw = ByteArray(67)
        raw[0] = 'x'.code.toByte()
        raw[1] = '\''.code.toByte()
        raw[66] = '\''.code.toByte()
        val hex = "0123456789abcdef"
        key.forEachIndexed { i, b ->
            raw[2 + i * 2] = hex[(b.toInt() ushr 4) and 15].code.toByte()
            raw[3 + i * 2] = hex[b.toInt() and 15].code.toByte()
        }
        key.fill(0)
        System.loadLibrary("sqlcipher")
        try {
            val db =
                Room.databaseBuilder<VaultDatabase>(
                        context,
                        File(directory, "index.db").absolutePath,
                    )
                    .setDriver(SQLCipherDriver(raw, null, null))
                    .addMigrations(
                        object : androidx.room3.migration.Migration(1, 2) {
                            override suspend fun migrate(connection: SQLiteConnection) {
                                connection.execSQL(
                                    "CREATE TABLE IF NOT EXISTS blocks (id TEXT NOT NULL PRIMARY KEY,noteId TEXT NOT NULL,position INTEGER NOT NULL,kind TEXT NOT NULL,content TEXT NOT NULL,searchText TEXT NOT NULL,FOREIGN KEY(noteId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                                )
                                connection.execSQL(
                                    "CREATE INDEX IF NOT EXISTS index_blocks_noteId_position ON blocks(noteId,position)"
                                )
                                connection.execSQL(
                                    "CREATE TABLE IF NOT EXISTS previews (id TEXT NOT NULL PRIMARY KEY,bytes BLOB NOT NULL,FOREIGN KEY(id) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                                )
                            }
                        },
                        object : androidx.room3.migration.Migration(2, 3) {
                            override suspend fun migrate(connection: SQLiteConnection) {
                                connection.execSQL(
                                    "CREATE TABLE IF NOT EXISTS drawing_strokes (blockId TEXT NOT NULL,id TEXT NOT NULL,digest TEXT NOT NULL,content TEXT NOT NULL,PRIMARY KEY(blockId,id),FOREIGN KEY(blockId) REFERENCES blocks(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                                )
                            }
                        },
                        FolderMigration,
                    )
                    .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(2))
                    .addCallback(
                        object : androidx.room3.RoomDatabase.Callback() {
                            override suspend fun onOpen(connection: SQLiteConnection) {
                                connection.execSQL("PRAGMA temp_store=MEMORY")
                                connection.execSQL("PRAGMA synchronous=FULL")
                                connection.execSQL("PRAGMA cipher_memory_security=ON")
                                connection.prepare("PRAGMA cipher_version").use {
                                    check(it.step() && it.getText(0).isNotBlank()) {
                                        "Encryption unavailable"
                                    }
                                }
                            }
                        }
                    )
                    .build()
            database = db
            databaseKey = raw
            session = s
            header = h
            objects =
                EncryptedObjects(File(directory, "objects"), h.id.toString()).also {
                    it.cleanupPartial()
                }
            // Force key/schema validation before reporting an unlocked session.
            db.useReaderConnection {
                it.usePrepared("SELECT count(*) FROM items") { q -> q.step() }
            }
            synchronized(authority) {
                check(epoch.get() == ticket) { "Unlock cancelled" }
                closed.set(false)
            }
        } catch (e: Exception) {
            s.close()
            raw.fill(0)
            close()
            throw e
        }
    }

    suspend fun import(uri: Uri, folder: String? = null, progress: (Long) -> Unit): String =
        withContext(Dispatchers.IO) {
            checkSession()
            val name =
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0)?.take(255) else null }
                    ?: "Imported file"
            val mime =
                if (name.endsWith(".epub", true)) "application/epub+zip"
                else context.contentResolver.getType(uri) ?: "application/octet-stream"
            val stream =
                context.contentResolver.openInputStream(uri) ?: error("File permission unavailable")
            importStream(name, mime, stream, folder, progress)
        }

    suspend fun reader(id: String): Pair<VaultItem, EncryptedObjects.Reader> =
        withContext(Dispatchers.IO) {
            val item = checkNotNull(dao.find(id))
            val key = checkNotNull(item.fileKey)
            try {
                item.copy(fileKey = null) to objects!!.open(id, key, checkNotNull(session))
            } finally {
                key.fill(0)
            }
        }

    suspend fun delete(id: String) =
        withContext(Dispatchers.IO) {
            val key = dao.objectKey(id)
            dao.delete(id)
            if (key != null) {
                key.fill(0)
                objects!!.delete(id)
            }
        }

    fun version(): Long = epoch.get()

    fun checkVersion(version: Long) {
        checkSession()
        check(epoch.get() == version)
    }

    fun checkSession() {
        check(isOpen)
        checkNotNull(session).check()
    }

    fun track(resource: AutoCloseable): () -> Unit {
        val s = checkNotNull(session)
        s.track(resource)
        return { s.untrack(resource) }
    }

    fun closeReaders() {
        session?.close()
    }

    suspend fun <T> transaction(block: suspend () -> T): T {
        check(isOpen)
        return checkNotNull(database).useWriterConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { block() }
        }
    }

    suspend fun importBytes(name: String, mime: String, bytes: ByteArray): String =
        importStream(name, mime, bytes.inputStream())

    suspend fun importStream(
        name: String,
        mime: String,
        input: java.io.InputStream,
        folder: String? = null,
        progress: (Long) -> Unit = {},
        checkpoint: suspend Items.(String) -> Unit = {},
    ): String =
        withContext(Dispatchers.IO) {
            val s = checkNotNull(session)
            s.check()
            val key = Crypto.fileKey()
            var id: String? = null
            var published = false
            try {
                val (result, length) =
                    input.use {
                        objects!!.import(it, key, s) { bytes ->
                            ensureActive()
                            progress(bytes)
                        }
                    }
                id = result
                val fd = Os.open(File(directory, "objects").path, OsConstants.O_RDONLY, 0)
                try {
                    Os.fsync(fd)
                } finally {
                    Os.close(fd)
                }
                s.check()
                withContext(NonCancellable) {
                    transaction {
                        dao.insert(
                            VaultItem(
                                id = result,
                                name = name.take(255),
                                mime = mime,
                                size = length,
                                updated = System.currentTimeMillis(),
                                fileKey = key,
                            )
                        )
                        dao.moveItems(listOf(result), folder)
                        dao.checkpoint(result)
                    }
                    published = true
                }
                result
            } catch (e: Exception) {
                if (!published) id?.let { objects?.delete(it) }
                throw e
            } finally {
                key.fill(0)
            }
        }

    suspend fun changePassword(current: ByteArray, next: ByteArray) =
        withContext(Dispatchers.IO) {
            val ticket = epoch.get()
            val h = header()
            val root = h.unlock(current, Sodium)
            try {
                check(isOpen && epoch.get() == ticket)
                val updated = h.changePassword(root, next, Sodium)
                check(epoch.get() == ticket)
                writeHeader(updated)
                header = updated
            } finally {
                root.fill(0)
                current.fill(0)
                next.fill(0)
            }
        }

    suspend fun ensureBlocks(id: String) = transaction {
        val note = dao.display(id) ?: error("Note is unavailable")
        require(note.mime == "application/x-securenote")
        if (dao.blocks(id).isEmpty()) {
            val original = dao.originalBody(id).orEmpty()
            dao.saveBlock(
                NoteBlock(
                    UUID.randomUUID().toString(),
                    id,
                    0,
                    "text",
                    original,
                    org.jsoup.Jsoup.parseBodyFragment(original).text(),
                )
            )
        }
    }

    suspend fun removeNoteBlock(block: NoteBlock) = transaction {
        dao.deleteBlock(block)
        // Keep a blank paragraph so reopening cannot resurrect the retained legacy body.
        if (dao.blocks(block.noteId).isEmpty())
            dao.saveBlock(NoteBlock(UUID.randomUUID().toString(), block.noteId, 0, "text", ""))
    }

    suspend fun addBlock(noteId: String, kind: String, content: String = ""): String {
        val current = dao.blocks(noteId)
        val id = UUID.randomUUID().toString()
        dao.saveBlock(
            NoteBlock(id, noteId, (current.maxOfOrNull { it.position } ?: -1) + 1, kind, content)
        )
        return id
    }

    suspend fun collectOrphans() =
        withContext(Dispatchers.IO) {
            val s = checkNotNull(session)
            s.check()
            val referenced = dao.objectIds().toHashSet()
            File(directory, "objects").listFiles()?.forEach { f ->
                s.check()
                if (f.name.endsWith(".svf") && f.name.removeSuffix(".svf") !in referenced)
                    f.delete()
            }
        }

    suspend fun newNote(): String {
        val id = UUID.randomUUID().toString()
        dao.insert(
            VaultItem(
                id = id,
                name = "Untitled",
                mime = "application/x-securenote",
                size = 0,
                updated = System.currentTimeMillis(),
                body = "",
            )
        )
        return id
    }

    /** Ciphertext-only snapshot. The writer lease prevents index/WAL mutation during copying. */
    suspend fun backup(output: java.io.OutputStream) =
        withContext(Dispatchers.IO) {
            checkSession()
            val authenticator = checkNotNull(session).withRoot { BackupAuthentication(it) }
            checkNotNull(database).useWriterConnection { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    java.util.zip.ZipOutputStream(output).use { zip ->
                        // Ciphertext cannot be compressed usefully; avoid burning CPU on large
                        // media.
                        zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                        zip.putNextEntry(java.util.zip.ZipEntry("format"))
                        zip.write("SecureNote encrypted vault v1".toByteArray())
                        zip.closeEntry()
                        val files =
                            listOf("header", "index.db", "index.db-wal")
                                .map { File(directory, it) }
                                .filter { it.isFile } +
                                dao.objectIds().map { File(directory, "objects/$it.svf") }
                        require(
                            files.any { it.name == "header" } && files.any { it.name == "index.db" }
                        ) {
                            "Vault is incomplete. Backup was not created."
                        }
                        require(files.size + 2 <= 100000) {
                            "Vault exceeds the backup entry limit."
                        }
                        require(
                            files.fold(0L) { total, file -> Math.addExact(total, file.length()) } <=
                                1024L * 1024 * 1024 * 1024 - 64
                        ) {
                            "Vault exceeds the backup size limit."
                        }
                        val buffer = ByteArray(64 * 1024)
                        try {
                            for (file in files) {
                                checkSession()
                                ensureActive()
                                require(file.isFile) {
                                    "Vault is incomplete. Backup was not created."
                                }
                                val digest = java.security.MessageDigest.getInstance("SHA-256")
                                zip.putNextEntry(
                                    java.util.zip.ZipEntry(
                                        file.relativeTo(directory).invariantSeparatorsPath
                                    )
                                )
                                file.inputStream().use { input ->
                                    while (true) {
                                        ensureActive()
                                        checkSession()
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        zip.write(buffer, 0, n)
                                        digest.update(buffer, 0, n)
                                    }
                                }
                                zip.closeEntry()
                                authenticator.entry(
                                    file.relativeTo(directory).invariantSeparatorsPath,
                                    digest.digest(),
                                )
                            }
                            zip.putNextEntry(java.util.zip.ZipEntry("authentication"))
                            zip.write(authenticator.finish())
                            zip.closeEntry()
                        } finally {
                            buffer.fill(0)
                        }
                    }
                }
            }
        }

    /**
     * Never replaces an existing vault. Untrusted archives are validated in a separate directory.
     */
    suspend fun restore(
        input: java.io.InputStream,
        password: ByteArray,
        recovery: ByteArray? = null,
    ) =
        withContext(Dispatchers.IO) {
            require(!isOpen && !exists && !File(directory, "index.db").exists()) {
                "Restore requires an empty vault."
            }
            val stageName = "restore-" + UUID.randomUUID()
            val stage = File(context.noBackupFilesDir, stageName).apply { mkdirs() }
            var candidate: VaultStore? = null
            try {
                val seen = mutableSetOf<String>()
                val payloadDigests = linkedMapOf<String, ByteArray>()
                var total = 0L
                java.util.zip.ZipInputStream(input).use { zip ->
                    val buffer = ByteArray(64 * 1024)
                    try {
                        while (true) {
                            ensureActive()
                            val entry = zip.nextEntry ?: break
                            val name = entry.name
                            require(!entry.isDirectory && seen.add(name) && seen.size <= 100000) {
                                "Invalid backup entries."
                            }
                            require(
                                name in
                                    setOf(
                                        "format",
                                        "header",
                                        "index.db",
                                        "index.db-wal",
                                        "authentication",
                                    ) || Regex("objects/[0-9a-f-]{36}\\.svf").matches(name)
                            ) {
                                "Invalid backup path."
                            }
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            val file = File(stage, name)
                            file.parentFile!!.mkdirs()
                            java.io.FileOutputStream(file).use { out ->
                                var length = 0L
                                while (true) {
                                    ensureActive()
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    length += n
                                    total += n
                                    require(total <= 1024L * 1024 * 1024 * 1024) {
                                        "Backup exceeds restore limit."
                                    }
                                    if (name == "format") require(length <= 64)
                                    if (name == "header") require(length <= VaultHeader.SIZE)
                                    if (name == "authentication") require(length <= 32)
                                    out.write(buffer, 0, n)
                                    digest.update(buffer, 0, n)
                                }
                                out.fd.sync()
                            }
                            zip.closeEntry()
                            if (name != "format" && name != "authentication")
                                payloadDigests[name] = digest.digest()
                        }
                    } finally {
                        buffer.fill(0)
                    }
                }
                require(
                    seen.containsAll(listOf("format", "header", "index.db", "authentication"))
                ) {
                    "Incomplete backup."
                }
                require(File(stage, "format").readText() == "SecureNote encrypted vault v1") {
                    "Unsupported backup format."
                }
                File(stage, "format").delete()
                val restored = VaultStore(context, stageName)
                candidate = restored
                val h = restored.header()
                val root =
                    if (recovery == null) h.unlock(password.copyOf(), Sodium)
                    else h.recover(recovery.copyOf())
                try {
                    val authentication = BackupAuthentication(root)
                    payloadDigests.forEach { (name, digest) -> authentication.entry(name, digest) }
                    require(
                        java.security.MessageDigest.isEqual(
                            authentication.finish(),
                            File(stage, "authentication").readBytes(),
                        )
                    ) {
                        "Backup authentication failed."
                    }
                    File(stage, "authentication").delete()
                    restored.open(h, root, restored.epoch.get())
                } finally {
                    root.fill(0)
                }
                checkNotNull(restored.database).useReaderConnection { connection ->
                    connection.usePrepared("PRAGMA integrity_check") {
                        check(it.step() && it.getText(0) == "ok") { "Backup database is damaged." }
                    }
                }
                val verifier = VaultTransfers(context, restored)
                val sink =
                    object : java.io.OutputStream() {
                        override fun write(b: Int) {}

                        override fun write(b: ByteArray, o: Int, n: Int) {}
                    }
                for (id in restored.dao.objectIds()) {
                    ensureActive()
                    verifier.copyFile(id, sink)
                }
                if (recovery != null) restored.recover(recovery.copyOf(), password.copyOf())
                restored.close()
                candidate = null
                ensureActive()
                require(!exists && !File(directory, "index.db").exists()) {
                    "A vault already exists."
                }
                require(directory.listFiles().isNullOrEmpty()) { "Vault directory is not empty." }
                check(directory.delete())
                try {
                    java.nio.file.Files.move(
                        stage.toPath(),
                        directory.toPath(),
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    )
                } catch (e: Exception) {
                    directory.mkdirs()
                    throw e
                }
                val fd = Os.open(context.noBackupFilesDir.path, OsConstants.O_RDONLY, 0)
                try {
                    Os.fsync(fd)
                } finally {
                    Os.close(fd)
                }
            } finally {
                password.fill(0)
                recovery?.fill(0)
                candidate?.close()
                stage.deleteRecursively()
            }
        }

    fun revoke() {
        synchronized(authority) {
            epoch.incrementAndGet()
            closed.set(true)
            session?.revoke()
        }
    }

    override fun close() {
        revoke()
        closeResources()
    }

    @Synchronized
    private fun closeResources() {
        closed.set(true)
        session?.close()
        session = null
        database?.close()
        database = null
        databaseKey?.fill(0)
        databaseKey = null
        objects = null
        header = null
    }
}
