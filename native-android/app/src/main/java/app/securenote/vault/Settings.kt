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

@Composable
fun SettingsDialog(model: VaultModel, dismiss: () -> Unit, legacy: () -> Unit) {
    val busy by model.busy.collectAsState()
    var changing by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        modifier = Modifier.secureEditing(),
        onDismissRequest = dismiss,
        title = { Text("Your vault, your rules.", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Local storage only. Password required after locking. No cloud backup, external viewers, sharing, printing or plaintext export."
                )
                Text(
                    "Screen-off and leaving the app lock immediately. Foreground inactivity locks after five minutes. Playback counts as activity."
                )
                TextButton(legacy, enabled = !busy) { Text("Import an old SecureNote database") }
                TextButton({ changing = !changing }, enabled = !busy) { Text("Change password") }
                if (changing) {
                    OutlinedTextField(
                        current,
                        { current = it },
                        label = { Text("Current password") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        next,
                        { next = it },
                        label = { Text("New password (16+ characters)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        confirm,
                        { confirm = it },
                        label = { Text("Confirm new password") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    Button(
                        {
                            model.changePassword(current.toByteArray(), next.toByteArray())
                            current = ""
                            next = ""
                            confirm = ""
                            dismiss()
                        },
                        enabled =
                            !busy && current.isNotEmpty() && next.length >= 16 && next == confirm,
                    ) {
                        Text("Save password")
                    }
                }
                Text(
                    "Import copies files; originals in Gallery, Downloads or Drive remain outside the vault. Recovery keys cannot restore deleted data."
                )
            }
        },
        confirmButton = { TextButton(dismiss) { Text("Close") } },
    )
    DisposableEffect(Unit) {
        onDispose {
            current = ""
            next = ""
            confirm = ""
        }
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
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
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
