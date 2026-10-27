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
        rule.onNodeWithText("New note").performScrollTo().performClick()
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
        rule.onNodeWithText("Back", substring = false).performClick()
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
            rule.onAllNodesWithText("Native round-trip note").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Native round-trip note").performClick()
        rule.waitUntil(15000) {
            rule.onAllNodesWithTag("native-note-body").fetchSemanticsNodes().isNotEmpty()
        }
        onView(isAssignableFrom(EditText::class.java))
            .check(matches(withText(containsString("Private native note body"))))
    }
}
