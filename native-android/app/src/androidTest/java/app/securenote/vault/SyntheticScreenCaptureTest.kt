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
        var visible by mutableStateOf(true)
        try {
            rule.setContent {
                val current = LocalContext.current
                val configured =
                    remember(current, font) {
                        current.createConfigurationContext(
                            Configuration(current.resources.configuration).apply {
                                fontScale = font
                            }
                        )
                    }
                val density = LocalDensity.current
                if (visible)
                    CompositionLocalProvider(
                        LocalContext provides configured,
                        LocalDensity provides Density(density.density, font),
                    ) {
                        PaperTheme { VaultApp(model) }
                    }
            }
            rule.waitUntil(15000) {
                rule
                    .onAllNodesWithText("A weekend away", substring = false)
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
            save(prefix + "-notes")
            rule.runOnIdle { model.openFolder(null) }
            rule.waitUntil(15000) {
                rule
                    .onAllNodesWithText("Travel documents", substring = false)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            save(prefix + "-folders")
        } finally {
            rule.runOnIdle { visible = false }
            rule.waitForIdle()
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
    }
}
