package app.securenote.vault

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal suspend fun folderPath(dao: Items, id: String?): String {
    val names = mutableListOf<String>()
    var current = id
    val seen = mutableSetOf<String>()
    while (current != null && seen.add(current) && names.size < 64) {
        val f = dao.folder(current) ?: break
        names.add(f.name)
        current = f.parentId
    }
    return (listOf("Folders") + names.asReversed()).joinToString(" / ")
}

@Composable
internal fun FolderBreadcrumb(model: VaultModel, id: String?) {
    var ancestors by remember(id) { mutableStateOf(emptyList<VaultFolder>()) }
    LaunchedEffect(id) {
        val result = mutableListOf<VaultFolder>()
        val seen = mutableSetOf<String>()
        var p = id
        while (p != null && seen.add(p) && result.size < 64) {
            val f = model.store.dao.folder(p) ?: break
            result.add(f)
            p = f.parentId
        }
        ancestors = result.asReversed()
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PaperAction("Folders", PaperGlyph.Home) { model.openFolder(null) }
        ancestors.forEach { f ->
            PaperIcon(PaperGlyph.Next, modifier = Modifier.size(14.dp), color = Paper.Muted)
            TextButton({ model.openFolder(f.id) }) { Text(f.name, maxLines = 1, softWrap = false) }
        }
    }
}

@Composable
internal fun FolderPicker(model: VaultModel, dismiss: () -> Unit, choose: (String?) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var parent by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("Folders / Unfiled") }
    var up by remember { mutableStateOf<String?>(null) }
    val children by remember(parent) { model.store.dao.folders(parent) }.collectAsState(emptyList())
    LaunchedEffect(parent) {
        label = if (parent == null) "Folders / Unfiled" else folderPath(model.store.dao, parent)
        up = parent?.let { model.store.dao.folder(it)?.parentId }
    }
    PaperSheet(
        "Move to folder",
        dismiss,
        footer = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(dismiss, Modifier.weight(1f)) { Text("Cancel", maxLines = 1) }
                Button(
                    {
                        choose(parent)
                        dismiss()
                    },
                    Modifier.weight(1f),
                ) {
                    Text("Move here", maxLines = 1)
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PaperIcon(PaperGlyph.Home)
            Text(
                label,
                Modifier.weight(1f).padding(horizontal = 8.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            PaperAction("New folder", PaperGlyph.FolderAdd) { creating = true }
        }
        if (parent != null)
            TextButton({ parent = up }) {
                PaperIcon(PaperGlyph.Back)
                Text("Parent folder", Modifier.padding(start = 8.dp))
            }
        LazyColumn(
            Modifier.fillMaxWidth()
                .heightIn(max = 280.dp)
                .height((children.size.coerceAtMost(4) * 64).coerceAtLeast(48).dp)
        ) {
            items(children, key = { it.id }) { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { parent = f.id }.heightIn(min = 64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FolderBadge(f)
                    Text(
                        f.name,
                        Modifier.weight(1f).padding(horizontal = 12.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PaperIcon(PaperGlyph.Next)
                }
            }
            if (children.isEmpty())
                item {
                    Text("No subfolders", Modifier.padding(vertical = 12.dp), color = Paper.Muted)
                }
        }
    }
    if (creating) FolderNameDialog(model, null, parent) { creating = false }
}

@Composable
private fun FolderBadge(f: VaultFolder) {
    val color = if (f.color == 0) Paper.Accent else Color(f.color)
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = .12f)) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            PaperIcon(PaperGlyph.Vault, color = color)
        }
    }
}

@Composable
internal fun FolderRow(model: VaultModel, summary: FolderSummary) {
    val f = summary.folder
    val busy by model.busy.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var color by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(enabled = !busy) { model.openFolder(f.id) }
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderBadge(f)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                f.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${summary.fileCount} files" +
                    (if (summary.childCount > 0) " · ${summary.childCount} folders" else "") +
                    " · ${fileSize(summary.totalSize)}",
                style = MaterialTheme.typography.bodySmall,
                color = Paper.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            PaperAction("Folder options", PaperGlyph.More, enabled = !busy) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                listOf("Color", "Rename", "Move", "Remove folder, keep contents").forEach { label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menu = false
                            when (label) {
                                "Color" -> color = true
                                "Rename" -> editing = true
                                "Move" -> moving = true
                                else -> removing = true
                            }
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider(color = Paper.Line.copy(alpha = .5f))
    if (editing) FolderNameDialog(model, f, f.parentId) { editing = false }
    if (moving) FolderPicker(model, { moving = false }) { model.saveFolder(f.name, f, it) }
    if (color)
        PaperSheet("Folder color", { color = false }) {
            FlowRow {
                listOf(
                        "Terracotta" to 0xFFA9513F.toInt(),
                        "Blue" to 0xFF315F91.toInt(),
                        "Green" to 0xFF2F6A4C.toInt(),
                        "Purple" to 0xFF694B83.toInt(),
                    )
                    .forEach { (name, value) ->
                        TextButton({
                            model.saveFolder(f.name, f, f.parentId, color = value)
                            color = false
                        }) {
                            RadioButton(
                                selected =
                                    f.color == value || (f.color == 0 && name == "Terracotta"),
                                onClick = null,
                            )
                            Text(name, Modifier.padding(start = 4.dp), color = Color(value))
                        }
                    }
            }
        }
    if (removing)
        PaperSheet(
            "Remove folder?",
            { removing = false },
            footer = {
                Button(
                    {
                        model.removeFolder(f.id)
                        removing = false
                    },
                    Modifier.fillMaxWidth(),
                ) {
                    Text("Keep contents and remove")
                }
            },
        ) {
            Text("Files and child folders move to the parent. Their encrypted contents are kept.")
        }
}

@Composable
internal fun FolderNameDialog(
    model: VaultModel,
    existing: VaultFolder?,
    parent: String?,
    dismiss: () -> Unit,
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var failure by remember { mutableStateOf<String?>(null) }
    val busy by model.busy.collectAsState()
    AlertDialog(
        onDismissRequest = { if (!busy) dismiss() },
        title = { Text(if (existing == null) "New folder" else "Rename folder") },
        text = {
            Column {
                OutlinedTextField(
                    name,
                    { name = it.take(100) },
                    label = { Text("Name") },
                    singleLine = true,
                    enabled = !busy,
                )
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                {
                    model.saveFolder(
                        name,
                        existing,
                        parent,
                        onResult = { error -> if (error == null) dismiss() else failure = error },
                    )
                },
                enabled = !busy && name.isNotBlank(),
            ) {
                Text(if (busy) "Saving…" else "Save")
            }
        },
        dismissButton = { TextButton(dismiss, enabled = !busy) { Text("Cancel") } },
    )
}
