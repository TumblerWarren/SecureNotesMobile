package app.securenote.vault

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** No production vault is opened and no production window security flags are changed. */
class SyntheticScreenCaptureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun normalSyntheticScreens() = capture(1f, "normal")

    @Test fun largeFontSyntheticScreens() = capture(2f, "large")

    private fun capture(font: Float, prefix: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "test-visual-${UUID.randomUUID()}"
        lateinit var model: VaultModel
        lateinit var note: String
        lateinit var pdf: String
        val store = VaultStore(context, directory)
        runBlocking {
            store.create("Synthetic screenshot vault password".toByteArray()).fill(0)
            store.dao.saveFolder(
                VaultFolder("travel", null, "Travel documents", "travel documents")
            )
            store.dao.saveFolder(VaultFolder("work", null, "Work", "work"))
            note = store.newNote()
            store.dao.rename(note, "A weekend away")
            store.ensureBlocks(note)
            val first = store.dao.blocks(note).single()
            store.dao.editBlock(
                first.copy(
                    content = "<p>Train tickets, a camera and a quiet place to read.</p>",
                    searchText = "Train tickets, a camera and a quiet place to read.",
                )
            )
            store.addBlock(note, "text", "<p>Remember to pack the booking confirmation.</p>")
            val document = android.graphics.pdf.PdfDocument()
            val bytes = java.io.ByteArrayOutputStream()
            try {
                for (pageNumber in 1..2) {
                    val page =
                        document.startPage(
                            android.graphics.pdf.PdfDocument.PageInfo.Builder(300, 420, pageNumber)
                                .create()
                        )
                    page.canvas.drawColor(android.graphics.Color.WHITE)
                    val paint =
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.BLACK
                            textSize = 18f
                        }
                    page.canvas.drawText("Travel itinerary", 20f, 40f, paint)
                    paint.textSize = 12f
                    repeat(12) { line ->
                        page.canvas.drawText(
                            "Day ${line+1}: A place to explore and remember.",
                            20f,
                            80f + line * 22,
                            paint,
                        )
                    }
                    document.finishPage(page)
                }
                document.writeTo(bytes)
                pdf =
                    store.importBytes(
                        "Weekend itinerary.pdf",
                        "application/pdf",
                        bytes.toByteArray(),
                    )
            } finally {
                document.close()
                bytes.close()
            }
            val another = store.newNote()
            store.dao.rename(another, "Ideas for the next project")
            val pinned = store.newNote()
            store.dao.rename(pinned, "Important details")
            store.dao.togglePin(pinned)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = VaultModel(context.applicationContext as Application, store)
            model.unlocked.value = true
        }
        val configured =
            rule.activity.createConfigurationContext(
                Configuration(rule.activity.resources.configuration).apply { fontScale = font }
            )
        val density = rule.activity.resources.displayMetrics.density
        try {
            rule.setContent {
                CompositionLocalProvider(
                    LocalContext provides configured,
                    LocalDensity provides Density(density, font),
                ) {
                    PaperTheme { VaultApp(model) }
                }
            }
            rule.waitUntil(15000) {
                rule
                    .onAllNodesWithText("4 items", substring = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            save(prefix + "-library")
            rule.runOnIdle { model.navigate(note) }
            rule.waitUntil(15000) {
                rule.onAllNodesWithTag("native-note-body").fetchSemanticsNodes().isNotEmpty()
            }
            if (font > 1.3f) rule.onNodeWithContentDescription("Insert").assertIsDisplayed()
            else rule.onNodeWithText("Insert", substring = false).assertIsDisplayed()
            rule.runOnIdle {
                fun editor(view: android.view.View): NoteEditText? {
                    if (view is NoteEditText) return view
                    if (view is android.view.ViewGroup)
                        for (i in 0 until view.childCount) editor(view.getChildAt(i))?.let {
                            return it
                        }
                    return null
                }
                val edit = checkNotNull(editor(rule.activity.window.decorView))
                edit.showSoftInputOnFocus = false
                edit.requestFocus()
                edit.setSelection(0, minOf(5, edit.length()))
                edit.toggle("bold")
            }
            if (font > 1.3f) rule.onNodeWithContentDescription("Bold").assertIsSelected()
            else rule.onNodeWithText("Bold", substring = false).assertIsSelected()
            save(prefix + "-notes")
            rule.runOnIdle { model.navigate(pdf) }
            rule.waitUntil(15000) {
                rule
                    .onAllNodesWithContentDescription("Page 1", substring = false)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            rule.onNodeWithContentDescription("Pages", substring = false).assertIsDisplayed()
            rule.onNodeWithContentDescription("Next page", substring = false).assertIsDisplayed()
            save(prefix + "-pdf")
            rule.runOnIdle { model.openFolder(null) }
            rule.waitUntil(15000) {
                rule
                    .onAllNodesWithText("Travel documents", substring = false)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            save(prefix + "-folders")
        } catch (e: Throwable) {
            println("CAPTURE_FAILURE=" + e.stackTraceToString())
            throw e
        } finally {
            rule.activityRule.scenario.close()
            runBlocking { model.flushWrites() }
            store.close()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val owner = androidx.lifecycle.ViewModelStore()
                owner.put("synthetic", model)
                owner.clear()
            }
            File(context.noBackupFilesDir, directory).deleteRecursively()
        }
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val image = rule.onRoot().captureToImage()
        assertTrue(image.width > 0 && image.height > 0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val path = File(context.cacheDir, "ui-screenshots/$name.png")
        path.parentFile!!.mkdirs()
        path.outputStream().use {
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values =
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        "Pictures/SecureNoteSyntheticTests",
                    )
                }
            val uri =
                checkNotNull(
                    context.contentResolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        values,
                    )
                )
            context.contentResolver.openOutputStream(uri)!!.use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
