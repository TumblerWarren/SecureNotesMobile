package app.securenote.vault

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun isEpubFile(mime: String, name: String = ""): Boolean =
    mime.substringBefore(';').trim().equals("application/epub+zip", true) ||
        name.endsWith(".epub", true)

fun fileKind(mime: String, name: String = ""): String =
    when {
        mime == "application/x-securenote" -> "Note"
        isEpubFile(mime, name) -> "EPUB"
        mime == "application/pdf" -> "PDF"
        mime.startsWith("image/") -> "Image"
        mime.startsWith("video/") -> "Video"
        mime.startsWith("audio/") -> "Audio"
        mime.startsWith("text/") -> "Text"
        else -> "Document"
    }

fun fileGlyph(mime: String, name: String = ""): PaperGlyph =
    when (fileKind(mime, name)) {
        "Note" -> PaperGlyph.Note
        "EPUB" -> PaperGlyph.Book
        "Image" -> PaperGlyph.Image
        "Video" -> PaperGlyph.Media
        "Audio" -> PaperGlyph.Audio
        else -> PaperGlyph.Document
    }

fun fileSize(size: Long): String {
    if (size < 1024) return "$size B"
    if (size < 1024L * 1024) return "${size / 1024} KB"
    if (size < 1024L * 1024 * 1024)
        return String.format(Locale.getDefault(), "%.1f MB", size / 1048576.0)
    return String.format(Locale.getDefault(), "%.1f GB", size / 1073741824.0)
}

fun itemDate(time: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(time))

@Composable
fun PaperFileBadge(mime: String, modifier: Modifier = Modifier, name: String = "") {
    val accent =
        if (mime == "application/pdf" || isEpubFile(mime, name)) Paper.Accent else Paper.Muted
    Box(
        modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (mime == "application/pdf" || isEpubFile(mime, name)) Paper.AccentSoft
                else Paper.Inset
            ),
        contentAlignment = Alignment.Center,
    ) {
        PaperIcon(fileGlyph(mime, name), color = accent)
    }
}

@Composable
fun PaperLibrary(model: VaultModel, state: LazyGridState, onAdd: () -> Unit) {
    val query by model.query.collectAsState()
    val category by model.category.collectAsState()
    val pages = model.pages.collectAsLazyPagingItems()
    val grid by model.useGrid.collectAsState()
    val order by model.sort.collectAsState()
    var sorting by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(false) }
    val pinned = emptyList<ItemSummary>()
    val browsing by model.folderBrowsing.collectAsState()
    val parent by model.folderId.collectAsState()
    var chosen by remember(parent, category, query) { mutableStateOf(setOf<String>()) }
    var movingFiles by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ItemSummary?>(null) }
    var lastFilter by remember { mutableStateOf(listOf(category, query, parent, order, grid)) }
    LaunchedEffect(category, query, parent, order, grid) {
        val next = listOf(category, query, parent, order, grid)
        if (lastFilter != next) {
            state.scrollToItem(0)
            lastFilter = next
        }
    }
    val filters =
        listOf(
            "all" to "All",
            "notes" to "Notes",
            "images" to "Images",
            "videos" to "Videos",
            "audio" to "Audio",
            "pdf" to "PDFs",
            "epub" to "EPUB",
            "documents" to "Files",
        )
    LazyVerticalGrid(
        columns = if (grid) GridCells.Adaptive(140.dp) else GridCells.Fixed(1),
        state = state,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 80.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().testTag("vault-library"),
    ) {
        item(key = "heading", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                if (browsing) FolderPanel(model)
                FlowRow {
                    TextButton({
                        selecting = !selecting
                        chosen = emptySet()
                    }) {
                        Text(if (selecting) "Done" else "Select")
                    }
                    TextButton({ model.toggleGrid() }) {
                        Text(if (grid) "List view" else "Grid view")
                    }
                    Box {
                        TextButton({ sorting = true }) { Text("Sort") }
                        DropdownMenu(sorting, { sorting = false }) {
                            listOf("modified" to "Modified", "name" to "Name", "size" to "Size")
                                .forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(if (key == order) "✓ $label" else label) },
                                        onClick = {
                                            model.sort.value = key
                                            sorting = false
                                        },
                                    )
                                }
                        }
                    }
                }
                if (chosen.isNotEmpty())
                    Row {
                        Text("${chosen.size} selected", Modifier.weight(1f).padding(12.dp))
                        TextButton({ movingFiles = true }) { Text("Move") }
                        TextButton({ grouping = true }) { Text("By type") }
                        TextButton({ chosen = emptySet() }) { Text("Clear") }
                    }

                Text(
                    when (category) {
                        "notes" -> "Notes"
                        "documents" -> "Files"
                        "media" -> "Media"
                        else -> if (browsing) "Folder contents" else "Vault"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (pages.itemCount == 0) "On this device"
                    else
                        "${pages.itemCount} ${if (pages.itemCount == 1) "item" else "items"} · On this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Muted,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        model.query.value = it
                        model.touch()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("vault-search"),
                    placeholder = {
                        Text(
                            "Search your notes, files and media",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    leadingIcon = { PaperIcon(PaperGlyph.Search) },
                    trailingIcon =
                        if (query.isNotEmpty()) {
                            {
                                IconButton({ model.query.value = "" }) {
                                    PaperIcon(PaperGlyph.Close, "Clear search")
                                }
                            }
                        } else null,
                    singleLine = true,
                    shape = RoundedCornerShape(30.dp),
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Paper.Inset,
                            unfocusedContainerColor = Paper.Inset,
                            focusedBorderColor = Paper.Accent,
                            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    filters.forEach { (key, label) ->
                        Column(
                            Modifier.width(if (key == "documents") 104.dp else 72.dp)
                                .clickable { model.showLibrary(key) }
                                .padding(top = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                label,
                                color = if (category == key) Paper.Accent else Paper.Muted,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Box(
                                Modifier.fillMaxWidth()
                                    .height(2.dp)
                                    .background(if (category == key) Paper.Accent else Paper.Line)
                            )
                        }
                    }
                }
            }
        }
        if (pinned.isNotEmpty()) {
            item(key = "pinned-label", span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel("Pinned", PaperGlyph.Pin)
            }
            pinned.forEach { pinnedItem ->
                item(key = "pinned/${pinnedItem.id}", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { model.navigate(pinnedItem.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PaperFileBadge(pinnedItem.mime, name = pinnedItem.name)
                        Column(Modifier.weight(1f)) {
                            Text(
                                pinnedItem.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${fileKind(pinnedItem.mime, pinnedItem.name)} · Pinned",
                                style = MaterialTheme.typography.bodySmall,
                                color = Paper.Muted,
                            )
                        }
                        FileMenu(
                            pinnedItem,
                            { model.pin(pinnedItem.id) },
                            { deleting = pinnedItem },
                        )
                    }
                }
            }
        }
        if (pages.itemCount > pinned.size) {
            item(key = "recent-label", span = { GridItemSpan(maxLineSpan) }) {
                SectionLabel(if (query.isBlank()) "Items · pinned first" else "Results")
            }
        }
        items(
            count = (pages.itemCount - pinned.size).coerceAtLeast(0),
            key = { i -> pages.peek(i + pinned.size)?.id ?: "loading/${i + pinned.size}" },
        ) { i ->
            val item = pages[i + pinned.size]
            if (item == null)
                Box(
                    Modifier.height(190.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Paper.Inset)
                )
            else
                Column {
                    Row {
                        if (selecting)
                            Checkbox(
                                item.id in chosen,
                                { checked ->
                                    chosen = if (checked) chosen + item.id else chosen - item.id
                                },
                            )
                        Box(Modifier.weight(1f)) { PaperItemCard(model, item, { deleting = item }) }
                    }
                }
        }
        if (pages.loadState.refresh is LoadState.Loading) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth(),
                    color = Paper.Accent,
                    trackColor = Paper.Inset,
                )
            }
        } else if (
            pages.loadState.refresh is LoadState.Error || pages.loadState.append is LoadState.Error
        ) {
            item(key = "retry", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(vertical = 24.dp)) {
                    Text(
                        "This collection couldn’t be loaded.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton({ pages.retry() }) { Text("Try again") }
                }
            }
        } else if (pages.itemCount == 0) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PaperFileBadge(
                        if (category == "notes") "application/x-securenote"
                        else "application/octet-stream",
                        Modifier.size(52.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (query.isNotBlank()) "Nothing found" else "Make yourself at home.",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (query.isNotBlank()) "Try another name or phrase."
                        else "Add a note or import a file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Paper.Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (query.isBlank()) TextButton(onAdd) { Text("Add something") }
                }
            }
        }
    }
    if (grouping)
        AlertDialog(
            onDismissRequest = { grouping = false },
            title = { Text("Arrange ${chosen.size} items by type?") },
            text = {
                Text(
                    "Moves selected items into Notes, Images, Videos, Audio, PDFs, EPUB or Other files folders in the current location. Encrypted files are kept."
                )
            },
            confirmButton = {
                TextButton({
                    model.groupFiles(chosen.toList())
                    chosen = emptySet()
                    grouping = false
                }) {
                    Text("Arrange")
                }
            },
            dismissButton = { TextButton({ grouping = false }) { Text("Cancel") } },
        )
    if (movingFiles)
        FolderPicker(model, { movingFiles = false }) { destination ->
            model.moveFiles(chosen.toList(), destination)
            chosen = emptySet()
        }
    deleting?.let { item ->
        ConfirmPaperDelete(item.name, { deleting = null }) {
            deleting = null
            model.delete(item.id)
        }
    }
}

@Composable
private fun SectionLabel(label: String, glyph: PaperGlyph? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (glyph != null) PaperIcon(glyph, color = Paper.Muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PaperItemCard(model: VaultModel, item: ItemSummary, onDelete: () -> Unit) {
    val isVisual = item.mime.startsWith("image/") || item.mime.startsWith("video/")
    var snippet by remember(item.id) { mutableStateOf("") }
    LaunchedEffect(item.id, item.updated) {
        if (item.mime == "application/x-securenote") {
            try {
                snippet = withContext(Dispatchers.IO) { model.store.dao.snippet(item.id).orEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snippet = ""
            }
        }
    }
    Card(
        onClick = { model.navigate(item.id) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Paper.Surface),
        border = BorderStroke(1.dp, Paper.Line.copy(alpha = 0.7f)),
    ) {
        if (isVisual) PaperThumbnail(model, item, Modifier.fillMaxWidth().height(112.dp))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isVisual && item.mime != "application/x-securenote")
                PaperFileBadge(item.mime, name = item.name)
            Text(
                item.name,
                style =
                    if (item.mime == "application/x-securenote")
                        MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.mime == "application/x-securenote")
                Text(
                    snippet.ifBlank { "Empty note" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Paper.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            item.folderName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Paper.Muted,
                    maxLines = 1,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (item.mime == "application/x-securenote") itemDate(item.updated)
                    else "${fileKind(item.mime, item.name)} · ${fileSize(item.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Paper.Muted,
                    modifier = Modifier.weight(1f),
                )
                if (item.pinned)
                    PaperIcon(PaperGlyph.Pin, "Pinned", modifier = Modifier.size(16.dp))
                FileMenu(item, { model.pin(item.id) }, onDelete)
            }
        }
    }
    DisposableEffect(item.id) { onDispose { snippet = "" } }
}

@Composable
private fun FileMenu(item: ItemSummary, pin: () -> Unit, delete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton({ expanded = true }, modifier = Modifier.size(48.dp)) {
            PaperIcon(PaperGlyph.More, "Options for ${item.name}", color = Paper.Muted)
        }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (item.pinned) "Unpin" else "Pin") },
                onClick = {
                    expanded = false
                    pin()
                },
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    expanded = false
                    delete()
                },
            )
        }
    }
}

@Composable
private fun PaperThumbnail(model: VaultModel, item: ItemSummary, modifier: Modifier) {
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    val busy by model.busy.collectAsState()
    LaunchedEffect(item.id, busy) {
        if (!busy)
            try {
                bitmap = model.previews.thumbnail(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                bitmap = null
            }
    }
    Box(modifier.background(Paper.Inset), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null)
            Image(
                image.asImageBitmap(),
                null,
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        else PaperIcon(fileGlyph(item.mime), color = Paper.Muted, modifier = Modifier.size(32.dp))
        if (item.mime.startsWith("video/"))
            Box(
                Modifier.align(Alignment.BottomStart)
                    .padding(8.dp)
                    .background(Paper.Media, RoundedCornerShape(6.dp))
                    .padding(5.dp)
            ) {
                PaperIcon(
                    PaperGlyph.Media,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
    }
    DisposableEffect(item.id) { onDispose { bitmap = null } }
}

@Composable
fun ConfirmPaperDelete(name: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        modifier = Modifier.secureEditing(),
        onDismissRequest = dismiss,
        title = { Text("Delete permanently?", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Text("“$name” will be removed from this vault. Deleted items cannot be recovered.")
        },
        confirmButton = {
            TextButton(confirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(dismiss) { Text("Keep item") } },
    )
}
