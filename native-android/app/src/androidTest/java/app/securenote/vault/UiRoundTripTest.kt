package app.securenote.vault

import android.widget.EditText
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.hamcrest.Matchers.containsString
import org.junit.*
import org.junit.Assume.assumeFalse

class UiRoundTripTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private var created = false
    private val root
        get() =
            File(
                InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
                "vault-v1",
            )

    @Before
    fun isolated() {
        assumeFalse(
            "Never replace an existing vault during UI tests",
            File(root, "header").exists(),
        )
    }

    @After
    fun cleanup() {
        if (created) {
            rule.activityRule.scenario.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun createEditLockUnlock() {
        rule.onNodeWithTag("vault-password").performTextInput("Synthetic UI password 123")
        rule.onNodeWithTag("vault-confirmation").performTextInput("Synthetic UI password 123")
        created = true
        rule.onNodeWithText("Create vault").performScrollTo().performClick()
        try {
            rule.waitUntil(30000) {
                rule
                    .onAllNodesWithText("Record your recovery key")
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        } catch (e: Exception) {
            val model = androidx.lifecycle.ViewModelProvider(rule.activity)[VaultModel::class.java]
            throw AssertionError(
                "Creation did not reach recovery: lifecycle=${rule.activity.lifecycle.currentState}, focused=${rule.activity.hasWindowFocus()}, open=${model.unlocked.value}, busy=${model.busy.value}, exists=${model.store.exists}, message=${model.message.value}",
                e,
            )
        }
        rule.onNode(isToggleable()).performScrollTo().performClick()
        rule.onNodeWithText("Continue").performScrollTo().performClick()
        rule.onNodeWithTag("add-item").performClick()
        rule.onNodeWithText("New note", substring = false).performClick()
        rule.waitUntil(15000) {
            rule.onAllNodesWithTag("note-title").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("note-title").performTextClearance()
        rule.onNodeWithTag("note-title").performTextInput("Native round-trip note")
        rule.waitUntil(15000) {
            rule.onAllNodesWithTag("native-note-body").fetchSemanticsNodes().isNotEmpty()
        }
        onView(isAssignableFrom(EditText::class.java))
            .perform(replaceText("Private native note body"))
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitUntil(15000) {
            rule.onAllNodesWithText("Native round-trip note").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("nav-notes").performClick()
        rule.waitUntil(15000) {
            rule.onAllNodesWithText("Native round-trip note").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Videos", substring = false).performScrollTo().performClick()
        rule.waitUntil(15000) {
            rule.onAllNodesWithText("Native round-trip note").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithTag("nav-all").performClick()
        rule.waitUntil(15000) {
            rule.onAllNodesWithText("Native round-trip note").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Native round-trip note").performClick()
        rule.waitUntil(15000) {
            rule.onAllNodesWithTag("native-note-body").fetchSemanticsNodes().isNotEmpty()
        }
        onView(isAssignableFrom(EditText::class.java))
            .check(matches(withText(containsString("Private native note body"))))
        val model = androidx.lifecycle.ViewModelProvider(rule.activity)[VaultModel::class.java]
        val revokedFirst = java.util.concurrent.atomic.AtomicBoolean(false)
        model.onLock { revokedFirst.set(!model.store.isOpen) }
        rule.onNodeWithText("Lock", substring = false).performClick()
        org.junit.Assert.assertTrue("Revoke readers before native cleanup", revokedFirst.get())
        rule.onNodeWithTag("vault-password").performTextInput("Synthetic UI password 123")
        rule.onNodeWithText("Unlock", substring = false).performScrollTo().performClick()
        rule.waitUntil(30000) {
            rule.onAllNodesWithTag("vault-library").fetchSemanticsNodes().isNotEmpty()
        }
        // Lazy-grid items can be outside the viewport on compact phones.
        rule.onNodeWithTag("vault-library").performScrollToNode(hasText("Native round-trip note"))
        rule.onNodeWithText("Native round-trip note").performClick()
        rule.waitUntil(15000) {
            rule.onAllNodesWithTag("native-note-body").fetchSemanticsNodes().isNotEmpty()
        }
        onView(isAssignableFrom(EditText::class.java))
            .check(matches(withText(containsString("Private native note body"))))

        // Exercise the styled native controller with synthetic encrypted audio.
        val seconds = 5
        val samples = 44100 * seconds
        val wav =
            java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVEfmt ".toByteArray())
        wav.putInt(16).putShort(1.toShort()).putShort(1.toShort()).putInt(44100).putInt(88200)
        wav.putShort(2.toShort())
            .putShort(16.toShort())
            .put("data".toByteArray())
            .putInt(samples * 2)
        repeat(samples) { i ->
            wav.putShort(
                (kotlin.math.sin(i * 2.0 * Math.PI * 220 / 44100) * 1000).toInt().toShort()
            )
        }
        val mediaId =
            kotlinx.coroutines.runBlocking {
                model.store.importBytes("Paper playback.wav", "audio/wav", wav.array())
            }
        wav.array().fill(0)
        rule.runOnIdle { model.navigate(mediaId) }
        rule.waitUntil(15000) {
            rule.onAllNodesWithTag("native-media-player").fetchSemanticsNodes().isNotEmpty()
        }
        onView(withId(androidx.media3.ui.R.id.exo_play_pause))
            .check(matches(isDisplayed()))
            .perform(androidx.test.espresso.action.ViewActions.click())
        rule.waitUntil(15000) { model.playing }
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        org.junit.Assert.assertFalse(model.unlocked.value)
        org.junit.Assert.assertFalse(model.playing)
        rule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        rule.onNodeWithTag("vault-password").assertExists()
    }
}
