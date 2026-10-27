package app.securenote.vault

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class VaultModel(app: Application) : AndroidViewModel(app) {
    val store = VaultStore(app)
    val previews = Previews(app, store)
    val unlocked = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    val recovery = MutableStateFlow<ByteArray?>(null)
    val screen = MutableStateFlow<String?>(null)
    val query = MutableStateFlow("")
    val category = MutableStateFlow("all")

    fun showLibrary(next: String) {
        require(next in setOf("all", "notes", "documents", "media"))
        screen.value = null
        history.clear()
        category.value = next
        touch()
    }

    val status = MutableStateFlow("")
    val legacySource = MutableStateFlow<Uri?>(null)
    val attachmentPicker = MutableStateFlow<String?>(null)
    private var attachmentNote: String? = null
    private val history = ArrayDeque<String>()

    fun navigate(id: String) {
        screen.value?.let { history.addLast(it) }
        screen.value = id
    }

    fun back() {
        screen.value = if (history.isEmpty()) null else history.removeLast()
    }

    fun beginAttachment(id: String) {
        attachmentNote = id
        attachmentPicker.value = id
    }

    fun renameNote(id: String, title: String) {
        if (!unlocked.value) return
        val dao = store.dao
        queue("title/$id") { dao.rename(id, title.take(255)) }
    }

    fun queueReadingState(id: String, state: String) {
        if (!unlocked.value || !store.isOpen) return
        require(state.length <= 512)
        val dao = store.dao
        queue("reader/$id") { dao.saveReadingState(id, state) }
    }

    fun queueBlock(block: NoteBlock) {
        if (!unlocked.value) return
        val dao = store.dao
        queue(block.id) { dao.editBlock(block) }
    }

    internal fun queueTable(block: NoteBlock, table: NoteTable) {
        if (!unlocked.value || !store.isOpen) return
        val dao = store.dao
        queue(block.id) {
            val next =
                withContext(Dispatchers.Default) {
                    block.copy(content = table.encode(), searchText = table.searchText())
                }
            dao.editBlock(next)
        }
    }

    internal fun queueDrawing(
        block: NoteBlock,
        strokes: List<NoteStroke>,
        aspect: Float,
        paper: String,
    ) {
        if (!unlocked.value || !store.isOpen) return
        val dao = store.dao
        val encoder = drawingEncoders.getOrPut(block.id) { DrawingEncoder() }
        queue(block.id) {
            val (manifest, rows) =
                withContext(Dispatchers.Default) {
                    encoder.prepare(block.id, strokes, aspect, paper)
                }
            dao.saveDrawing(block, manifest, rows)
        }
    }

    private val drawingEncoders = mutableMapOf<String, DrawingEncoder>()

    internal fun observeNoteBlocks(id: String): Flow<List<NoteBlock>> = flow {
        val dao = store.dao
        val version = store.version()
        val hydrated = mutableMapOf<String, Pair<String, NoteBlock>>()
        dao.observeBlocks(id).distinctUntilChanged().collect { rows ->
            store.checkVersion(version)
            val resolved =
                withContext(Dispatchers.Default) {
                    rows.mapNotNull { block ->
                        if (block.kind != "drawing") block
                        else {
                            val cached = hydrated[block.id]
                            if (cached != null && cached.first == block.content)
                                cached.second.copy(position = block.position)
                            else
                                DrawingPersistence.hydrate(dao, block).also {
                                    if (it != null) hydrated[block.id] = block.content to it
                                }
                        }
                    }
                }
            hydrated.keys.retainAll(rows.map { it.id }.toSet())
            store.checkVersion(version)
            emit(resolved)
        }
    }
        .flowOn(Dispatchers.IO)

    internal fun queueRichText(block: NoteBlock, snapshot: android.text.SpannedString) {
        if (!unlocked.value) return
        val dao = store.dao
        queue(block.id) {
            // Snapshot contains only immutable text and approved formatting spans, never a view.
            val html =
                withContext(Dispatchers.Default) {
                    android.text.Html.toHtml(
                        snapshot,
                        android.text.Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE,
                    )
                }
            dao.editBlock(block.copy(content = html, searchText = snapshot.toString()))
        }
    }

    internal suspend fun prepareNote(id: String) {
        val version = store.version()
        gate.withLock {
            savePending()
            store.checkVersion(version)
            store.ensureBlocks(id)
            drawingEncoders.clear()
        }
    }

    private fun queue(id: String, save: suspend () -> Unit) {
        if (!unlocked.value) return
        touch()
        val since = pendingSince.getOrPut(id) { android.os.SystemClock.elapsedRealtime() }
        pendingWrites[id] = save
        saveJobs.remove(id)?.cancel()
        saveJobs[id] = viewModelScope.launch {
            delay(
                minOf(
                    300L,
                    (2000L - (android.os.SystemClock.elapsedRealtime() - since)).coerceAtLeast(0),
                )
            )
            // Only debounce timers are cancelled by the next keystroke. A save already
            // waiting for/holding the gate must finish and stays pending until committed.
            saveJobs.remove(id)
            gate.withLock {
                try {
                    savePending(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message.value = "Changes could not be saved. They will be retried."
                }
            }
        }
    }

    fun addBlock(id: String, kind: String, content: String = "") = runWork {
        store.addBlock(id, kind, content)
    }

    fun removeBlock(block: NoteBlock) = runWork {
        store.removeNoteBlock(block)
        drawingEncoders.remove(block.id)
    }

    fun moveBlock(block: NoteBlock, direction: Int) = runWork {
        val all = store.dao.blocks(block.noteId)
        val from = all.indexOfFirst { it.id == block.id }
        val to = from + direction
        if (from >= 0 && to in all.indices)
            store.transaction {
                store.dao.setBlockPosition(all[from].id, all[to].position)
                store.dao.setBlockPosition(all[to].id, all[from].position)
                store.dao.updateNoteIndex(block.noteId, System.currentTimeMillis())
            }
    }

    fun changePassword(current: ByteArray, next: ByteArray) {
        if (busy.value) {
            current.fill(0)
            next.fill(0)
            return
        }
        runWork {
            try {
                store.changePassword(current, next)
                message.value = "Password changed."
            } finally {
                current.fill(0)
                next.fill(0)
            }
        }
    }

    fun importLegacy(password: ByteArray) {
        val source = legacySource.value
        if (source == null || busy.value) {
            password.fill(0)
            return
        }
        legacySource.value = null
        runWork {
            try {
                val count =
                    LegacyImporter(getApplication(), store).import(source, password) {
                        status.value = "Importing note $it"
                    }
                message.value = "Imported $count notes. Original file is unchanged."
            } finally {
                password.fill(0)
                status.value = ""
                store.collectOrphans()
            }
        }
    }

    var pendingImports: List<Uri> = emptyList()
    private val gate = Mutex()
    private var operation: Job? = null
    private var cleanup: Job? = null
    private val lockCallbacks = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private val pendingSince = mutableMapOf<String, Long>()
    private val pendingWrites = linkedMapOf<String, suspend () -> Unit>()
    private val saveJobs = mutableMapOf<String, Job>()

    fun onLock(callback: () -> Unit): () -> Unit {
        lockCallbacks.add(callback)
        return { lockCallbacks.remove(callback) }
    }

    private suspend fun savePending(only: String? = null) {
        val entries =
            pendingWrites.entries
                .filter { only == null || it.key == only }
                .map { it.key to it.value }
        for ((id, save) in entries) {
            save()
            // An edit can arrive while the database suspends. Never remove that newer save.
            if (pendingWrites[id] === save) {
                pendingWrites.remove(id)
                pendingSince.remove(id)
            } else pendingSince[id] = android.os.SystemClock.elapsedRealtime()
        }
    }

    suspend fun flushWrites() {
        gate.withLock { savePending() }
    }

    private var generation = 0
    private var lastInteraction = android.os.SystemClock.elapsedRealtime()
    var playing = false
    val pages =
        combine(unlocked, query.debounce(250), category) { open, q, type -> Triple(open, q, type) }
            .flatMapLatest { (open, q, type) ->
                if (!open) flowOf(PagingData.empty())
                else
                    Pager(
                            PagingConfig(
                                pageSize = 50,
                                initialLoadSize = 50,
                                prefetchDistance = 15,
                                maxSize = 200,
                            )
                        ) {
                            val tokens =
                                q.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(10)
                            if (tokens.isEmpty()) store.dao.pages(type)
                            else
                                store.dao.search(
                                    tokens.joinToString(" AND ") {
                                        "\"${it.replace("\"", "\"\"")}\"*"
                                    },
                                    type,
                                )
                        }
                        .flow
            }
            .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                if (
                    unlocked.value &&
                        !playing &&
                        android.os.SystemClock.elapsedRealtime() - lastInteraction > 300000
                )
                    lock()
            }
        }
    }

    fun touch() {
        lastInteraction = android.os.SystemClock.elapsedRealtime()
    }

    private fun runWork(block: suspend () -> Unit) {
        if (busy.value) return
        val epoch = generation
        val waitForCleanup = cleanup
        busy.value = true
        message.value = ""
        operation = viewModelScope.launch {
            try {
                waitForCleanup?.join()
                gate.withLock {
                    savePending()
                    block()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == generation)
                    message.value =
                        "Operation failed. Check the password, recovery key, file permissions, or available space."
            } finally {
                if (epoch == generation) {
                    busy.value = false
                    status.value = ""
                }
            }
        }
    }

    fun unlock(password: ByteArray, create: Boolean) {
        if (busy.value) {
            password.fill(0)
            return
        }
        runWork {
            try {
                val secret =
                    if (create) store.create(password)
                    else {
                        store.unlock(password)
                        null
                    }
                if (!currentCoroutineContext().isActive) {
                    secret?.fill(0)
                    store.close()
                    return@runWork
                }
                recovery.value = secret
                unlocked.value = true
                touch()
                store.collectOrphans()
                importPending()
            } finally {
                password.fill(0)
            }
        }
    }

    fun recover(secret: ByteArray, password: ByteArray) {
        if (busy.value) {
            secret.fill(0)
            password.fill(0)
            return
        }
        runWork {
            try {
                store.recover(secret, password)
                currentCoroutineContext().ensureActive()
                unlocked.value = true
                touch()
            } finally {
                secret.fill(0)
                password.fill(0)
            }
        }
    }

    fun dismissRecovery() {
        recovery.value?.fill(0)
        recovery.value = null
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            attachmentNote = null
            pendingImports = emptyList()
            return
        }
        pendingImports = uris
        if (unlocked.value) runWork { importPending() }
    }

    private suspend fun importPending() {
        val list = pendingImports
        pendingImports = emptyList()
        val note = attachmentNote
        attachmentNote = null
        var lastUpdate = 0L
        for (uri in list) {
            val id =
                store.import(uri) { bytes ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 100) {
                        status.value = "Importing ${bytes/1024} KiB"
                        lastUpdate = now
                    }
                }
            currentCoroutineContext().ensureActive()
            if (note != null) store.addBlock(note, "attachment", id)
        }
        if (note != null && list.isNotEmpty()) screen.value = note
    }

    fun newNote() = runWork { navigate(store.newNote()) }

    fun delete(id: String) = runWork {
        store.delete(id)
        drawingEncoders.clear()
        screen.value = null
    }

    fun pin(id: String) = runWork { store.dao.togglePin(id) }

    fun lock() {
        if (!unlocked.value && !busy.value) return
        generation++
        store.revoke()
        drawingEncoders.clear()
        operation?.cancel()
        operation = null
        lockCallbacks.toList().forEach { runCatching { it() } }
        lockCallbacks.clear()
        saveJobs.values.forEach { it.cancel() }
        saveJobs.clear()
        unlocked.value = false
        busy.value = false
        playing = false
        screen.value = null
        history.clear()
        query.value = ""
        category.value = "all"
        attachmentPicker.value = null
        dismissRecovery()
        // Readers were revoked before potentially blocking native player cleanup.
        previews.close()
        val saves = pendingWrites.values.toList()
        pendingWrites.clear()
        pendingSince.clear()
        cleanup =
            viewModelScope.launch(Dispatchers.IO) {
                store.closeReaders()
                gate.withLock {
                    try {
                        saves.forEach { runCatching { it() } }
                    } finally {
                        store.close()
                    }
                }
            }
    }

    override fun onCleared() {
        store.revoke()
        drawingEncoders.clear()
        lockCallbacks.toList().forEach { runCatching { it() } }
        lockCallbacks.clear()
        previews.close()
        val saves = pendingWrites.values.toList()
        pendingWrites.clear()
        pendingSince.clear()
        CoroutineScope(Dispatchers.IO).launch {
            store.closeReaders()
            gate.withLock {
                try {
                    saves.forEach { runCatching { it() } }
                } finally {
                    store.close()
                }
            }
        }
    }
}
