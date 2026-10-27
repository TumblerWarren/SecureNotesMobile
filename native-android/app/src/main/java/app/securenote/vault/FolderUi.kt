package app.securenote.vault

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun FolderPicker(model: VaultModel, dismiss: () -> Unit, choose: (String?) -> Unit) {
    var parent by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("Unfiled") }
    val children by remember(parent) { model.store.dao.folders(parent) }.collectAsState(emptyList())
    var up by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(parent) {
        val f = parent?.let { model.store.dao.folder(it) }
        label = f?.name ?: "Unfiled"
        up = f?.parentId
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Move to $label") },
        text = {
            Column {
                if (parent != null) TextButton({ parent = up }) { Text("↑ Parent folder") }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(children, key = { it.id }) { f ->
                        TextButton({ parent = f.id }, modifier = Modifier.fillMaxWidth()) {
                            Text(f.name, Modifier.weight(1f))
                            PaperIcon(PaperGlyph.Next)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                choose(parent)
                dismiss()
            }) {
                Text("Move here")
            }
        },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun FolderPanel(model: VaultModel) {
    val parent by model.folderId.collectAsState()
    val children by remember(parent) { model.store.dao.folders(parent) }.collectAsState(emptyList())
    val jobs by remember { model.store.dao.importJobs() }.collectAsState(emptyList())
    val busy by model.busy.collectAsState()
    var title by remember { mutableStateOf("Unfiled") }
    var editing by remember { mutableStateOf(false) }
    var editFolder by remember { mutableStateOf<VaultFolder?>(null) }
    var name by remember { mutableStateOf("") }
    var moving by remember { mutableStateOf<VaultFolder?>(null) }
    var removing by remember { mutableStateOf<VaultFolder?>(null) }
    LaunchedEffect(parent) { title = parent?.let { model.store.dao.folder(it)?.name } ?: "Unfiled" }
    Column(Modifier.fillMaxWidth()) {
        Row {
            if (parent != null)
                TextButton({ model.folderUp() }, enabled = !busy) { Text("↑ Parent") }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(12.dp),
            )
            TextButton(
                {
                    editFolder = null
                    name = ""
                    editing = true
                },
                enabled = !busy,
            ) {
                Text("New folder")
            }
        }
        children.forEach { f ->
            var menu by remember(f.id) { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    { model.openFolder(f.id) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) {
                    PaperIcon(PaperGlyph.Vault)
                    Spacer(Modifier.width(8.dp))
                    Text(f.name, Modifier.weight(1f))
                }
                Box {
                    IconButton({ menu = true }, enabled = !busy) {
                        PaperIcon(PaperGlyph.More, "Folder options")
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                menu = false
                                editFolder = f
                                name = f.name
                                editing = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Move") },
                            onClick = {
                                menu = false
                                moving = f
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove folder, keep contents") },
                            onClick = {
                                menu = false
                                removing = f
                            },
                        )
                    }
                }
            }
        }
        jobs.forEach { job ->
            Row {
                Text(
                    "Import: ${job.completed} completed · ${job.failed} failed",
                    Modifier.weight(1f).padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton({ model.resumeImport(job) }, enabled = !busy) { Text("Resume") }
            }
        }
        if (busy) TextButton({ model.cancelImport() }) { Text("Stop current operation") }
    }
    if (editing)
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(if (editFolder == null) "New folder" else "Rename folder") },
            text = {
                OutlinedTextField(
                    name,
                    { name = it.take(100) },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    {
                        model.saveFolder(name, editFolder, editFolder?.parentId ?: parent)
                        editing = false
                    },
                    enabled = name.isNotBlank() && !busy,
                ) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton({ editing = false }) { Text("Cancel") } },
        )
    moving?.let { f ->
        FolderPicker(model, { moving = null }) { destination ->
            model.saveFolder(f.name, f, destination)
        }
    }
    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${f.name}?") },
            text = {
                Text(
                    "Files and child folders will move to its parent. Their encrypted contents are kept."
                )
            },
            confirmButton = {
                TextButton({
                    model.removeFolder(f.id)
                    removing = null
                }) {
                    Text("Keep contents and remove")
                }
            },
            dismissButton = { TextButton({ removing = null }) { Text("Cancel") } },
        )
    }
}
