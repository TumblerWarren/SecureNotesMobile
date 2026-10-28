package app.securenote.vault

import android.graphics.Typeface
import android.text.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private data class NoteLinkTarget(
    val edit: NoteEditText,
    val start: Int,
    val end: Int,
    val revision: Long,
)

/** Stable block drafts with native text selection and per-block encrypted autosaves. */
@Composable
fun NativeNoteEditor(model: VaultModel, item: VaultItem) {
    val session = remember(item.id) { NoteEditingSession() }
    var ready by remember(item.id) { mutableStateOf(false) }
    var failure by remember(item.id) { mutableStateOf("") }
    var title by remember(item.id) { mutableStateOf(item.name) }
    var active by remember(item.id) { mutableStateOf<NoteEditText?>(null) }
    var linkTarget by remember(item.id) { mutableStateOf<NoteLinkTarget?>(null) }
    var link by remember(item.id) { mutableStateOf("") }
    val busy by model.busy.collectAsState()
    val saveStatus by model.saveStatus.collectAsState()
    var toolRevision by remember { mutableIntStateOf(0) }
    var insertMenu by remember { mutableStateOf(false) }
    var managingBlocks by remember { mutableStateOf(false) }
    DisposableEffect(session) {
        fun clear() {
            session.close()
            active = null
            linkTarget = null
            link = ""
            title = ""
        }
        val unregister = model.onLock(::clear)
        onDispose {
            unregister()
            clear()
        }
    }
    LaunchedEffect(item.id) {
        try {
            // Reopening must not hydrate from a database row older than a pending edit.
            model.prepareNote(item.id)
            ready = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = "This note could not be loaded. Its stored content is unchanged."
        }
    }
    if (failure.isNotEmpty()) {
        Text(failure, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
        return
    }
    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val blocks by
        remember(item.id) {
                model.observeNoteBlocks(item.id).catch {
                    failure = "This note could not be loaded. Its stored content is unchanged."
                }
            }
            .collectAsState(emptyList())
    LaunchedEffect(blocks) { session.retainBlocks(blocks.map { it.id }.toSet()) }
    fun target(): NoteEditText? = active?.takeIf(session::contains)
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicTextField(
                        title,
                        {
                            title = it.take(255)
                            model.renameNote(item.id, title)
                        },
                        textStyle = MaterialTheme.typography.headlineMedium.copy(color = Paper.Ink),
                        cursorBrush = SolidColor(Paper.Accent),
                        modifier =
                            Modifier.fillMaxWidth()
                                .secureEditing()
                                .onFocusChanged { if (it.isFocused) active = null }
                                .testTag("note-title"),
                        decorationBox = { field ->
                            Box {
                                if (title.isEmpty())
                                    Text(
                                        "Untitled",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = Paper.Muted,
                                    )
                                field()
                            }
                        },
                    )
                    Text(
                        "$saveStatus · ${itemDate(item.updated)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Paper.Muted,
                    )
                }
            }
            items(blocks, key = { it.id }, contentType = { it.kind }) { block ->
                Box(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(end = if (managingBlocks) 48.dp else 0.dp)
                    ) {
                        when (block.kind) {
                            "table" ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = Paper.Surface,
                                    border = BorderStroke(1.dp, Paper.Line),
                                ) {
                                    Box(Modifier.padding(8.dp)) {
                                        TableBlock(block, session, { active = null }) { table ->
                                            session.edited(block.id, table)
                                            model.queueTable(block, table)
                                        }
                                    }
                                }
                            "drawing" ->
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = Paper.Surface,
                                    border = BorderStroke(1.dp, Paper.Line),
                                ) {
                                    Column(Modifier.padding(8.dp)) {
                                        DrawingBlock(block, session) { strokes, aspect, paper ->
                                            model.queueDrawing(block, strokes, aspect, paper)
                                        }
                                    }
                                }
                            "attachment" ->
                                Surface(shape = MaterialTheme.shapes.small, color = Paper.Inset) {
                                    TextButton(
                                        { model.navigate(block.content) },
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(16.dp),
                                    ) {
                                        PaperIcon(PaperGlyph.Attach)
                                        Spacer(Modifier.width(8.dp))
                                        AttachmentLabel(model, block.content)
                                    }
                                }
                            else ->
                                RichTextBlock(
                                    block,
                                    session,
                                    { edit, focused ->
                                        if (focused) {
                                            active = edit
                                            edit.onSelection = { toolRevision++ }
                                        } else if (active === edit) active = null
                                    },
                                ) { snapshot ->
                                    session.edited(block.id, snapshot)
                                    model.queueRichText(block, snapshot)
                                }
                        }
                    }
                    var options by remember(block.id) { mutableStateOf(false) }
                    if (managingBlocks)
                        Box(Modifier.align(Alignment.TopEnd)) {
                            IconButton(
                                { options = true },
                                enabled = !busy,
                                modifier = Modifier.size(48.dp),
                            ) {
                                PaperIcon(PaperGlyph.More, "Block options", color = Paper.Muted)
                            }
                            DropdownMenu(options, { options = false }) {
                                DropdownMenuItem(
                                    text = { Text("Move up") },
                                    onClick = {
                                        options = false
                                        model.moveBlock(block, -1)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Move down") },
                                    onClick = {
                                        options = false
                                        model.moveBlock(block, 1)
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Remove block",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        options = false
                                        model.removeBlock(block)
                                    },
                                )
                            }
                        }
                }
            }
            item(key = "paragraph") {
                TextButton({ model.addBlock(item.id, "text") }, enabled = !busy) {
                    PaperIcon(PaperGlyph.Add)
                    Spacer(Modifier.width(8.dp))
                    Text("Add paragraph")
                }
            }
        }
        HorizontalDivider(color = Paper.Line)
        Row(
            Modifier.fillMaxWidth()
                .background(Paper.Surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val revision = toolRevision
            fun state(kind: String): FormatState {
                revision.hashCode()
                return target()?.formatState(kind) ?: FormatState.Off
            }
            PaperEditorTool(
                "Bold",
                modifier = Modifier.weight(1f),
                letter = "B",
                enabled = target() != null,
                state = state("bold"),
            ) {
                target()?.toggle("bold")
            }
            PaperEditorTool(
                "Italic",
                modifier = Modifier.weight(1f),
                letter = "I",
                enabled = target() != null,
                state = state("italic"),
            ) {
                target()?.toggle("italic")
            }
            PaperEditorTool(
                "Underline",
                modifier = Modifier.weight(1f),
                letter = "U",
                enabled = target() != null,
                state = state("underline"),
            ) {
                target()?.toggle("underline")
            }
            PaperEditorTool(
                "List",
                PaperGlyph.List,
                modifier = Modifier.weight(1f),
                enabled = target() != null,
                state = state("list"),
            ) {
                target()?.bullets()
            }
            PaperEditorTool(
                "Link",
                PaperGlyph.Link,
                modifier = Modifier.weight(1f),
                enabled = target() != null,
            ) {
                target()?.let { edit ->
                    linkTarget =
                        NoteLinkTarget(
                            edit,
                            minOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0),
                            maxOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0),
                            edit.revision,
                        )
                }
            }
            Box(Modifier.weight(1f)) {
                PaperEditorTool(
                    "Insert",
                    PaperGlyph.Add,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) {
                    insertMenu = true
                }
                DropdownMenu(insertMenu, { insertMenu = false }) {
                    listOf("Table", "Drawing", "Attachment", "Link", "Manage blocks").forEach {
                        label ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            enabled = label != "Link" || target() != null,
                            onClick = {
                                insertMenu = false
                                when (label) {
                                    "Table" ->
                                        model.addBlock(
                                            item.id,
                                            "table",
                                            "[[\"\",\"\"],[\"\",\"\"]]",
                                        )
                                    "Manage blocks" -> managingBlocks = !managingBlocks
                                    "Drawing" -> model.addBlock(item.id, "drawing", "[]")
                                    "Attachment" -> model.beginAttachment(item.id)
                                    else ->
                                        target()?.let { edit ->
                                            linkTarget =
                                                NoteLinkTarget(
                                                    edit,
                                                    minOf(edit.selectionStart, edit.selectionEnd)
                                                        .coerceAtLeast(0),
                                                    maxOf(edit.selectionStart, edit.selectionEnd)
                                                        .coerceAtLeast(0),
                                                    edit.revision,
                                                )
                                        }
                                }
                            },
                        )
                    }
                }
            }
            if (saveStatus == "Save failed") TextButton({ model.retrySaves() }) { Text("Retry") }
        }
    }

    linkTarget?.let { selected ->
        AlertDialog(
            modifier = Modifier.secureEditing(),
            onDismissRequest = {
                linkTarget = null
                link = ""
            },
            title = { Text("Add a link") },
            text = {
                OutlinedTextField(
                    link,
                    { link = it },
                    label = { Text("https://…") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    {
                        if (
                            !session.contains(selected.edit) ||
                                !selected.edit.link(
                                    link.trim(),
                                    selected.start,
                                    selected.end,
                                    selected.revision,
                                )
                        )
                            model.message.value = "Select the text again before adding a link."
                        link = ""
                        linkTarget = null
                    },
                    enabled = NoteEditingSession.safeLink(link.trim()),
                ) {
                    Text("Insert")
                }
            },
            dismissButton = {
                TextButton({
                    link = ""
                    linkTarget = null
                }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
internal fun PaperEditorTool(
    label: String,
    glyph: PaperGlyph? = null,
    letter: String? = null,
    enabled: Boolean = true,
    state: FormatState = FormatState.Off,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    TextButton(
        action,
        enabled = enabled,
        modifier =
            modifier.heightIn(min = 48.dp).semantics {
                contentDescription = label
                selected = state == FormatState.On
                stateDescription = if (!enabled) "Unavailable" else state.name
            },
        colors =
            ButtonDefaults.textButtonColors(
                containerColor =
                    if (enabled && state != FormatState.Off) Paper.AccentSoft else Color.Transparent
            ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val color = if (enabled) Paper.Ink else Paper.Muted.copy(alpha = .4f)
            if (glyph != null) PaperIcon(glyph, color = color)
            else
                Text(
                    letter.orEmpty(),
                    fontFamily = FontFamily.Serif,
                    style = MaterialTheme.typography.titleLarge,
                    fontStyle =
                        if (label == "Italic") androidx.compose.ui.text.font.FontStyle.Italic
                        else androidx.compose.ui.text.font.FontStyle.Normal,
                    fontWeight =
                        if (label == "Bold") androidx.compose.ui.text.font.FontWeight.Bold
                        else androidx.compose.ui.text.font.FontWeight.Normal,
                    textDecoration =
                        if (label == "Underline")
                            androidx.compose.ui.text.style.TextDecoration.Underline
                        else null,
                    color = color,
                )
            if (state == FormatState.Mixed)
                Text(
                    "−",
                    Modifier.align(Alignment.BottomEnd),
                    style = MaterialTheme.typography.labelSmall,
                )
        }
    }
}

@Composable
private fun RichTextBlock(
    block: NoteBlock,
    session: NoteEditingSession,
    focus: (NoteEditText, Boolean) -> Unit,
    onEdit: (SpannedString) -> Unit,
) {
    val editCallback by rememberUpdatedState(onEdit)
    val focusCallback by rememberUpdatedState(focus)
    val initial = remember(block.id) { runCatching { session.text(block) } }
    if (initial.isFailure) {
        Text(
            "This paragraph could not be edited. Its stored content is unchanged.",
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    AndroidView(
        factory = { context ->
            NoteEditText(context).apply {
                load(initial.getOrThrow())
                setTextColor(android.graphics.Color.rgb(39, 43, 40))
                setHintTextColor(android.graphics.Color.rgb(115, 110, 101))
                hint = "Write a note…"
                includeFontPadding = false
                minLines = 1
                maxLines = 24
                textSize = 18f
                typeface = Typeface.create("serif", Typeface.NORMAL)
                setLineSpacing(5f * resources.displayMetrics.density, 1f)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
                highlightColor = android.graphics.Color.rgb(243, 223, 213)
                setSelectAllOnFocus(false)
                this.onEdit = { editCallback(it) }
                this.onActive = { focusCallback(it, true) }
                session.attach(this)
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("native-note-body"),
        onRelease = {
            focusCallback(it, false)
            session.release(it)
        },
    )
}

@Composable
private fun TableBlock(
    block: NoteBlock,
    session: NoteEditingSession,
    onFocus: () -> Unit,
    onEdit: (NoteTable) -> Unit,
) {
    val decoded = remember(block.id) { runCatching { session.table(block) } }
    if (decoded.isFailure) {
        Text(
            "This table could not be edited. Its stored content is unchanged.",
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    var table by remember(block.id) { mutableStateOf(decoded.getOrThrow()) }
    fun update(next: NoteTable) {
        table = next
        onEdit(next)
    }
    val horizontal = rememberScrollState()
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cellWidth = (maxWidth / table.columns.coerceAtMost(3)).coerceAtLeast(104.dp)
        Column {
            Column(Modifier.fillMaxWidth().horizontalScroll(horizontal)) {
                table.rows.forEachIndexed { rowIndex, row ->
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        row.forEachIndexed { col, value ->
                            val active = focused == (rowIndex to col)
                            Box(
                                Modifier.width(cellWidth)
                                    .fillMaxHeight()
                                    .background(if (rowIndex == 0) Paper.Inset else Paper.Surface)
                                    .border(1.dp, if (active) Paper.Accent else Paper.Line)
                            ) {
                                BasicTextField(
                                    value,
                                    { update(table.cell(rowIndex, col, it)) },
                                    textStyle =
                                        MaterialTheme.typography.bodyMedium.copy(color = Paper.Ink),
                                    cursorBrush = SolidColor(Paper.Accent),
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .padding(10.dp)
                                            .secureEditing()
                                            .semantics {
                                                contentDescription =
                                                    "Cell ${rowIndex + 1}, ${col + 1}"
                                            }
                                            .onFocusChanged {
                                                if (it.isFocused) {
                                                    focused = rowIndex to col
                                                    onFocus()
                                                }
                                            },
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PaperAction(
                    "Add row",
                    PaperGlyph.RowAdd,
                    enabled = table.rows.size < NoteTable.MAX_ROWS,
                ) {
                    update(table.addRow())
                }
                PaperAction(
                    "Add column",
                    PaperGlyph.ColumnAdd,
                    enabled = table.columns < NoteTable.MAX_COLUMNS,
                ) {
                    update(table.addColumn())
                    scope.launch {
                        kotlinx.coroutines.delay(100)
                        horizontal.animateScrollTo(horizontal.maxValue)
                    }
                }
                if (table.columns > 2)
                    Text(
                        "Swipe for columns",
                        style = MaterialTheme.typography.labelSmall,
                        color = Paper.Muted,
                    )
            }
        }
    }
}

@Composable
private fun AttachmentLabel(model: VaultModel, id: String) {
    var item by remember(id) { mutableStateOf<VaultItem?>(null) }
    LaunchedEffect(id) { model.store.dao.observeDisplay(id).collect { item = it } }
    Column {
        Text(item?.name ?: "Attachment", maxLines = 2)
        item?.let {
            Text(
                "${fileKind(it.mime,it.name)} · ${fileSize(it.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = Paper.Muted,
            )
        }
    }
}
