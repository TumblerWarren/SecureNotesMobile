package app.securenote.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal fun VaultTransferDialogs(model: VaultModel) {
    val open by model.unlocked.collectAsState()
    val busy by model.busy.collectAsState()
    val originals by model.originalsToDelete.collectAsState()
    val export by model.exportRequest.collectAsState()
    val restore by model.restoreRequest.collectAsState()
    val exporter =
        rememberLauncherForActivityResult(VaultExportContract()) { uri ->
            val plan = model.exportRequest.value
            model.finishDocumentPicker()
            if (plan != null) model.writeExport(uri, plan)
        }
    if (open && !busy && originals.isNotEmpty()) {
        var selected by remember(originals) { mutableStateOf(originals.map { it.id }.toSet()) }
        PaperSheet(
            "Delete originals?",
            { model.keepOriginals() },
            scrollContent = false,
            footer = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton({ model.keepOriginals() }) { Text("Keep originals") }
                    TextButton(
                        { model.deleteApprovedOriginals(selected) },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text("Delete selected")
                    }
                }
            },
        ) {
            Text(
                "These files were saved and verified in your vault. Select originals to delete. Cloud trash and other copies may remain."
            )
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f, fill = false)) {
                items(originals.size, key = { originals[it].id }) { index ->
                    val receipt = originals[index]
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            receipt.id in selected,
                            { checked ->
                                selected =
                                    if (checked) selected + receipt.id else selected - receipt.id
                            },
                        )
                        Text(receipt.name, Modifier.weight(1f), maxLines = 2)
                    }
                }
            }
        }
    }
    export?.let { plan ->
        if (open && !busy)
            PaperSheet(
                if (plan.backup) "Encrypted backup" else "Export unencrypted copy?",
                { model.exportRequest.value = null },
                footer = {
                    TextButton({
                        if (model.beginDocumentPicker()) {
                            try {
                                exporter.launch(plan)
                            } catch (_: Exception) {
                                model.finishDocumentPicker()
                                model.exportRequest.value = null
                                model.message.value = "The destination picker could not be opened."
                            }
                        }
                    }) {
                        Text("Choose destination")
                    }
                },
            ) {
                Text(
                    if (plan.backup)
                        "Save your encrypted vault locally or to a cloud provider shown in the file picker. Restore requires the password valid when this backup is created, or its recovery key. This is a snapshot, not automatic cloud sync."
                    else
                        "This copy can be read by other apps and anyone with access to its destination. The vault copy stays encrypted. Notes export as a ZIP containing structured note data, drawings and attachments."
                )
            }
    }
    restore?.let { source ->
        var password by remember(source) { mutableStateOf("") }
        var recoveryMode by remember(source) { mutableStateOf(false) }
        var recoveryKey by remember(source) { mutableStateOf("") }
        PaperSheet(
            "Restore encrypted backup",
            {
                password = ""
                model.restoreRequest.value = null
            },
            footer = {
                TextButton(
                    {
                        val bytes = password.toByteArray()
                        password = ""
                        val secret =
                            if (recoveryMode)
                                recoveryKey
                                    .filterNot(Char::isWhitespace)
                                    .chunked(2)
                                    .map { it.toInt(16).toByte() }
                                    .toByteArray()
                            else null
                        recoveryKey = ""
                        model.restoreBackup(bytes, secret)
                    },
                    enabled =
                        !busy &&
                            (if (recoveryMode)
                                password.length >= 16 &&
                                    recoveryKey
                                        .filterNot(Char::isWhitespace)
                                        .matches(Regex("[a-fA-F0-9]{64}"))
                            else password.isNotEmpty()),
                ) {
                    Text("Verify and restore")
                }
            },
        ) {
            Text(
                "Restore is available only before creating a vault. Enter the password used when the backup was created. Your backup is checked before installation."
            )
            TextButton({
                recoveryMode = !recoveryMode
                password = ""
                recoveryKey = ""
            }) {
                Text(if (recoveryMode) "Use backup password" else "Use recovery key")
            }
            if (recoveryMode)
                OutlinedTextField(
                    recoveryKey,
                    { recoveryKey = it },
                    label = { Text("Recovery key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().keepInputVisible(),
                )
            OutlinedTextField(
                password,
                { password = it },
                label = {
                    Text(if (recoveryMode) "New password (16+ characters)" else "Backup password")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().keepInputVisible(),
            )
        }
        DisposableEffect(source) {
            onDispose {
                password = ""
                recoveryKey = ""
            }
        }
    }
}

@Composable
internal fun RestoreBackupButton(model: VaultModel, enabled: Boolean) {
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            model.finishDocumentPicker()
            if (uri != null) model.restoreRequest.value = uri
        }
    TextButton(
        { picker.launch(arrayOf("application/octet-stream", "application/zip")) },
        enabled = enabled,
    ) {
        Text("Restore encrypted backup")
    }
}

internal class VaultExportContract :
    androidx.activity.result.contract.ActivityResultContract<VaultExport, android.net.Uri?>() {
    override fun createIntent(context: android.content.Context, input: VaultExport) =
        android.content
            .Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType(if (input.backup) "application/octet-stream" else input.mime)
            .putExtra(android.content.Intent.EXTRA_TITLE, input.name)

    override fun parseResult(resultCode: Int, intent: android.content.Intent?): android.net.Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}
