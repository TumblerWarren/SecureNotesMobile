package app.securenote.vault

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UsabilityLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun settingsPasswordActionRemainsReachableAtLargeFont() {
        lateinit var model: VaultModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model =
                VaultModel(
                    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                        as Application
                )
        }
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PaperTheme { SettingsDialog(model, {}, {}, {}, {}) }
            }
        }
        rule.onNodeWithText("Change password", substring = false).performScrollTo().performClick()
        rule
            .onNodeWithText("Save password", substring = false)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        assertNull(model.passwordResult.value)
    }

    @Test
    fun compactToolbarExposesSelectionAndDisabledSemantics() {
        rule.setContent {
            PaperTheme {
                Row(Modifier.width(320.dp)) {
                    PaperEditorTool("Bold", letter = "B", state = FormatState.On) {}
                    PaperEditorTool("Italic", letter = "I", state = FormatState.Mixed) {}
                    PaperEditorTool("Underline", letter = "U", enabled = false) {}
                }
            }
        }
        rule
            .onNodeWithContentDescription("Bold", substring = false)
            .assertIsSelected()
            .assertIsDisplayed()
        rule
            .onNodeWithContentDescription("Italic", substring = false)
            .assert(
                SemanticsMatcher.expectValue(
                    androidx.compose.ui.semantics.SemanticsProperties.StateDescription,
                    "Mixed",
                )
            )
        rule.onNodeWithContentDescription("Underline", substring = false).assertIsNotEnabled()
    }
}
