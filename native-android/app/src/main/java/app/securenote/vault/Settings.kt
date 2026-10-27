package app.securenote.vault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy

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
            ),
    ) {
        Surface(Modifier.fillMaxSize(), color = Paper.Background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().secureEditing()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    TextButton(
                        {
                            if (changing) {
                                changing = false
                                clear()
                            } else dismiss()
                        },
                        enabled = !busy,
                    ) {
                        Text("Back")
                    }
                    Text(
                        if (changing) "Change password" else "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(12.dp),
                    )
                    TextButton({ model.lock() }) { Text("Lock") }
                }
                HorizontalDivider(color = Paper.Line)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(PaperSpacing.Screen)
                ) {
                    if (changing) {
                        Text("Use a passphrase of at least 16 characters.", color = Paper.Muted)
                        Spacer(Modifier.height(16.dp))
                        listOf("Current password", "New password", "Confirm new password")
                            .forEachIndexed { index, label ->
                                OutlinedTextField(
                                    value =
                                        when (index) {
                                            0 -> current
                                            1 -> next
                                            else -> confirm
                                        },
                                    onValueChange = {
                                        when (index) {
                                            0 -> current = it
                                            1 -> next = it
                                            else -> confirm = it
                                        }
                                    },
                                    label = { Text(label) },
                                    singleLine = true,
                                    keyboardOptions =
                                        KeyboardOptions(keyboardType = KeyboardType.Password),
                                    visualTransformation = PasswordVisualTransformation(),
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
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
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) {
                            Text(if (busy) "Changing…" else "Save password")
                        }
                    } else {
                        Text(
                            "Security",
                            style = MaterialTheme.typography.titleSmall,
                            color = Paper.Muted,
                        )
                        SettingsRow(
                            "Change password",
                            "Update the password for this vault",
                            !busy,
                        ) {
                            model.passwordResult.value = null
                            changing = true
                        }
                        HorizontalDivider(color = Paper.Line)
                        Text(
                            "Locks when you leave the app or turn off the screen. Locks after five minutes of inactivity; playback counts as activity.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Paper.Muted,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "Import & storage",
                            style = MaterialTheme.typography.titleSmall,
                            color = Paper.Muted,
                        )
                        SettingsRow("Import files", "Choose encrypted copies to add", !busy, files)
                        SettingsRow(
                            "Import folder",
                            "Keep subfolders and encrypt each file",
                            !busy,
                            folder,
                        )
                        SettingsRow(
                            "Import old database",
                            "Copy notes from an earlier SecureNote vault",
                            !busy,
                            legacy,
                        )
                        HorizontalDivider(color = Paper.Line)
                        Spacer(Modifier.height(20.dp))
                        Text("On this device", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "No cloud backup. Imports create encrypted copies; originals remain outside the vault. Recovery keys cannot restore deleted files.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Paper.Muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
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
private fun SettingsRow(title: String, subtitle: String, enabled: Boolean, action: () -> Unit) {
    TextButton(
        action,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (enabled) Paper.Muted
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
        PaperIcon(PaperGlyph.Next)
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
