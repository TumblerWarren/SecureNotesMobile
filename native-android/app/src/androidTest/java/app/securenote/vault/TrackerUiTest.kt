package app.securenote.vault

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Uses its own encrypted vault; never reads or replaces the application's real vault. */
class TrackerUiTest {
    @get:Rule val rule = createComposeRule()
    private val directory = "test-tracker-ui-${UUID.randomUUID()}"
    private val password = "Synthetic tracker UI password 123"
    private lateinit var model: VaultModel
    private lateinit var store: VaultStore
    private val visible = mutableStateOf(true)

    @Before
    fun isolatedVault() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        store = VaultStore(context, directory)
        runBlocking { store.create(password.toByteArray()).fill(0) }
        instrumentation.runOnMainSync {
            model = VaultModel(context.applicationContext as Application, store)
            model.unlocked.value = true
            model.touch()
        }
        rule.setContent { PaperTheme { if (visible.value) VaultApp(model) } }
    }

    @After
    fun cleanup() {
        if (::model.isInitialized) {
            rule.runOnIdle {
                visible.value = false
                model.lock()
                ViewModelStore().apply {
                    put("tracker-test", model)
                    clear()
                }
            }
            runBlocking { model.flushWrites() }
        }
        if (::store.isInitialized) store.close()
        File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, directory)
            .deleteRecursively()
    }

    @Test
    fun categoryChecklistAndCustomFieldsSurviveFormatEditsAndUnlock() {
        rule.onNodeWithTag("nav-trackers").performClick().assertIsSelected()
        waitForTag("tracker-screen")

        // Cancelling format selection must not create a category.
        rule.onNodeWithTag("new-tracker-category").performClick()
        rule.onNodeWithTag("tracker-category-name").performTextInput("Cancelled category")
        rule.onNodeWithText("Choose format", substring = false).performClick()
        rule.onNodeWithContentDescription("Close", substring = false).performClick()
        assertTrue(runBlocking { store.trackerDao.categories().first().isEmpty() })

        rule.onNodeWithTag("new-tracker-category").performClick()
        rule.onNodeWithTag("tracker-category-name").performTextInput("Weekend tasks")
        rule.onNodeWithText("Choose format", substring = false).performClick()
        rule.onNodeWithText("Checklist", substring = false).performScrollTo().performClick()
        rule.onNodeWithTag("save-tracker-format").performClick()
        waitUntilSaved()
        waitForText("Add your first entry with the + button.")
        val category = runBlocking { store.trackerDao.categories().first().single() }
        val doneId = TrackerFormat.decode(category.format).fields.single().id

        // A cancelled entry should not leave a draft row in encrypted storage.
        rule.onNodeWithContentDescription("Add tracker entry", substring = false).performClick()
        rule.onNodeWithTag("tracker-entry-title").performTextInput("Unsaved item")
        rule.onNodeWithContentDescription("Close", substring = false).performClick()
        assertTrue(runBlocking { store.trackerDao.entries(category.id).first().isEmpty() })

        rule.onNodeWithContentDescription("Add tracker entry", substring = false).performClick()
        rule.onNodeWithTag("tracker-entry-title").performTextInput("Read a chapter")
        rule.onNodeWithTag("save-tracker-entry").performClick()
        waitForEntrySaved()
        waitForText("Read a chapter")
        rule.onNodeWithContentDescription("Read a chapter: Done", substring = false)
            .assertIsOff().performClick()
        rule.waitUntil(15_000) {
            runBlocking {
                store.trackerDao.entries(category.id).first().singleOrNull()?.let {
                    TrackerValues.decode(it.values)[doneId] == "true"
                } == true
            }
        }
        rule.onNodeWithContentDescription("Read a chapter: Done", substring = false).assertIsOn()

        openFormat()
        rule.onNodeWithText("Add field", substring = false).performScrollTo().performClick()
        rule.onNodeWithTag("tracker-field-name").performTextInput("Details")
        rule.onNodeWithText("Save field", substring = false).performClick()
        rule.onNodeWithTag("save-tracker-format").performClick()
        waitUntilSaved()
        rule.onNodeWithText("Read a chapter", substring = false).performClick()
        rule
            .onNode(hasText("Details") and hasSetTextAction())
            .performScrollTo()
            .performTextInput("Keep this progress")
        rule.onNodeWithTag("save-tracker-entry").performClick()
        waitForEntrySaved()
        waitForText("Details: Keep this progress")

        // Rename and reorder existing fields through the editor, preserving their identities.
        openFormat()
        rule
            .onNodeWithContentDescription("Edit Done", substring = false)
            .performScrollTo()
            .performClick()
        rule.onNodeWithTag("tracker-field-name").performTextReplacement("Finished")
        rule.onNodeWithText("Save field", substring = false).performClick()
        rule.onAllNodesWithText("Move down", substring = false)[0].performScrollTo().performClick()
        rule.onNodeWithTag("save-tracker-format").performClick()
        waitUntilSaved()
        waitForText("Finished")
        val updated = runBlocking { store.trackerDao.findCategory(category.id)!! }
        val fields = TrackerFormat.decode(updated.format).fields
        assertEquals(listOf("Details", "Finished"), fields.map { it.label })
        assertEquals(doneId, fields.last().id)
        val entry = runBlocking { store.trackerDao.entries(category.id).first().single() }
        assertEquals("true", TrackerValues.decode(entry.values)[doneId])
        assertEquals("Keep this progress", TrackerValues.decode(entry.values)[fields.first().id])

        // Lock/unlock closes and reopens the encrypted vault and resets session-only navigation.
        rule.onNodeWithContentDescription("Lock", substring = false).performClick()
        waitForTag("vault-password")
        rule.onNodeWithTag("vault-password").performTextInput(password)
        rule.onNodeWithText("Unlock", substring = false).performScrollTo().performClick()
        waitForTag("nav-trackers")
        rule.onNodeWithTag("nav-trackers").performClick()
        waitForText("Weekend tasks")
        rule.onNodeWithText("Weekend tasks", substring = false).performClick()
        waitForText("Read a chapter")
        rule.onNodeWithContentDescription("Read a chapter: Finished", substring = false).assertIsOn()
        rule.onNodeWithText("Details: Keep this progress", substring = false).assertExists()
    }

    private fun openFormat() {
        rule.onNodeWithContentDescription("Category options", substring = false).performClick()
        rule.onNodeWithText("Edit format", substring = false).performClick()
        waitForTag("save-tracker-format")
    }

    private fun waitUntilSaved() {
        rule.waitUntil(15_000) {
            !model.busy.value &&
                rule.onAllNodesWithTag("save-tracker-format").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForEntrySaved() {
        rule.waitUntil(15_000) {
            !model.busy.value &&
                rule.onAllNodesWithTag("save-tracker-entry").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText(text, substring = false).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        rule.waitUntil(30_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
}
