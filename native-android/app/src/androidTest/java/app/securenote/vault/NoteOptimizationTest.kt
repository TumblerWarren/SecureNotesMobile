package app.securenote.vault

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NoteOptimizationTest {
    @Test
    fun liveBufferSnapshotsRemainImmutableWhenInputIsReused() {
        val live = LiveDrawingStroke()
        val size = IntSize(360, 480)
        val path = live.path
        repeat(1000) { live.add(Offset(it / 1000f, 0.5f), size, false) }
        val committed = live.snapshot()
        live.clear()
        live.add(Offset(0.1f, 0.2f), size, false)
        assertSame(path, live.path)
        assertEquals(1000, committed.size)
        assertEquals(1, live.size)
        assertEquals(Offset(0f, 0.5f), committed.first())
    }

    @Test
    fun unchangedStrokeEncodingIsReusedAndChangedStyleGetsNewPayload() {
        val encoder = DrawingEncoder()
        val first = NoteStroke(listOf(Offset(0.2f, 0.3f)), android.graphics.Color.BLUE, 4f)
        val second = NoteStroke(listOf(Offset(0.6f, 0.7f)), android.graphics.Color.RED, 5f)
        val (_, a) = encoder.prepare("block", listOf(first), 1f, "Blank")
        val (_, b) = encoder.prepare("block", listOf(first, second), 1f, "Grid")
        assertSame(a.single(), b.first())
        val (changedManifest, c) =
            encoder.prepare(
                "block",
                listOf(first.copy(color = android.graphics.Color.GREEN), second),
                1f,
                "Grid",
            )
        assertNotEquals(a.single().digest, c.first().digest)
        val (previousManifest, _) =
            DrawingEncoder().prepare("block", listOf(first, second), 1f, "Grid")
        assertNotEquals(previousManifest, changedManifest)
        assertSame(b.last(), c.last())
        assertEquals(first.id, NoteDrawing.decode(a.single().content).single().id)
    }

    @Test
    fun encryptedStrokeRowsSurviveReopenEraseUndoAndCascadeDeletion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "test-note-optimization-${UUID.randomUUID()}"
        val store = VaultStore(context, directory)
        val password = "Synthetic optimized notes password"
        try {
            store.create(password.toByteArray()).fill(0)
            val note = store.newNote()
            store.ensureBlocks(note)
            val text = store.dao.blocks(note).single()
            store.dao.editBlock(
                text.copy(content = "<p>Searchable text</p>", searchText = "Searchable text")
            )
            val id = store.addBlock(note, "drawing", "[]")
            val block = checkNotNull(store.dao.findBlock(id))
            val a = NoteStroke(listOf(Offset(0.2f, 0.3f)), android.graphics.Color.BLUE, 4f)
            val b = NoteStroke(listOf(Offset(0.6f, 0.7f)), android.graphics.Color.RED, 5f)
            val encoder = DrawingEncoder()
            suspend fun save(strokes: List<NoteStroke>) {
                val (manifest, rows) = encoder.prepare(id, strokes, 0.7071f, "Dots")
                store.dao.saveDrawing(block, manifest, rows)
            }
            save(listOf(a))
            val first = store.dao.drawingStrokes(id).single()
            save(listOf(a, b))
            assertEquals(first, store.dao.drawingStrokes(id).first { it.id == a.id })
            assertEquals(2, store.dao.drawingStrokes(id).size)
            assertFalse(checkNotNull(store.dao.findBlock(id)).content.contains("points"))
            assertEquals(1L, store.dao.searchCount("Searchable"))
            save(listOf(b))
            assertEquals(listOf(b.id), store.dao.drawingStrokes(id).map { it.id })
            save(listOf(a, b)) // undo reintroduces the original ID and geometry
            store.close()
            store.unlock(password.toByteArray())
            val restored =
                checkNotNull(
                    DrawingPersistence.hydrate(store.dao, checkNotNull(store.dao.findBlock(id)))
                )
            assertEquals(listOf(a, b), NoteDrawing.decode(restored.content))
            store.removeNoteBlock(checkNotNull(store.dao.findBlock(id)))
            assertTrue(store.dao.drawingStrokes(id).isEmpty())
            save(listOf(a)) // stale callback cannot recreate a deleted block's strokes
            assertTrue(store.dao.drawingStrokes(id).isEmpty())
        } finally {
            store.close()
            File(context.noBackupFilesDir, directory).deleteRecursively()
        }
    }
}
