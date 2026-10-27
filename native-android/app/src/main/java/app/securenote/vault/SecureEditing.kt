package app.securenote.vault

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*

open class SecureEditText(context: android.content.Context) : EditText(context, null, 0, R.style.SecureVaultEditText) {
    override fun onTextContextMenuItem(id: Int): Boolean =
        if (id == android.R.id.copy || id == android.R.id.cut || id == android.R.id.shareText) false
        else super.onTextContextMenuItem(id)

    override fun onKeyShortcut(keyCode: Int, event: android.view.KeyEvent): Boolean =
        if (
            event.isCtrlPressed &&
                (keyCode == android.view.KeyEvent.KEYCODE_C ||
                    keyCode == android.view.KeyEvent.KEYCODE_X)
        )
            true
        else super.onKeyShortcut(keyCode, event)
}

/**
 * Paste/select are local editing operations; copying, cutting, sharing and process-text export are
 * not.
 */
fun Modifier.secureEditing(): Modifier = onPreviewKeyEvent {
    it.isCtrlPressed && (it.key == Key.C || it.key == Key.X)
}
    .filterTextContextMenuComponents {
        it.key == TextContextMenuKeys.PasteKey || it.key == TextContextMenuKeys.SelectAllKey
    }

fun EditText.restrictExternalActions() {
    val callback =
        object : ActionMode.Callback {
            private fun filter(menu: Menu) {
                for (i in menu.size() - 1 downTo 0) {
                    val id = menu.getItem(i).itemId
                    if (id != android.R.id.paste && id != android.R.id.selectAll)
                        menu.removeItem(id)
                }
            }

            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                filter(menu)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                filter(menu)
                return true
            }

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
                item.itemId != android.R.id.paste && item.itemId != android.R.id.selectAll

            override fun onDestroyActionMode(mode: ActionMode) {}
        }
    isSaveEnabled = false
    isSaveFromParentEnabled = false
    freezesText = false
    customSelectionActionModeCallback = callback
    customInsertionActionModeCallback = callback
    setAutofillHints(null)
    if (android.os.Build.VERSION.SDK_INT >= 26)
        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    if (android.os.Build.VERSION.SDK_INT >= 30)
        importantForContentCapture =
            android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    imeOptions =
        imeOptions or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
}
