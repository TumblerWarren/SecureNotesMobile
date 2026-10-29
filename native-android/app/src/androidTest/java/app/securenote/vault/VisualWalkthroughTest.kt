package app.securenote.vault

import android.app.Application
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Records the real app UI using disposable, synthetic data. Never opens the user's vault. */
class VisualWalkthroughTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val context
        get() = instrumentation.targetContext

    private var generation by mutableIntStateOf(0)
    private lateinit var model: VaultModel
    private lateinit var store: VaultStore
    private lateinit var prefix: String
    private val failures = mutableListOf<String>()
    private val manifest = StringBuilder()
    private var ordinal = 0
    private var recording: ParcelFileDescriptor? = null
    private var recordStarted = 0L

    @Test fun portraitWalkthrough() = tour("portrait", 1f, false)

    @Test fun largeTextWalkthrough() = tour("large", 2f, false)

    @Test fun landscapeWalkthrough() = tour("landscape", 1f, true)

    private fun tour(name: String, font: Float, landscape: Boolean) {
        prefix = name
        shell("settings put system font_scale $font")
        Thread.sleep(1200)
        instrumentation.waitForIdleSync()
        rule.activity.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        Thread.sleep(1200)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            // Match MainActivity's layout-affecting window settings; only screenshot blocking
            // differs because this Activity displays synthetic fixtures exclusively.
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false)
            rule.activity.window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            androidx.core.view.WindowCompat.getInsetsController(
                    rule.activity.window,
                    rule.activity.window.decorView,
                )
                .apply {
                    isAppearanceLightStatusBars = true
                    isAppearanceLightNavigationBars = true
                }
        }
        val directory = "test-walkthrough-${UUID.randomUUID()}"
        store = VaultStore(context, directory)
        val ids = runBlocking { fixtures() }
        instrumentation.runOnMainSync {
            model = VaultModel(context.applicationContext as Application, store)
            model.unlocked.value = true
            model.showLibrary("all")
            model.useGrid.value = false
        }
        check(kotlin.math.abs(rule.activity.resources.configuration.fontScale - font) < 0.05f) {
            "System font scale was not applied: ${rule.activity.resources.configuration.fontScale}, expected $font"
        }
        try {
            rule.setContent { PaperTheme { key(generation) { VaultApp(model) } } }
            waitTag("vault-library")
            shell("mkdir -p /sdcard/Movies/SecureNoteSyntheticTests")
            recordStarted = android.os.SystemClock.elapsedRealtime()
            recording =
                instrumentation.uiAutomation.executeShellCommand(
                    "screenrecord --time-limit 175 --bit-rate 1500000 /sdcard/Movies/SecureNoteSyntheticTests/$prefix-tour.mp4"
                )
            step("library-top") {
                home()
                save("library-top")
            }
            step("file-type-overlay") {
                home()
                rule.onNodeWithText("Others", substring = false).assertIsDisplayed()
                tool("Other file types").performClick()
                save("file-types-overlay")
                rule.onNodeWithTag("sheet-scrim").performTouchInput {
                    click(androidx.compose.ui.geometry.Offset(8f, 8f))
                }
                rule.onNodeWithTag("sheet-scrim").assertDoesNotExist()
                tool("Other file types").performClick()
                rule.onNodeWithTag("file-type-epub").performScrollTo().performClick()
                rule.runOnIdle { assertTrue(model.category.value == "epub") }
                save("file-type-active")
                tool("Other file types").performClick()
                rule.onNodeWithTag("file-type-all").performScrollTo().performClick()
                rule.runOnIdle { assertTrue(model.category.value == "all") }
            }
            step("library-scrolled") {
                rule.onNodeWithTag("vault-library").performTouchInput { swipeUp() }
                save("library-scrolled")
            }
            step("library-grid") {
                home()
                rule.runOnIdle { model.useGrid.value = true }
                save("library-grid")
                rule
                    .onNodeWithTag("vault-library")
                    .performScrollToNode(hasTestTag("entry-${ids.getValue("note")}"))
                rule.onNodeWithTag("entry-${ids.getValue("note")}").performTouchInput {
                    longClick()
                }
                waitText("1 selected")
                rule.onNodeWithTag("select-${ids.getValue("note")}").assertIsOn()
                save("long-press-selection")
            }
            step("sort-menu") {
                home()
                tool("Sort").performClick()
                save("sort-menu")
            }
            step("selection") {
                home()
                tool("Select", "Select items").performClick()
                rule
                    .onNodeWithTag("vault-library")
                    .performScrollToNode(hasTestTag("select-${ids.getValue("audio")}"))
                rule
                    .onNodeWithTag("select-${ids.getValue("audio")}")
                    .performScrollTo()
                    .performClick()
                    .assertIsOn()
                waitText("1 selected")
                rule.onNodeWithTag("vault-library").performScrollToIndex(0)
                save("selection")
                rule.onNodeWithText("Move", substring = false).performClick()
                waitText("Move to folder")
                save("move-picker")
            }
            step("unfiled-move") {
                home()
                rule.onNodeWithTag("nav-folders").performClick()
                if (landscape) {
                    tool("Sort").performClick()
                    rule.waitUntil(15000) {
                        rule
                            .onAllNodes(
                                hasText("Organize unfiled", substring = false) and isEnabled()
                            )
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }
                    rule
                        .onNodeWithText("Organize unfiled", substring = false)
                        .performScrollTo()
                        .performClick()
                } else {
                    waitText("16 items", true)
                    rule
                        .onNodeWithTag("vault-library")
                        .performScrollToNode(hasText("Organize", substring = false))
                    rule.onNodeWithText("Organize", substring = false).performClick()
                }
                rule
                    .onNodeWithTag("vault-library")
                    .performScrollToNode(hasTestTag("select-${ids.getValue("audio")}"))
                rule.onNodeWithTag("select-${ids.getValue("audio")}").performClick()
                rule.onNodeWithText("Move", substring = false).performClick()
                rule
                    .onNodeWithTag("folder-destinations")
                    .performScrollToNode(hasTestTag("folder-destination-work"))
                rule.onNodeWithTag("folder-destination-work").performClick()
                waitText("Folders / Work")
                rule.onNodeWithText("Move here", substring = false).performClick()
                rule.waitUntil(15000) {
                    runBlocking { ids.getValue("audio") in store.dao.members("work") }
                }
                save("unfiled-organized")
            }
            step("bulk-delete-long-press") {
                home()
                val disposable = runBlocking {
                    listOf(store.newNote(), store.newNote()).also { notes ->
                        notes.forEachIndexed { index, note ->
                            store.dao.rename(note, "Disposable selection $index")
                        }
                    }
                }
                rule
                    .onNodeWithTag("vault-library")
                    .performScrollToNode(hasTestTag("entry-${disposable[0]}"))
                rule.onNodeWithTag("entry-${disposable[0]}").performTouchInput { longClick() }
                waitText("1 selected")
                rule
                    .onNodeWithTag("vault-library")
                    .performScrollToNode(hasTestTag("select-${disposable[1]}"))
                rule.onNodeWithTag("select-${disposable[1]}").performClick()
                waitText("2 selected")
                rule.onNodeWithText("Delete", substring = false).performClick()
                waitText("Delete permanently?")
                save("bulk-delete-confirmation")
                rule.onNodeWithTag("confirm-delete").performClick()
                rule.waitUntil(15000) {
                    runBlocking { disposable.all { store.dao.find(it) == null } }
                }
            }
            step("group-by-type") {
                home()
                tool("Select", "Select items").performClick()
                rule
                    .onNodeWithTag("vault-library")
                    .performScrollToNode(hasTestTag("select-${ids.getValue("audio")}"))
                rule
                    .onNodeWithTag("select-${ids.getValue("audio")}")
                    .performScrollTo()
                    .performClick()
                    .assertIsOn()
                waitText("1 selected")
                rule.onNodeWithTag("vault-library").performScrollToIndex(0)
                rule.onNodeWithText("More", substring = false).performClick()
                rule.onNodeWithText("By type", substring = false).performScrollTo().performClick()
                waitText("Arrange by type?")
                save("group-confirmation")
            }
            step("add-dialog") {
                home()
                rule.onNodeWithTag("add-item").performClick()
                save("add-dialog")
                rule.onNodeWithTag("sheet-scrim").performTouchInput {
                    click(androidx.compose.ui.geometry.Offset(8f, 8f))
                }
                rule.onNodeWithText("Add item", substring = false).assertDoesNotExist()
            }
            step("search-keyboard") {
                home()
                rule.onNodeWithTag("vault-search").performClick().performTextInput("weekend")
                save("search-keyboard")
            }
            step("no-results") {
                home()
                rule.runOnIdle { model.query.value = "no matching synthetic title" }
                save("no-results")
            }
            step("settings") {
                home()
                val footerTop =
                    rule.onNodeWithTag("nav-all").fetchSemanticsNode().boundsInWindow.top
                rule.onNodeWithTag("nav-settings").performClick()
                rule.onNodeWithTag("settings-header").assertIsDisplayed()
                assertTrue(
                    "Settings must retain the shared footer position",
                    kotlin.math.abs(
                        rule.onNodeWithTag("nav-all").fetchSemanticsNode().boundsInWindow.top -
                            footerTop
                    ) < 2f,
                )
                rule.onNodeWithTag("nav-settings").assertIsDisplayed().assertIsSelected()
                rule.onNodeWithTag("nav-all").assertIsDisplayed().assertIsNotSelected()
                rule.onNodeWithTag("nav-notes").assertIsDisplayed()
                rule.onNodeWithTag("nav-folders").assertIsDisplayed()
                save("settings-top")
                rule.onNodeWithText("On this device", substring = false).performScrollTo()
                save("settings-bottom")
                rule.onNodeWithTag("nav-notes").performClick()
                rule.onNodeWithTag("nav-notes").assertIsSelected()
                rule.onNodeWithTag("vault-library").assertIsDisplayed()
                rule.onNodeWithTag("nav-settings").performClick()
                rule.onNodeWithTag("nav-settings").assertIsSelected()
                rule.onNodeWithTag("settings-header").assertIsDisplayed()
            }
            step("password-form") {
                home()
                rule.onNodeWithTag("nav-settings").performClick()
                rule
                    .onNodeWithText("Change password", substring = false)
                    .performScrollTo()
                    .performClick()
                rule.onNodeWithTag("nav-settings").assertIsDisplayed().assertIsSelected()
                save("password-form")
                rule.onNodeWithText("Current password", substring = false).performClick()
                save("password-keyboard")
                var previousField = "Current password"
                listOf("New password", "Confirm password").forEach { label ->
                    rule.onNodeWithText(previousField, substring = false).performImeAction()
                    rule.onNodeWithText(label, substring = false).assertIsFocused()
                    previousField = label
                    save(
                        if (label == "New password") "new-password-keyboard"
                        else "confirm-password-keyboard"
                    )
                }
            }
            step("folders") {
                home()
                rule.onNodeWithTag("nav-folders").performClick()
                waitText("Travel documents")
                save("folders-top")
                rule.onNodeWithText("Travel documents", substring = false).performClick()
                save("folder-contents")
            }
            step("folder-dialogs") {
                home()
                rule.onNodeWithTag("nav-folders").performClick()
                waitText("Travel documents")
                rule.onAllNodesWithContentDescription("Folder options").onLast().performClick()
                save("folder-menu")
                rule.onNodeWithText("Color", substring = false).performClick()
                save("folder-colors")
            }
            step("new-folder") {
                home()
                rule.onNodeWithTag("nav-folders").performClick()
                rule.onNodeWithContentDescription("Sort", substring = false).performClick()
                rule.onNodeWithText("New folder", substring = false).performClick()
                save("new-folder")
            }
            step("notes") {
                open(ids.getValue("note"))
                waitTag("native-note-body")
                save("note-reading")
                rule.onAllNodesWithTag("native-note-body").onFirst().performTouchInput { click() }
                save("note-keyboard")
                hideKeyboard()
                rule.runOnIdle {
                    val edit = checkNotNull(findEditor(rule.activity.window.decorView))
                    edit.showSoftInputOnFocus = false
                    edit.requestFocus()
                    edit.setSelection(0, minOf(12, edit.length()))
                    edit.toggle("bold")
                }
                save("note-bold-selected")
                tool("Insert").performClick()
                save("note-insert-menu")
            }
            step("note-block-controls") {
                open(ids.getValue("note"))
                waitTag("native-note-body")
                tool("Insert").performClick()
                rule.onNodeWithText("Manage blocks", substring = false).performClick()
                rule.onAllNodesWithContentDescription("Block options").onFirst().performClick()
                save("note-block-menu")
            }
            step("table") {
                open(ids.getValue("table"))
                waitDescription("Add row")
                save("table")
                rule
                    .onNodeWithContentDescription("Add column", substring = false)
                    .performScrollTo()
                    .performClick()
                waitDescription("Cell 1, 3")
                // In landscape the Add column button is below the first row; scroll back
                // vertically before checking that row. Portrait still checks auto-reveal.
                if (landscape) {
                    rule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
                    rule
                        .onNodeWithContentDescription("Cell 1, 3", substring = false)
                        .performScrollTo()
                }
                rule
                    .onNodeWithContentDescription("Cell 1, 3", substring = false)
                    .assertIsDisplayed()
                save("table-wide")
            }
            step("drawing") {
                open(ids.getValue("drawing"))
                waitText("Edit drawing")
                save("drawing-preview")
                rule
                    .onNodeWithText("Edit drawing", substring = false)
                    .performScrollTo()
                    .performClick()
                save("drawing-editor")
                rule.onNodeWithContentDescription("Note drawing canvas").performTouchInput {
                    swipe(
                        androidx.compose.ui.geometry.Offset(width * .2f, height * .3f),
                        androidx.compose.ui.geometry.Offset(width * .8f, height * .7f),
                        400,
                    )
                }
                rule
                    .onAllNodesWithText("Stroke limit reached", substring = true)
                    .assertCountEquals(0)
                save("drawing-stroke")
                rule
                    .onNodeWithContentDescription("Color & stroke", substring = false)
                    .performClick()
                rule
                    .onNodeWithContentDescription("Pen opacity", substring = false)
                    .performScrollTo()
                    .assertIsDisplayed()
                save("drawing-properties")
                rule
                    .onNodeWithContentDescription(
                        if (landscape) "Close" else "Hide properties",
                        substring = false,
                    )
                    .performClick()
                rule
                    .onNodeWithContentDescription("Navigate", substring = false)
                    .performClick()
                    .assertIsSelected()
                val canvasNode =
                    rule.onNodeWithContentDescription("Note drawing canvas", substring = false)
                allowSyntheticCaptures()
                val beforePan =
                    if (landscape) canvasNode.captureToImage().asAndroidBitmap() else null
                canvasNode.performTouchInput {
                    swipe(
                        center,
                        center - androidx.compose.ui.geometry.Offset(0f, height * .4f),
                        400,
                    )
                }
                rule.waitForIdle()
                if (beforePan != null) {
                    val afterPan = canvasNode.captureToImage().asAndroidBitmap()
                    try {
                        org.junit.Assert.assertFalse(
                            "Navigate must pan a tall page at 1x in landscape",
                            beforePan.sameAs(afterPan),
                        )
                    } finally {
                        beforePan.recycle()
                        afterPan.recycle()
                    }
                }
                save("drawing-navigate")
            }
            step("pdf") {
                open(ids.getValue("pdf"))
                waitDescription("Page 1")
                save("pdf-page")
                rule.onNodeWithContentDescription("Next page", substring = false).performClick()
                save("pdf-next-page")
                rule.onNodeWithContentDescription("Pages", substring = false).performClick()
                save("pdf-thumbnails")
                rule.onNodeWithContentDescription("Close", substring = false).performClick()
                rule.onNodeWithContentDescription("Go to page", substring = true).performClick()
                save("pdf-jump")
            }
            step("pdf-zoom") {
                open(ids.getValue("pdf"))
                rule.waitUntil(15000) {
                    rule
                        .onAllNodes(
                            hasContentDescription("Page ", substring = true) and hasClickAction()
                        )
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                rule
                    .onAllNodes(
                        hasContentDescription("Page ", substring = true) and hasClickAction()
                    )
                    .onFirst()
                    .performClick()
                waitDescription("Fit width")
                save("pdf-zoom")
                rule.onNodeWithContentDescription("Zoom in", substring = false).performClick()
                save("pdf-zoomed")
            }
            step("image") {
                open(ids.getValue("image"))
                waitDescription("Fit")
                save("image")
                rule.onNodeWithContentDescription("Zoom in", substring = false).performClick()
                save("image-zoom")
            }
            step("epub") {
                open(ids.getValue("epub"))
                waitTag("epub-content")
                save("epub-reading")
                rule.onNodeWithTag("epub-content").performTouchInput {
                    swipeLeft(durationMillis = 1000)
                }
                rule.waitUntil(5000) {
                    var offset = 0
                    instrumentation.runOnMainSync {
                        offset =
                            rule.activity.window.decorView
                                .findViewWithTag<EpubScrollView>("epub-scroll")
                                ?.scrollY ?: 0
                    }
                    offset > 0
                }
                rule.onNodeWithText("Chapter 1 / 2", substring = false).assertExists()
                save("epub-swiped")
                rule.onNodeWithContentDescription("Contents", substring = false).performClick()
                save("epub-contents")
            }
            step("text") {
                open(ids.getValue("text"))
                waitText("1 /", true)
                save("text-reader")
            }
            step("audio") {
                open(ids.getValue("audio"))
                Thread.sleep(1200)
                save("audio-player")
                rule
                    .onNodeWithContentDescription("Play", substring = false)
                    .performScrollTo()
                    .performClick()
                rule.waitUntil(15000) { model.playing }
                rule.onNodeWithTag("media-error").assertDoesNotExist()
                save("audio-playing")
                rule
                    .onNodeWithContentDescription("Queue", substring = false)
                    .performScrollTo()
                    .performClick()
                waitText("Queue")
                save("audio-queue")
                rule.onNodeWithContentDescription("Close", substring = false).performClick()
                rule
                    .onNodeWithContentDescription("Playback settings", substring = false)
                    .performScrollTo()
                    .performClick()
                waitText("Volume")
                save("audio-settings")
                rule.onNodeWithContentDescription("Close", substring = false).performClick()
                rule
                    .onNodeWithContentDescription("Sleep timer", substring = false)
                    .performScrollTo()
                    .performClick()
                waitText("15 minutes")
                save("audio-timer")
                rule.onNodeWithContentDescription("Close", substring = false).performClick()
            }
            step("video") {
                open(ids.getValue("video"))
                Thread.sleep(1200)
                save("video-player")
                rule
                    .onNodeWithContentDescription("Play", substring = false)
                    .performScrollTo()
                    .performClick()
                rule.waitUntil(15000) { model.playing }
                rule.onNodeWithTag("media-error").assertDoesNotExist()
                save("video-playing")
                rule.waitUntil(7000) {
                    rule.onAllNodesWithTag("media-controls").fetchSemanticsNodes().isEmpty()
                }
                save("video-controls-hidden")
                rule.onNodeWithTag("video-picture").performClick()
                rule.onNodeWithTag("media-controls").assertExists()
                rule
                    .onNodeWithContentDescription("Playback settings", substring = false)
                    .performScrollTo()
                    .performClick()
                waitText("Captions")
                save("video-settings")
                rule.onNodeWithContentDescription("Close", substring = false).performClick()
                val initialVideoOrientation = rule.activity.resources.configuration.orientation
                rule
                    .onNodeWithContentDescription("Rotate video", substring = false)
                    .performScrollTo()
                    .performClick()
                rule.waitUntil(15000) {
                    rule.activity.resources.configuration.orientation != initialVideoOrientation
                }
                rule.runOnIdle {
                    assertTrue("Rotation must keep the vault unlocked", model.unlocked.value)
                }
                rule.waitUntil(15000) { model.playing }
                save("video-rotated")
                if (rule.onAllNodesWithTag("media-controls").fetchSemanticsNodes().isEmpty())
                    rule.onNodeWithTag("video-picture").performClick()
                rule
                    .onNodeWithContentDescription("Rotate video", substring = false)
                    .performScrollTo()
                    .performClick()
                rule.waitUntil(15000) {
                    rule.activity.resources.configuration.orientation == initialVideoOrientation
                }
                rule
                    .onNodeWithContentDescription("Full screen", substring = false)
                    .performScrollTo()
                    .performClick()
                waitDescription("Close full screen")
                save("video-fullscreen")
                rule.waitUntil(7000) {
                    rule.onAllNodesWithTag("media-controls").fetchSemanticsNodes().isEmpty()
                }
                rule.onNodeWithTag("video-picture").performClick()
                rule
                    .onNodeWithContentDescription("Close full screen", substring = false)
                    .performClick()
            }
            step("unsupported") {
                open(ids.getValue("document"))
                waitText("Internal preview", true)
                save("unsupported-document")
            }
            step("delete-dialog") {
                open(ids.getValue("note"))
                waitTag("native-note-body")
                rule.onNodeWithContentDescription("Item options").performClick()
                rule.onNodeWithText("Delete", substring = false).performClick()
                save("delete-confirmation")
            }
            step("legacy-password") {
                home()
                rule.runOnIdle {
                    model.legacySource.value =
                        android.net.Uri.parse("content://app.securenote.syntheticfolders/unused")
                }
                waitText("Import old notes")
                save("legacy-password")
                rule.runOnIdle { model.legacySource.value = null }
            }
            step("recovery-key-display") {
                home()
                rule.runOnIdle { model.recovery.value = ByteArray(32) }
                waitText("Record your recovery key")
                save("recovery-key-display")
                rule.runOnIdle { model.recovery.value = null }
            }
            step("lock-screen") {
                home()
                rule.runOnIdle { model.lock() }
                rule.onNodeWithTag("welcome-header").assertIsDisplayed()
                val welcomeHeaderTop =
                    rule.onNodeWithTag("welcome-header").fetchSemanticsNode().boundsInWindow.top
                save("lock-screen")
                rule
                    .onNodeWithTag("vault-password")
                    .performScrollTo()
                    .performClick()
                    .assertIsFocused()
                rule.onNodeWithTag("welcome-header").assertIsDisplayed()
                val keyboardHeaderTop =
                    rule.onNodeWithTag("welcome-header").fetchSemanticsNode().boundsInWindow.top
                assertTrue(
                    "Welcome header must stay pinned above the keyboard",
                    kotlin.math.abs(keyboardHeaderTop - welcomeHeaderTop) < 2f,
                )
                rule.onNodeWithText("Welcome back.", substring = false).assertExists()
                if (!landscape && font == 1f)
                    rule.onNodeWithText("Welcome back.", substring = false).assertIsDisplayed()
                save("vault-password-keyboard")
                hideKeyboard()
                rule
                    .onNodeWithText("Use recovery key", substring = false)
                    .performScrollTo()
                    .performClick()
                save("recovery-form")
                rule
                    .onNodeWithText("Recovery key", substring = false)
                    .performScrollTo()
                    .performClick()
                    .assertIsFocused()
                save("recovery-key-field-keyboard")
                rule.onNodeWithText("Recovery key", substring = false).performImeAction()
                rule.onNodeWithText("New password", substring = false).assertIsFocused()
                save("recovery-keyboard")
                rule.onNodeWithText("New password", substring = false).performImeAction()
                rule.onNodeWithText("Confirm password", substring = false).assertIsFocused()
                save("recovery-confirm-keyboard")
            }
        } finally {
            shell("pkill -2 screenrecord")
            Thread.sleep(1200)
            recording?.close()
            writeFile("$prefix-index.txt", "text/plain", manifest.toString().toByteArray())
            writeFile(
                "$prefix-failures.txt",
                "text/plain",
                failures.joinToString("\n").toByteArray(),
            )
            rule.activityRule.scenario.close()
            shell("settings put system font_scale 1.0")
            runBlocking { model.flushWrites() }
            store.close()
            instrumentation.runOnMainSync {
                androidx.lifecycle.ViewModelStore().apply {
                    put("tour", model)
                    clear()
                }
            }
            File(context.noBackupFilesDir, directory).deleteRecursively()
        }
        assertTrue("Incomplete walkthrough states: ${failures.joinToString()}", failures.isEmpty())
    }

    private fun home() {
        hideKeyboard()
        rule.runOnIdle {
            model.message.value = ""
            model.legacySource.value = null
            model.recovery.value = null
            model.query.value = ""
            model.folderBrowsing.value = false
            model.folderId.value = null
            model.showLibrary("all")
            model.useGrid.value = false
            generation++
        }
        waitTag("vault-library")
    }

    private fun open(id: String) {
        home()
        rule.runOnIdle { model.navigate(id) }
        rule.waitForIdle()
    }

    private fun tool(text: String, description: String = text): SemanticsNodeInteraction {
        if (text in listOf("Select", "Grid view", "List view"))
            rule.onNodeWithContentDescription("Sort", substring = false).performClick()
        return if (
            rule
                .onAllNodesWithContentDescription(description, substring = false)
                .fetchSemanticsNodes()
                .isNotEmpty()
        )
            rule.onNodeWithContentDescription(description, substring = false)
        else rule.onNodeWithText(text, substring = false)
    }

    private fun waitTag(tag: String) =
        rule.waitUntil(15000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun waitText(text: String, substring: Boolean = false) =
        rule.waitUntil(15000) {
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }

    private fun waitDescription(text: String) =
        rule.waitUntil(15000) {
            rule
                .onAllNodesWithContentDescription(text, substring = false)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

    private fun step(name: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            failures += "$name: ${e.message}"
            println("WALKTHROUGH_FAILURE $prefix/$name: ${e.stackTraceToString()}")
            runCatching { save("FAILED-$name") }
        }
    }

    private fun hideKeyboard() {
        instrumentation.runOnMainSync {
            val view = rule.activity.window.decorView
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
            view.clearFocus()
        }
    }

    private fun findEditor(view: View): NoteEditText? {
        if (view is NoteEditText) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) findEditor(view.getChildAt(i))?.let {
                return it
            }
        return null
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun allowSyntheticCaptures() {
        instrumentation.runOnMainSync {
            fun syntheticWindows(view: View) {
                (view as? DialogWindowProvider)
                    ?.window
                    ?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                if (view is ViewGroup)
                    for (i in 0 until view.childCount) syntheticWindows(view.getChildAt(i))
            }
            WindowInspector.getGlobalWindowViews().forEach(::syntheticWindows)
        }
    }

    private fun inputViewport(): android.graphics.Rect {
        val frame = android.graphics.Rect()
        instrumentation.runOnMainSync {
            val roots = WindowInspector.getGlobalWindowViews()
            val active = roots.lastOrNull { it.hasWindowFocus() } ?: roots.last { it.isShown }
            active.getWindowVisibleDisplayFrame(frame)
        }
        return frame
    }

    private fun save(name: String) {
        rule.waitForIdle()
        // Test-APK only: allow synthetic dialog windows to appear in recordings. Production
        // MainActivity and its security flags are never opened or altered by this harness.
        allowSyntheticCaptures()
        Thread.sleep(650)
        if (name.contains("keyboard") && (name.contains("password") || name.contains("recovery"))) {
            val inputs = rule.onAllNodes(isFocused())
            inputs.onFirst().assertIsDisplayed()
            val focused = inputs.fetchSemanticsNodes().firstOrNull()
            checkNotNull(focused) { "No focused input in $name" }
            val inputBottom = focused.positionInWindow.y + focused.size.height
            val frame = inputViewport()
            assertTrue(
                "Focused input in $name extends to ${inputBottom}, keyboard starts at ${frame.bottom}",
                inputBottom <= frame.bottom + 2,
            )
        }
        if (name == "search-keyboard")
            rule.onNodeWithTag("vault-search").assertIsFocused().assertIsDisplayed()
        if (prefix == "landscape" && name in listOf("search-keyboard", "note-keyboard")) {
            val control =
                if (name == "search-keyboard") rule.onNodeWithTag("vault-search")
                else rule.onNodeWithContentDescription("Bold", substring = false)
            control.assertIsDisplayed()
            val node = control.fetchSemanticsNode()
            val bottom = node.positionInWindow.y + node.size.height
            assertTrue(
                "$name control extends to $bottom behind keyboard at ${inputViewport().bottom}",
                bottom <= inputViewport().bottom + 2,
            )
            if (name == "search-keyboard") rule.onNodeWithTag("nav-folders").assertDoesNotExist()
            else {
                rule.onNodeWithContentDescription("Back", substring = false).assertDoesNotExist()
                val editor = rule.onAllNodesWithTag("native-note-body").onFirst()
                editor.assertIsDisplayed()
                assertTrue(
                    "Notes need a visible writing line above the keyboard",
                    editor.fetchSemanticsNode().boundsInWindow.height >= 16,
                )
            }
        }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val file = "$prefix-${(++ordinal).toString().padStart(2, '0')}-$name"
        val bytes =
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        writeFile("$file.png", "image/png", bytes)
        writeFile(
            "$file.txt",
            "text/plain",
            rule.onAllNodes(isRoot()).printToString().toByteArray(),
        )
        manifest.append(
            "$file\t${bitmap.width}x${bitmap.height}\t${(android.os.SystemClock.elapsedRealtime() - recordStarted) / 1000.0}\n"
        )
        bitmap.recycle()
        println("WALKTHROUGH_CAPTURE=$file")
    }

    private fun writeFile(name: String, mime: String, bytes: ByteArray) {
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "Download/SecureNoteSyntheticWalkthrough",
                )
            }
        val uri =
            checkNotNull(
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            )
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
    }

    private suspend fun fixtures(): Map<String, String> {
        store.create("Disposable synthetic walkthrough passphrase".toByteArray()).fill(0)
        store.dao.saveFolder(VaultFolder("travel", null, "Travel documents", "travel documents"))
        store.dao.saveFolder(VaultFolder("work", null, "Work", "work"))
        store.dao.saveFolder(
            VaultFolder("tickets", "travel", "Tickets & confirmations", "tickets & confirmations")
        )
        val ids = mutableMapOf<String, String>()
        suspend fun note(
            key: String,
            title: String,
            kind: String = "text",
            content: String = "",
        ): String {
            val id = store.newNote()
            store.dao.rename(id, title)
            store.ensureBlocks(id)
            val first = store.dao.blocks(id).single()
            store.dao.saveBlock(
                first.copy(
                    kind = kind,
                    content = content,
                    searchText =
                        if (kind == "text") "Weekend tickets, packing list and reading ideas."
                        else "",
                )
            )
            ids[key] = id
            return id
        }
        val note =
            note(
                "note",
                "A weekend away",
                content = "<p>Train tickets, a camera and a quiet place to read.</p>",
            )
        store.addBlock(
            note,
            "text",
            "<p>Remember the booking confirmation and a spare charger.</p>",
        )
        store.addBlock(
            note,
            "text",
            "<p>A short checklist should fit naturally without a large gap between paragraphs.</p>",
        )
        store.dao.togglePin(note)
        note(
            "table",
            "Project checklist",
            "table",
            "[[\"Task\",\"Status\"],[\"Pack camera\",\"Ready\"],[\"Book train\",\"Tomorrow\"]]",
        )
        note("drawing", "Sketchbook", "drawing", "[]")
        repeat(7) {
            note(
                "extra$it",
                listOf(
                    "Reading list",
                    "Important details",
                    "Ideas for next week",
                    "Shopping",
                    "Home projects",
                    "Meeting notes",
                    "A long note title that needs sensible wrapping",
                )[it],
                content = "<p>A few useful details saved privately for later.</p>",
            )
        }
        val pdf = android.graphics.pdf.PdfDocument()
        val pdfBytes = ByteArrayOutputStream()
        repeat(3) { pageIndex ->
            val page =
                pdf.startPage(
                    android.graphics.pdf.PdfDocument.PageInfo.Builder(420, 594, pageIndex + 1)
                        .create()
                )
            page.canvas.drawColor(Color.WHITE)
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.DKGRAY
                    textSize = 23f
                }
            page.canvas.drawText("A weekend away", 28f, 48f, paint)
            paint.textSize = 14f
            repeat(17) {
                page.canvas.drawText(
                    "Day ${it + 1}: A place to explore and remember.",
                    28f,
                    90f + it * 25,
                    paint,
                )
            }
            pdf.finishPage(page)
        }
        pdf.writeTo(pdfBytes)
        pdf.close()
        ids["pdf"] =
            store.importBytes("Weekend itinerary.pdf", "application/pdf", pdfBytes.toByteArray())
        val image = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.rgb(206, 222, 216))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(69, 105, 89) }
            drawCircle(400f, 400f, 220f, paint)
            paint.color = Color.WHITE
            paint.textSize = 55f
            drawText("Weekend", 270f, 420f, paint)
        }
        val imageBytes =
            ByteArrayOutputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        image.recycle()
        ids["image"] = store.importBytes("A quiet afternoon.png", "image/png", imageBytes)
        ids["epub"] = store.importBytes("A small book.epub", "application/epub+zip", epub())
        ids["text"] =
            store.importBytes(
                "Private reading.txt",
                "text/plain",
                ("Private reading\n\n" +
                        "A paragraph should make good use of the available width.\n\n".repeat(40))
                    .toByteArray(),
            )
        ids["document"] =
            store.importBytes(
                "Archive document.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "Synthetic unsupported document".toByteArray(),
            )
        for ((key, file, mime) in
            listOf(
                Triple("audio", "visual-audio.wav", "audio/wav"),
                Triple("video", "visual-video.mp4", "video/mp4"),
            )) {
            ids[key] =
                store.importBytes(
                    file,
                    mime,
                    instrumentation.context.assets.open(file).use { it.readBytes() },
                )
        }
        store.dao.moveItems(listOf(ids.getValue("pdf")), "travel")
        return ids
    }

    private fun epub(): ByteArray =
        ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                linkedMapOf(
                        "mimetype" to "application/epub+zip",
                        "META-INF/container.xml" to
                            """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
                        "OPS/book.opf" to
                            """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">synthetic</dc:identifier><dc:title>A small book</dc:title><dc:language>en</dc:language></metadata><manifest><item id="one" href="one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="two.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""",
                        "OPS/one.xhtml" to
                            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>A quiet morning</title></head><body><h1>A quiet morning</h1>" +
                                "<p>There is time to read, to think, and to find a quiet place away from the city.</p>"
                                    .repeat(12) +
                                "</body></html>",
                        "OPS/two.xhtml" to
                            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>The journey home</title></head><body><h1>The journey home</h1><p>A second chapter to test navigation.</p></body></html>",
                    )
                    .forEach { (name, text) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(text.toByteArray())
                        zip.closeEntry()
                    }
            }
            out.toByteArray()
        }
}
