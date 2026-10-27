package app.securenote.vault

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsDialog(model: VaultModel, dismiss: () -> Unit, legacy: () -> Unit) {
    var changing by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Vault settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Local storage only. Password required after locking. No cloud backup, external viewers, sharing, printing or plaintext export."
                )
                Text(
                    "Screen-off and leaving the app lock immediately. Foreground inactivity locks after five minutes. Playback counts as activity."
                )
                TextButton(legacy) { Text("Import an old SecureNote database") }
                TextButton({ changing = !changing }) { Text("Change password") }
                if (changing) {
                    OutlinedTextField(
                        current,
                        { current = it },
                        label = { Text("Current password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        next,
                        { next = it },
                        label = { Text("New password (16+ characters)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        confirm,
                        { confirm = it },
                        label = { Text("Confirm new password") },
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
                        enabled = current.isNotEmpty() && next.length >= 16 && next == confirm,
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
    AlertDialog(
        onDismissRequest = { model.legacySource.value = null },
        title = { Text("Import old notes") },
        text = {
            Column {
                Text(
                    "Enter the password of the old database. The old file is never modified. Parsing runs in an isolated process, and notes and attachments are copied into this vault. Legacy databases are limited to 64 MiB, with 16 MiB per note."
                )
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Old database password") },
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
                enabled = password.isNotEmpty(),
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
