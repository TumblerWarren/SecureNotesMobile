package app.securenote.vault

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
    val folderExporter =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val plan = model.exportRequest.value
            model.finishDocumentPicker()
            if (plan != null) model.writeFolderExport(uri, plan)
        }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(export) {
        if (export != null) {
            focus.clearFocus()
            keyboard?.hide()
        }
    }
    if (open && !busy && originals.isNotEmpty()) {
        var selected by remember(originals) { mutableStateOf(originals.map { it.id }.toSet()) }
        PaperSheet(
            "Delete originals?",
            { model.keepOriginals() },
            scrollContent = false,
            footer = {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    @Composable
                    fun keep(modifier: Modifier) {
                        TextButton({ model.keepOriginals() }, modifier = modifier) {
                            Text("Keep originals")
                        }
                    }
                    @Composable
                    fun remove(modifier: Modifier) {
                        TextButton(
                            { model.deleteApprovedOriginals(selected) },
                            enabled = selected.isNotEmpty(),
                            modifier = modifier,
                        ) {
                            Text("Delete selected")
                        }
                    }
                    if (
                        androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f ||
                            maxWidth < 260.dp
                    )
                        Column(Modifier.fillMaxWidth()) {
                            keep(Modifier.fillMaxWidth())
                            remove(Modifier.fillMaxWidth())
                        }
                    else
                        Row(Modifier.fillMaxWidth()) {
                            keep(Modifier.weight(1f))
                            remove(Modifier.weight(1f))
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
                            modifier = Modifier.semantics { contentDescription = receipt.name },
                        )
                        Text(receipt.name, Modifier.weight(1f), maxLines = 2)
                    }
                }
            }
        }
    }
    export?.let { plan ->
        var archive by remember(plan.ids, plan.backup) { mutableStateOf(plan.archive) }
        if (open && !busy)
            PaperSheet(
                if (plan.backup) "Encrypted backup" else "Export unencrypted copy?",
                { model.exportRequest.value = null },
                footer = {
                    TextButton({
                        if (model.beginDocumentPicker()) {
                            try {
                                val chosen =
                                    if (!plan.backup && archive)
                                        plan.copy(
                                            archive = true,
                                            folder = false,
                                            mime = "application/zip",
                                            name =
                                                if (plan.ids.size == 1)
                                                    plan.name.substringBeforeLast('.', plan.name) +
                                                        ".zip"
                                                else "SecureNote-export.zip",
                                        )
                                    else plan.copy(archive = false)
                                model.exportRequest.value = chosen
                                if (chosen.folder && !chosen.backup) folderExporter.launch(null)
                                else exporter.launch(chosen)
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
                    else "Copies are unencrypted. Duplicate names get a number."
                )
                if (!plan.backup)
                    Column(Modifier.fillMaxWidth().selectableGroup()) {
                        listOf(false to "Files", true to "ZIP archive").forEach { (value, label) ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = archive == value,
                                        role = Role.RadioButton,
                                        onClick = { archive = value },
                                    ),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = archive == value,
                                    onClick = null,
                                    modifier = Modifier.padding(8.dp),
                                )
                                Text(label, Modifier.weight(1f))
                            }
                        }
                    }
                if (!plan.backup && plan.mime == "application/x-securenote")
                    Text("Notes use a named folder containing note data and attachments.")
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
            compactOnKeyboard = true,
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
                if (recoveryMode)
                    "Enter the backup's recovery key and choose a new password of at least 16 characters. Restore never replaces an existing vault."
                else
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
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().keepInputVisible(),
                )
            OutlinedTextField(
                password,
                { password = it },
                label = {
                    Text(if (recoveryMode) "New password" else "Backup password")
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
