package app.securenote.vault

import android.graphics.Typeface
import android.text.*
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NoteEditingTest {
    private fun block(id: String = "paragraph") =
        NoteBlock(id, "note", 0, "text", "<p>Original</p>")

    @Test
    fun drawingDocumentRoundTripRedoAndNewEditInvalidateRedo() {
        val drawing = NoteDrawing("[]")
        val stroke = NoteStroke(listOf(Offset(0.2f, 0.3f)), android.graphics.Color.RED, 4f)
        drawing.strokes = listOf(stroke)
        drawing.commit(emptyList())
        drawing.aspect = 0.7071f
        drawing.paper = "Grid"
        val restored = NoteDrawing(drawing.encode())
        assertTrue(restored.decoded.isSuccess)
        assertEquals(drawing.strokes, restored.strokes)
        assertEquals("Grid", restored.paper)
        assertEquals(drawing.aspect, restored.aspect, 0.0001f)
        assertTrue(drawing.undo())
        assertTrue(drawing.canRedo)
        assertTrue(drawing.redo())
        assertEquals(listOf(stroke), drawing.strokes)
        drawing.undo()
        drawing.strokes = listOf(stroke.copy(color = android.graphics.Color.BLUE))
        drawing.commit(emptyList())
        assertFalse(drawing.canRedo)
        assertFalse(drawing.redo())
        assertTrue(NoteDrawing("{\"version\":99}").decoded.isFailure)
    }

    @Test
    fun formattingPreservesEditableSelectionAndPendingDraftAcrossRecycling() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val session = NoteEditingSession()
            val first = NoteEditText(instrumentation.targetContext)
            val paragraph = block()
            first.load(session.text(paragraph))
            session.attach(first)
            first.onEdit = { session.edited(paragraph.id, it) }
            first.text.replace(0, first.length(), "Private text")
            first.setSelection(0, 7)
            val editable = first.text
            first.toggle("bold")
            assertSame(editable, first.text)
            assertEquals(0, first.selectionStart)
            assertEquals(7, first.selectionEnd)
            assertTrue(
                first.text.getSpans(0, 7, StyleSpan::class.java).any {
                    it.style and Typeface.BOLD != 0
                }
            )
            first.toggle("bold")
            assertTrue(
                first.text.getSpans(0, 7, StyleSpan::class.java).none {
                    it.style and Typeface.BOLD != 0
                }
            )
            first.toggle("italic")
            session.release(first)
            assertEquals("", first.text.toString())
            assertFalse(session.contains(first))

            // A lazy item is recreated before the old database row has been updated.
            val recreated = NoteEditText(instrumentation.targetContext)
            recreated.load(session.text(paragraph))
            session.attach(recreated)
            assertEquals("Private text", recreated.text.toString())
            assertTrue(
                recreated.text.getSpans(0, 7, StyleSpan::class.java).any {
                    it.style and Typeface.ITALIC != 0
                }
            )
            session.close()
            assertEquals("", recreated.text.toString())
            assertFalse(recreated.isEnabled)
        }
    }

    @Test
    fun selectedLinksListsAndSnapshotDoNotResetOrExportText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val edit = NoteEditText(instrumentation.targetContext)
            try {
                edit.load(SpannedString("First\nSecond"))
                edit.setSelection(0, edit.length())
                edit.bullets()
                assertEquals("• First\n• Second", edit.text.toString())
                edit.setSelection(0, edit.length())
                edit.bullets()
                assertEquals("First\nSecond", edit.text.toString())
                val revision = edit.revision
                assertTrue(edit.link("https://example.com", 0, 5, revision))
                assertEquals("First\nSecond", edit.text.toString())
                assertEquals(1, edit.text.getSpans(0, 5, URLSpan::class.java).size)
                assertFalse(edit.link("https://example.com", 6, 12, revision))
                assertFalse(NoteEditingSession.safeLink("file:///private"))
                assertFalse(NoteEditingSession.safeLink("javascript:alert(1)"))
                val transient = NoCopySpan.Concrete()
                edit.text.setSpan(transient, 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val snapshot = NoteEditingSession.snapshot(edit.text)
                assertEquals(-1, snapshot.getSpanStart(transient))
                assertFalse(edit.onTextContextMenuItem(android.R.id.copy))

                edit.load(SpannedString("• Item"))
                edit.setSelection(edit.length())
                edit.text.append("\n")
                assertEquals("• Item\n• ", edit.text.toString())
                edit.text.append("\n")
                assertEquals("• Item\n", edit.text.toString())
            } finally {
                edit.dispose()
            }
        }
    }

    @Test
    fun collapsedFormattingAndHtmlRoundTripRetainStyles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val edit = NoteEditText(instrumentation.targetContext)
            try {
                edit.load(SpannedString(""))
                edit.setSelection(0)
                edit.toggle("bold")
                edit.text.append("Bold")
                edit.setSelection(edit.length())
                edit.toggle("bold")
                edit.text.append(" plain")
                val snapshot = NoteEditingSession.snapshot(edit.text)
                val html = Html.toHtml(snapshot, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
                val restored = NoteEditingSession().text(block().copy(content = html))
                assertEquals("Bold plain", restored.toString())
                val spans = restored.getSpans(0, restored.length, StyleSpan::class.java)
                assertTrue(
                    spans.any {
                        it.style and Typeface.BOLD != 0 &&
                            restored.getSpanStart(it) == 0 &&
                            restored.getSpanEnd(it) == 4
                    }
                )
            } finally {
                edit.dispose()
            }
        }
    }

    @Test
    fun tablesAndDrawingUndoPreserveDataAndHitSparseLines() {
        val table = NoteTable.decode("[[\"One\",\"Two\"],[\"Three\"]]")
        assertEquals(listOf("Three", ""), table.rows[1])
        val changed = table.cell(1, 1, "Four").addRow().addColumn()
        assertEquals("Four", NoteTable.decode(changed.encode()).rows[1][1])
        assertTrue(runCatching { NoteTable.decode("[[{}]]") }.isFailure)
        assertTrue(runCatching { NoteDrawing.decode("[[[2,0]]]") }.isFailure)
        val stroke =
            NoteStroke(
                listOf(Offset(0.1f, 0.5f), Offset(0.9f, 0.5f)),
                android.graphics.Color.BLUE,
                4f,
            )
        assertTrue(NoteDrawing.hit(stroke, Offset(0.5f, 0.5f), IntSize(1000, 300), 10f))
        assertFalse(NoteDrawing.hit(stroke, Offset(0.5f, 0.9f), IntSize(1000, 300), 10f))
        val session = NoteEditingSession()
        val drawing = session.draft("drawing/id") { NoteDrawing("[]") }
        drawing.strokes = listOf(stroke)
        assertTrue(drawing.commit(emptyList()))
        val before = drawing.strokes
        drawing.strokes = emptyList() // erase/clear, not merely append another pen stroke
        assertTrue(drawing.commit(before))
        val recreated =
            session.draft<NoteDrawing>("drawing/id") { error("Must reuse drawing undo state") }
        assertSame(drawing, recreated)
        assertTrue(drawing.undo())
        assertEquals(listOf(stroke), drawing.strokes)
        assertEquals(listOf(stroke), NoteDrawing.decode(drawing.encode()))
        assertTrue(drawing.undo())
        assertTrue(drawing.strokes.isEmpty())
        session.close()
    }

    @Test
    fun reorderingDoesNotOverwriteEditsAndLastBlockDoesNotResurrectLegacyBody() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "test-note-editing-${UUID.randomUUID()}"
        val store = VaultStore(context, directory)
        val password = "Synthetic note persistence password"
        try {
            store.create(password.toByteArray()).fill(0)
            val id = store.newNote()
            store.dao.saveNote(id, "Private title", "<p>Legacy original</p>", 1)
            store.ensureBlocks(id)
            store.ensureBlocks(id)
            val stale = store.dao.blocks(id).single()
            val secondId = store.addBlock(id, "text", "<p>Second</p>")
            store.dao.editBlock(
                stale.copy(content = "<p>Latest edit</p>", searchText = "Latest edit")
            )
            store.transaction {
                store.dao.setBlockPosition(stale.id, 1)
                store.dao.setBlockPosition(secondId, 0)
            }
            assertEquals("<p>Latest edit</p>", store.dao.blocks(id).last().content)
            assertEquals(1L, store.dao.searchCount("Latest"))
            store.removeNoteBlock(store.dao.blocks(id).first())
            store.removeNoteBlock(store.dao.blocks(id).single())
            store.close()
            store.unlock(password.toByteArray())
            store.ensureBlocks(id)
            assertEquals("", store.dao.blocks(id).single().content)
            assertEquals("<p>Legacy original</p>", store.dao.originalBody(id))
        } finally {
            store.close()
            File(context.noBackupFilesDir, directory).deleteRecursively()
        }
    }
}
