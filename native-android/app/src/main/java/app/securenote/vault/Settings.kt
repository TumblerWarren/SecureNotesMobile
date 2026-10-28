package app.securenote.vault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*

@Composable
fun SettingsDialog(
    model: VaultModel,
    dismiss: () -> Unit,
    legacy: () -> Unit,
    files: () -> Unit,
    folder: () -> Unit,
) {
    val busy by model.busy.collectAsState()
    val result by model.passwordResult.collectAsState()
    var changing by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<String?>(null) }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    fun clear() {
        current = ""
        next = ""
        confirm = ""
    }
    LaunchedEffect(result) {
        if (result == "success") {
            clear()
            changing = false
            model.passwordResult.value = null
        }
    }
    Dialog(
        onDismissRequest = { if (!busy) dismiss() },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                securePolicy = SecureFlagPolicy.SecureOn,
                decorFitsSystemWindows = false,
            ),
    ) {
        PaperDialogBars()
        val keyboardOpen =
            WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        val compactKeyboard =
            keyboardOpen &&
                androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
        Surface(Modifier.fillMaxSize(), color = Paper.Background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().secureEditing()) {
                if (!compactKeyboard)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PaperAction("Back", PaperGlyph.Back, enabled = !busy) {
                            if (changing) {
                                changing = false
                                clear()
                            } else dismiss()
                        }
                        Text(
                            if (changing) "Change password" else "Settings",
                            Modifier.weight(1f).padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        PaperAction("Lock", PaperGlyph.Lock) { model.lock() }
                    }
                HorizontalDivider(color = Paper.Line)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(if (changing) 16.dp else 8.dp)
                ) {
                    if (changing) {
                        if (!keyboardOpen)
                            Text(
                                "At least 16 characters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Paper.Muted,
                            )
                        if (!keyboardOpen) Spacer(Modifier.height(12.dp))
                        listOf("Current password", "New password", "Confirm password")
                            .forEachIndexed { index, label ->
                                OutlinedTextField(
                                    when (index) {
                                        0 -> current
                                        1 -> next
                                        else -> confirm
                                    },
                                    {
                                        when (index) {
                                            0 -> current = it
                                            1 -> next = it
                                            else -> confirm = it
                                        }
                                    },
                                    label = {
                                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    },
                                    singleLine = true,
                                    keyboardOptions =
                                        KeyboardOptions(keyboardType = KeyboardType.Password),
                                    visualTransformation = PasswordVisualTransformation(),
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().keepInputVisible(),
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                        if (confirm.isNotEmpty() && next != confirm)
                            Text("Passwords do not match.", color = MaterialTheme.colorScheme.error)
                        if (result != null && result != "success")
                            Text(result!!, color = MaterialTheme.colorScheme.error)
                        Button(
                            { model.changePassword(current.toByteArray(), next.toByteArray()) },
                            enabled =
                                !busy &&
                                    current.isNotEmpty() &&
                                    next.length >= 16 &&
                                    next == confirm,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        ) {
                            Text(if (busy) "Changing…" else "Save password")
                        }
                    } else {
                        SettingsHeading("Security")
                        SettingsRow(
                            "Change password",
                            "Update your vault password",
                            PaperGlyph.Lock,
                            !busy,
                        ) {
                            model.passwordResult.value = null
                            changing = true
                        }
                        SettingsRow(
                            "Auto-lock",
                            "On leaving · 5 minutes idle",
                            PaperGlyph.Timer,
                            !busy,
                        ) {
                            details = "Auto-lock"
                        }
                        Spacer(Modifier.height(12.dp))
                        SettingsHeading("Import & storage")
                        SettingsRow(
                            "Import files",
                            "Add encrypted copies",
                            PaperGlyph.Import,
                            !busy,
                            files,
                        )
                        SettingsRow(
                            "Import folder",
                            "Include subfolders",
                            PaperGlyph.Vault,
                            !busy,
                            folder,
                        )
                        SettingsRow(
                            "Import old database",
                            "Bring notes from an earlier vault",
                            PaperGlyph.Note,
                            !busy,
                            legacy,
                        )
                        Spacer(Modifier.height(12.dp))
                        SettingsHeading("On this device")
                        SettingsRow(
                            "Storage & recovery",
                            "Originals, backups and recovery keys",
                            PaperGlyph.Shield,
                            true,
                        ) {
                            details = "Storage & recovery"
                        }
                    }
                }
            }
        }
    }
    details?.let { title ->
        PaperSheet(title, { details = null }) {
            Text(
                if (title == "Auto-lock")
                    "Locks when you leave the app or turn off the screen. Locks after five minutes of inactivity; playback counts as activity."
                else
                    "No cloud backup. Imports create encrypted copies; originals remain outside the vault. Recovery keys cannot restore deleted files.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            clear()
            model.passwordResult.value = null
        }
    }
}

@Composable
private fun SettingsHeading(title: String) {
    Text(
        title,
        Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = Paper.Muted,
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    icon: PaperGlyph,
    enabled: Boolean,
    action: () -> Unit,
) {
    TextButton(
        action,
        enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        PaperIcon(icon, color = Paper.Accent)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Paper.Muted)
        }
        PaperIcon(PaperGlyph.Next, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun LegacyPasswordDialog(model: VaultModel) {
    var password by remember { mutableStateOf("") }
    val busy by model.busy.collectAsState()
    AlertDialog(
        modifier = Modifier.secureEditing(),
        onDismissRequest = { model.legacySource.value = null },
        title = { Text("Import old notes") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Enter the password of the old database. The old file is never modified. Parsing runs in an isolated process, and notes and attachments are copied into this vault. Legacy databases are limited to 64 MiB, with 16 MiB per note."
                )
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Old database password") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                {
                    model.importLegacy(password.toByteArray())
                    password = ""
                },
                enabled = !busy && password.isNotEmpty(),
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton({
                model.legacySource.value = null
                password = ""
            }) {
                Text("Cancel")
            }
        },
    )
    DisposableEffect(Unit) { onDispose { password = "" } }
}
