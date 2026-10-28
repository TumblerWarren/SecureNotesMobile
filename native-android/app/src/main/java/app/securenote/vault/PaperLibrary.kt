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
import kotlinx.coroutines.flow.flowOf

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

internal fun countLabel(count: Int, singular: String): String =
    "$count $singular" + if (count == 1) "" else "s"

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
fun PaperLibrary(
    model: VaultModel,
    state: LazyGridState,
    onSelecting: (Boolean) -> Unit = {},
    onAdd: () -> Unit,
) {
    val inputFocus = androidx.compose.ui.platform.LocalFocusManager.current
    val inputKeyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val query by model.query.collectAsState()
    val category by model.category.collectAsState()
    val grid by model.useGrid.collectAsState()
    val order by model.sort.collectAsState()
    val browsing by model.folderBrowsing.collectAsState()
    val parent by model.folderId.collectAsState()
    val busy by model.busy.collectAsState()
    val pages = model.pages.collectAsLazyPagingItems()
    val folders by
        remember(parent, browsing) {
                if (browsing) model.store.dao.folderSummaries(parent)
                else flowOf(emptyList<FolderSummary>())
            }
            .collectAsState(emptyList())
    val jobs by remember { model.store.dao.importJobs() }.collectAsState(emptyList())
    var folderName by remember(parent) { mutableStateOf("Folders") }
    LaunchedEffect(parent) {
        folderName = parent?.let { model.store.dao.folder(it)?.name } ?: "Folders"
    }
    var selecting by remember { mutableStateOf(false) }
    var chosen by remember(parent, category, query) { mutableStateOf(setOf<String>()) }
    var sorting by remember { mutableStateOf(false) }
    var types by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var grouping by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ItemSummary?>(null) }
    var bulkDelete by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val callback by rememberUpdatedState(onSelecting)
    LaunchedEffect(selecting) { callback(selecting) }
    DisposableEffect(Unit) { onDispose { callback(false) } }
    // Folder rows arrive asynchronously. Reset after their identities arrive so the grid
    // cannot retain an old file anchor above which the folders were inserted.
    val folderIds = folders.map { it.folder.id }
    LaunchedEffect(category, query, parent, browsing, order, grid, folderIds) {
        state.scrollToItem(0)
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
    val title =
        if (browsing) folderName
        else
            filters.firstOrNull { it.first == category }?.second?.takeUnless { category == "all" }
                ?: "Vault"
    val visibleFolders =
        remember(folders, browsing, query) {
            if (browsing)
                folders.filter {
                    query.isBlank() || it.folder.name.contains(query, ignoreCase = true)
                }
            else emptyList()
        }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Keep the text field at the same call site when the IME changes available height.
        val short =
            androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        @Composable
        fun search(modifier: Modifier) {
            OutlinedTextField(
                query,
                {
                    model.query.value = it
                    model.touch()
                },
                singleLine = true,
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search
                    ),
                keyboardActions =
                    androidx.compose.foundation.text.KeyboardActions(
                        onSearch = {
                            inputFocus.clearFocus()
                            inputKeyboard?.hide()
                        }
                    ),
                modifier = modifier.testTag("vault-search"),
                textStyle = MaterialTheme.typography.bodyMedium,
                placeholder = {
                    Text(
                        if (browsing) "Search folder" else "Search vault",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                leadingIcon = { PaperIcon(PaperGlyph.Search) },
                trailingIcon = {
                    if (query.isNotEmpty())
                        PaperAction("Clear search", PaperGlyph.Close) { model.query.value = "" }
                    else
                        Box {
                            PaperAction("File types", PaperGlyph.Sliders) { types = true }
                            DropdownMenu(types, { types = false }) {
                                filters.forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            model.showLibrary(key)
                                            types = false
                                        },
                                    )
                                }
                            }
                        }
                },
                shape = RoundedCornerShape(16.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = Paper.Inset,
                        focusedContainerColor = Paper.Inset,
                        unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
            )
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting)
                    PaperAction("Finish selecting", PaperGlyph.Close) {
                        selecting = false
                        chosen = emptySet()
                    }
                else if (browsing && parent != null)
                    PaperAction("Parent folder", PaperGlyph.Back) { model.folderUp() }
                Column(
                    if (short && !selecting)
                        Modifier.widthIn(max = 120.dp).padding(horizontal = 12.dp)
                    else Modifier.weight(1f).padding(start = 12.dp)
                ) {
                    Text(
                        if (selecting) "${chosen.size} selected" else title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!short && !selecting)
                        Text(
                            if (
                                pages.loadState.refresh is LoadState.Loading && pages.itemCount == 0
                            )
                                "Loading…"
                            else
                                countLabel(pages.itemCount, "item") +
                                    if (browsing && parent == null) " · Unfiled" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = Paper.Muted,
                            maxLines = 1,
                        )
                }
                if (short && !selecting) search(Modifier.weight(1f).padding(vertical = 4.dp))
                if (!selecting) {
                    PaperAction(
                        "Add item",
                        PaperGlyph.Add,
                        Modifier.testTag("add-item"),
                        enabled = !busy,
                        action = onAdd,
                    )
                    PaperAction("Lock", PaperGlyph.Lock) { model.lock() }
                }
                Box {
                    PaperAction("Sort", PaperGlyph.More) { sorting = true }
                    DropdownMenu(sorting, { sorting = false }) {
                        DropdownMenuItem(
                            text = { Text(if (selecting) "Done" else "Select") },
                            onClick = {
                                selecting = !selecting
                                chosen = emptySet()
                                sorting = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("New folder") },
                            onClick = {
                                creating = true
                                sorting = false
                            },
                            leadingIcon = { PaperIcon(PaperGlyph.FolderAdd) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (grid) "List view" else "Grid view") },
                            onClick = {
                                model.toggleGrid()
                                sorting = false
                            },
                            leadingIcon = {
                                PaperIcon(if (grid) PaperGlyph.List else PaperGlyph.Grid)
                            },
                        )
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
            if (!selecting && !short) {
                if (browsing && parent != null)
                    Box(Modifier.padding(horizontal = 8.dp)) { FolderBreadcrumb(model, parent) }
                search(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    filters.forEach { (key, label) ->
                        FilterChip(
                            selected = category == key,
                            onClick = { model.showLibrary(key) },
                            label = { Text(label, maxLines = 1) },
                            shape = RoundedCornerShape(14.dp),
                        )
                    }
                }
            }
            if (browsing && query.isNotBlank())
                TextButton({
                    model.folderBrowsing.value = false
                    model.folderId.value = null
                    model.showLibrary("all")
                }) {
                    Text("Search entire vault")
                }
            LazyVerticalGrid(
                columns = if (grid) GridCells.Adaptive(152.dp) else GridCells.Fixed(1),
                state = state,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("vault-library"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    visibleFolders,
                    key = { "folder/${it.folder.id}" },
                    span = { GridItemSpan(maxLineSpan) },
                ) {
                    FolderRow(model, it)
                }
                if (visibleFolders.isNotEmpty() && pages.itemCount > 0)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (parent == null) "Unfiled" else "Files",
                            Modifier.padding(top = 8.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = Paper.Muted,
                        )
                    }
                items(count = pages.itemCount, key = { pages.peek(it)?.id ?: "placeholder/$it" }) {
                    index ->
                    val item = pages[index]
                    if (item != null) {
                        val checked = item.id in chosen
                        Row(
                            Modifier.fillMaxWidth()
                                .background(
                                    if (checked) Paper.AccentSoft else Paper.Surface,
                                    RoundedCornerShape(12.dp),
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selecting)
                                Checkbox(
                                    checked,
                                    { chosen = if (it) chosen + item.id else chosen - item.id },
                                    modifier = Modifier.testTag("select-${item.id}"),
                                )
                            Box(Modifier.weight(1f)) {
                                VaultEntry(
                                    model,
                                    item,
                                    grid,
                                    selecting,
                                    {
                                        chosen = if (checked) chosen - item.id else chosen + item.id
                                    },
                                    { deleting = item },
                                )
                            }
                        }
                    }
                }
                if (pages.loadState.refresh is LoadState.Loading)
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                if (
                    pages.loadState.refresh is LoadState.Error ||
                        pages.loadState.append is LoadState.Error
                )
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        TextButton({ pages.retry() }) { Text("Try again") }
                    }
                if (
                    pages.itemCount == 0 &&
                        visibleFolders.isEmpty() &&
                        pages.loadState.refresh is LoadState.NotLoading
                )
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PaperFileBadge("application/octet-stream")
                            Text(
                                if (query.isBlank()) "Your vault, ready for you"
                                else "Nothing found",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (query.isBlank()) "Add a note, file or folder."
                                else "Try another name or phrase.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Paper.Muted,
                            )
                            if (query.isBlank()) TextButton(onAdd) { Text("Add something") }
                        }
                    }
                if (browsing)
                    items(jobs, key = { "job/${it.id}" }, span = { GridItemSpan(maxLineSpan) }) {
                        job ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Import · ${job.completed} done · ${job.failed} failed",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton({ model.resumeImport(job) }, enabled = !busy) {
                                Text("Resume")
                            }
                        }
                    }
            }
            if (selecting) {
                HorizontalDivider(color = Paper.Line)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    @Composable
                    fun action(
                        label: String,
                        icon: PaperGlyph,
                        enabled: Boolean = true,
                        click: () -> Unit,
                    ) {
                        Column(
                            Modifier.weight(1f)
                                .heightIn(min = 64.dp)
                                .clickable(enabled = enabled, onClick = click)
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            PaperIcon(
                                icon,
                                color =
                                    if (enabled) Paper.Accent else Paper.Muted.copy(alpha = .4f),
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (enabled) Paper.Ink else Paper.Muted.copy(alpha = .4f),
                                maxLines = 1,
                            )
                        }
                    }
                    action("Move", PaperGlyph.Move, chosen.isNotEmpty()) { moving = true }
                    action("Rename", PaperGlyph.Draw, chosen.size == 1) {
                        newName =
                            pages.itemSnapshotList.items
                                .firstOrNull { it.id in chosen }
                                ?.name
                                .orEmpty()
                        renaming = true
                    }
                    action("Delete", PaperGlyph.Trash, chosen.isNotEmpty()) { bulkDelete = true }
                    action("More", PaperGlyph.More) { more = true }
                }
            }
        }
    }
    if (creating) FolderNameDialog(model, null, if (browsing) parent else null) { creating = false }
    if (moving)
        FolderPicker(model, { moving = false }) {
            model.moveFiles(chosen.toList(), it)
            chosen = emptySet()
            selecting = false
        }
    if (more)
        PaperSheet("Selection", { more = false }) {
            TextButton(
                {
                    more = false
                    grouping = true
                },
                enabled = chosen.isNotEmpty(),
            ) {
                Text("By type")
            }
            TextButton({
                chosen = emptySet()
                more = false
            }) {
                Text("Clear")
            }
        }
    if (grouping)
        PaperSheet(
            "Arrange by type?",
            { grouping = false },
            footer = {
                Button(
                    {
                        model.groupFiles(chosen.toList())
                        chosen = emptySet()
                        selecting = false
                        grouping = false
                    },
                    Modifier.fillMaxWidth(),
                ) {
                    Text("Arrange")
                }
            },
        ) {
            Text(
                "Move selected items into file-type folders in this location. Encrypted contents are kept."
            )
        }
    if (renaming)
        PaperSheet(
            "Rename file",
            { renaming = false },
            footer = {
                Button(
                    {
                        chosen.singleOrNull()?.let { model.renameNote(it, newName.trim()) }
                        renaming = false
                    },
                    Modifier.fillMaxWidth(),
                    enabled = newName.isNotBlank(),
                ) {
                    Text("Save")
                }
            },
        ) {
            OutlinedTextField(
                newName,
                { newName = it.take(255) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().secureEditing(),
            )
        }
    if (bulkDelete)
        ConfirmPaperDelete("${chosen.size} selected items", { bulkDelete = false }) {
            model.deleteFiles(chosen.toList())
            chosen = emptySet()
            bulkDelete = false
            selecting = false
        }
    deleting?.let { item ->
        ConfirmPaperDelete(item.name, { deleting = null }) {
            model.delete(item.id)
            deleting = null
        }
    }
}

@Composable
private fun VaultEntry(
    model: VaultModel,
    item: ItemSummary,
    grid: Boolean,
    selecting: Boolean,
    select: () -> Unit,
    remove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val visual = item.mime.startsWith("image/") || item.mime.startsWith("video/")
    @Composable
    fun options() {
        Box {
            PaperAction("Options for ${item.name}", PaperGlyph.More) { menu = true }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (item.pinned) "Unpin" else "Pin") },
                    onClick = {
                        model.pin(item.id)
                        menu = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menu = false
                        remove()
                    },
                )
            }
        }
    }
    val detail =
        if (item.mime == "application/x-securenote") item.snippet.ifBlank { "Empty note" }
        else "${fileKind(item.mime, item.name)} · ${fileSize(item.size)}"
    val click = { if (selecting) select() else model.navigate(item.id) }
    if (grid)
        Card(
            onClick = click,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Paper.Surface),
            border = BorderStroke(1.dp, Paper.Line),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (visual) PaperThumbnail(model, item, Modifier.fillMaxWidth().height(112.dp))
            Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                Text(
                    item.name,
                    Modifier.padding(end = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        detail,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = Paper.Muted,
                    )
                    if (!selecting) options()
                }
            }
        }
    else
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 72.dp)
                .clickable(onClick = click)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (visual)
                PaperThumbnail(model, item, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            else PaperFileBadge(item.mime, name = item.name)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Paper.Muted,
                )
                item.folderName?.let {
                    if (!model.folderBrowsing.value)
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            color = Paper.Muted,
                        )
                }
            }
            if (!selecting && item.pinned)
                PaperIcon(PaperGlyph.Pin, "Pinned", Modifier.size(14.dp), Paper.Accent)
            if (!selecting) options()
        }
}

@Composable
internal fun PaperThumbnail(model: VaultModel, item: ItemSummary, modifier: Modifier) {
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
