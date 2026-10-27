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
        val dao = store.dao
        queue("title/$id") { dao.rename(id, title.take(255)) }
    }

    fun queueBlock(block: NoteBlock) {
        val dao = store.dao
        queue(block.id) { dao.saveBlock(block) }
    }

    private fun queue(id: String, save: suspend () -> Unit) {
        if (!unlocked.value) return
        touch()
        pendingWrites[id] = save
        saveJobs.remove(id)?.cancel()
        saveJobs[id] = viewModelScope.launch {
            delay(300)
            gate.withLock {
                val action = pendingWrites.remove(id) ?: return@withLock
                try {
                    action()
                } catch (e: Exception) {
                    message.value = "Changes could not be saved."
                }
            }
        }
    }

    fun addBlock(id: String, kind: String, content: String = "") = runWork {
        store.addBlock(id, kind, content)
    }

    fun removeBlock(block: NoteBlock) {
        saveJobs.remove(block.id)?.cancel()
        pendingWrites.remove(block.id)
        runWork { store.dao.deleteBlock(block) }
    }

    fun moveBlock(block: NoteBlock, direction: Int) = runWork {
        val all = store.dao.blocks(block.noteId)
        val from = all.indexOfFirst { it.id == block.id }
        val to = from + direction
        if (from >= 0 && to in all.indices)
            store.transaction {
                store.dao.saveBlock(all[from].copy(position = all[to].position))
                store.dao.saveBlock(all[to].copy(position = all[from].position))
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
    private val pendingWrites = linkedMapOf<String, suspend () -> Unit>()
    private val saveJobs = mutableMapOf<String, Job>()

    fun onLock(callback: () -> Unit): () -> Unit {
        lockCallbacks.add(callback)
        return { lockCallbacks.remove(callback) }
    }

    fun queueNote(id: String, title: String, body: String, complete: () -> Unit) {
        if (!unlocked.value) return
        touch()
        val dao = store.dao
        pendingWrites[id] = { dao.saveNote(id, title, body, System.currentTimeMillis()) }
        saveJobs.remove(id)?.cancel()
        saveJobs[id] = viewModelScope.launch {
            delay(300)
            gate.withLock {
                val save = pendingWrites.remove(id) ?: return@withLock
                try {
                    save()
                    complete()
                } catch (e: Exception) {
                    message.value = "Note could not be saved."
                }
            }
        }
    }

    suspend fun flushWrites() {
        gate.withLock {
            val saves = pendingWrites.values.toList()
            pendingWrites.clear()
            saves.forEach { it() }
        }
    }

    private var generation = 0
    private var lastInteraction = android.os.SystemClock.elapsedRealtime()
    var playing = false
    val pages =
        combine(unlocked, query.debounce(250)) { open, q -> open to q }
            .flatMapLatest { (open, q) ->
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
                            if (tokens.isEmpty()) store.dao.pages()
                            else
                                store.dao.search(
                                    tokens.joinToString(" AND ") {
                                        "\"${it.replace("\"", "\"\"")}\"*"
                                    }
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
                    val saves = pendingWrites.values.toList()
                    pendingWrites.clear()
                    saves.forEach { it() }
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
        screen.value = null
    }

    fun pin(id: String) = runWork { store.dao.togglePin(id) }

    fun lock() {
        if (!unlocked.value && !busy.value) return
        generation++
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
        attachmentPicker.value = null
        dismissRecovery()
        // Revoke readers synchronously before asynchronous database cleanup.
        store.revoke()
        previews.close()
        val saves = pendingWrites.values.toList()
        pendingWrites.clear()
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
        lockCallbacks.toList().forEach { runCatching { it() } }
        lockCallbacks.clear()
        previews.close()
        val saves = pendingWrites.values.toList()
        pendingWrites.clear()
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
