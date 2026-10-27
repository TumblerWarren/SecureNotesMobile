package app.securenote.vault

import android.graphics.Typeface
import android.text.*
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.widget.EditText
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.catch
import org.json.JSONArray

/** Each edit saves one encrypted block, independent of total note/vault size. */
@Composable
fun NativeNoteEditor(model: VaultModel, item: VaultItem) {
    var ready by remember(item.id) { mutableStateOf(false) }
    var title by remember(item.id) { mutableStateOf(item.name) }
    var active by remember { mutableStateOf<EditText?>(null) }
    var linkDialog by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    LaunchedEffect(item.id) {
        model.store.ensureBlocks(item.id)
        ready = true
    }
    if (!ready) return
    val blocks by
        remember(item.id) {
                model.store.dao.observeBlocks(item.id).catch {
                    model.message.value = "Note is unavailable."
                }
            }
            .collectAsState(emptyList())
    OutlinedTextField(
        title,
        {
            title = it
            model.renameNote(item.id, it)
        },
        label = { Text("Title") },
        modifier = Modifier.fillMaxWidth().testTag("note-title"),
    )
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        fun style(span: Any) {
            active?.let { edit ->
                val start = minOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(0)
                val end = maxOf(edit.selectionStart, edit.selectionEnd).coerceAtLeast(start)
                if (end > start) {
                    edit.text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    edit.text = edit.text
                    edit.setSelection(end)
                }
            }
        }
        TextButton({ style(StyleSpan(Typeface.BOLD)) }) { Text("Bold") }
        TextButton({ style(StyleSpan(Typeface.ITALIC)) }) { Text("Italic") }
        TextButton({ style(UnderlineSpan()) }) { Text("Underline") }
        TextButton({ active?.let { it.text.insert(it.selectionStart.coerceAtLeast(0), "\n• ") } }) {
            Text("List")
        }
        TextButton({ linkDialog = true }) { Text("Link") }
        TextButton({ model.addBlock(item.id, "text") }) { Text("Paragraph") }
        TextButton({ model.addBlock(item.id, "table", "[[\"\",\"\"],[\"\",\"\"]]") }) {
            Text("Table")
        }
        TextButton({ model.addBlock(item.id, "drawing", "[]") }) { Text("Drawing") }
        TextButton({ model.beginAttachment(item.id) }) { Text("Attach file") }
    }
    if (linkDialog)
        AlertDialog(
            onDismissRequest = { linkDialog = false },
            title = { Text("Add link") },
            text = { OutlinedTextField(link, { link = it }, label = { Text("https://…") }) },
            confirmButton = {
                TextButton({
                    val safe = runCatching { android.net.Uri.parse(link) }.getOrNull()
                    if (safe?.scheme in listOf("https", "http"))
                        active?.let { edit ->
                            val start = edit.selectionStart.coerceAtLeast(0)
                            edit.text.insert(start, link)
                            edit.text.setSpan(
                                android.text.style.URLSpan(link),
                                start,
                                start + link.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                            edit.text = edit.text
                        }
                    link = ""
                    linkDialog = false
                }) {
                    Text("Insert")
                }
            },
            dismissButton = { TextButton({ linkDialog = false }) { Text("Cancel") } },
        )
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(blocks, key = { it.id }) { block ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    when (block.kind) {
                        "table" ->
                            TableBlock(block) { content, plain ->
                                model.queueBlock(block.copy(content = content, searchText = plain))
                            }
                        "drawing" ->
                            DrawingBlock(block) { model.queueBlock(block.copy(content = it)) }
                        "attachment" ->
                            TextButton({ model.navigate(block.content) }) {
                                Text("Open encrypted attachment")
                            }
                        else ->
                            RichTextBlock(block, { active = it }) { html, plain ->
                                model.queueBlock(block.copy(content = html, searchText = plain))
                            }
                    }
                    Row {
                        TextButton({ model.moveBlock(block, -1) }) { Text("Move up") }
                        TextButton({ model.moveBlock(block, 1) }) { Text("Move down") }
                        TextButton({ model.removeBlock(block) }) { Text("Remove block") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RichTextBlock(
    block: NoteBlock,
    focus: (EditText) -> Unit,
    onEdit: (String, String) -> Unit,
) {
    val editCallback by rememberUpdatedState(onEdit)
    AndroidView(
        factory = { context ->
            SecureEditText(context).apply {
                setText(Html.fromHtml(block.content, Html.FROM_HTML_MODE_LEGACY))
                setTextColor(android.graphics.Color.WHITE)
                setHintTextColor(android.graphics.Color.GRAY)
                hint = "Write a note…"
                minLines = 2
                maxLines = 16
                textSize = 18f
                setSelectAllOnFocus(false)
                restrictExternalActions()
                imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                setOnFocusChangeListener { _, hasFocus -> if (hasFocus) focus(this) }
                addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int,
                        ) {}

                        override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int,
                        ) {}

                        override fun afterTextChanged(s: Editable?) {
                            s ?: return
                            editCallback(
                                Html.toHtml(s, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE),
                                s.toString(),
                            )
                        }
                    }
                )
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("native-note-body"),
        onRelease = {
            it.text.clear()
            it.setOnFocusChangeListener(null)
        },
    )
}

@Composable
private fun TableBlock(block: NoteBlock, onEdit: (String, String) -> Unit) {
    var rows by
        remember(block.id) {
            mutableStateOf(
                runCatching { JSONArray(block.content) }
                    .getOrElse { JSONArray("[[\"\",\"\"],[\"\",\"\"]]") }
            )
        }
    fun update(next: JSONArray) {
        rows = next
        val text = buildString {
            for (i in 0 until next.length()) {
                val row = next.getJSONArray(i)
                for (j in 0 until row.length()) append(row.optString(j)).append(' ')
            }
        }
        onEdit(next.toString(), text)
    }
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        for (rowIndex in 0 until rows.length()) Row {
            val row = rows.getJSONArray(rowIndex)
            for (col in 0 until row.length()) OutlinedTextField(
                row.optString(col),
                { value ->
                    val next = JSONArray(rows.toString())
                    next.getJSONArray(rowIndex).put(col, value)
                    update(next)
                },
                modifier = Modifier.width(150.dp),
                singleLine = false,
            )
        }
        Row {
            TextButton({
                if (rows.length() < 20) {
                    val next = JSONArray(rows.toString())
                    val row = JSONArray()
                    repeat(if (next.length() > 0) next.getJSONArray(0).length() else 2) {
                        row.put("")
                    }
                    next.put(row)
                    update(next)
                }
            }) {
                Text("Add row")
            }
            TextButton({
                if (rows.length() > 0 && rows.getJSONArray(0).length() < 12) {
                    val next = JSONArray(rows.toString())
                    repeat(next.length()) { next.getJSONArray(it).put("") }
                    update(next)
                }
            }) {
                Text("Add column")
            }
        }
    }
}

private data class InkStroke(
    val points: List<Offset>,
    val color: Int = android.graphics.Color.BLACK,
    val width: Float = 4f,
)

@Composable
private fun DrawingBlock(block: NoteBlock, onEdit: (String) -> Unit) {
    fun decode(value: String): List<InkStroke> = runCatching {
        val data = JSONArray(value)
        List(data.length()) { i ->
            val obj = data.optJSONObject(i)
            val points = obj?.getJSONArray("points") ?: data.getJSONArray(i)
            InkStroke(
                List(points.length()) { j ->
                    val xy = points.getJSONArray(j)
                    Offset(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat())
                },
                obj?.optInt("color", android.graphics.Color.BLACK) ?: android.graphics.Color.BLACK,
                obj?.optDouble("width", 4.0)?.toFloat() ?: 4f,
            )
        }
    }
        .getOrDefault(emptyList())
    var strokes by remember(block.id) { mutableStateOf(decode(block.content)) }
    var current by remember { mutableStateOf(emptyList<Offset>()) }
    var color by remember { mutableIntStateOf(android.graphics.Color.BLACK) }
    var thickness by remember { mutableFloatStateOf(4f) }
    var erasing by remember { mutableStateOf(false) }
    var dimensions by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val saveCallback by rememberUpdatedState(onEdit)
    fun save() {
        val array = JSONArray()
        strokes.forEach { stroke ->
            val points = JSONArray()
            stroke.points.forEach {
                points.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble()))
            }
            array.put(
                org.json
                    .JSONObject()
                    .put("points", points)
                    .put("color", stroke.color)
                    .put("width", stroke.width.toDouble())
            )
        }
        saveCallback(array.toString())
    }
    fun path(points: List<Offset>): androidx.compose.ui.graphics.Path =
        androidx.compose.ui.graphics.Path().apply {
            points.firstOrNull()?.let { moveTo(it.x * dimensions.width, it.y * dimensions.height) }
            for (i in 1 until points.size) lineTo(
                points[i].x * dimensions.width,
                points[i].y * dimensions.height,
            )
        }
    val paths = remember(strokes, dimensions) { strokes.map { path(it.points) } }
    val currentPath = remember(current, dimensions) { path(current) }
    fun erase(point: Offset) {
        strokes = strokes.filterNot { stroke ->
            stroke.points.any { p -> (p - point).getDistanceSquared() < 0.0016f }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        listOf(
                android.graphics.Color.BLACK,
                android.graphics.Color.RED,
                android.graphics.Color.BLUE,
                android.graphics.Color.rgb(0, 130, 80),
            )
            .forEach { value ->
                TextButton({
                    color = value
                    erasing = false
                }) {
                    Text("●", color = Color(value))
                }
            }
        TextButton({ erasing = !erasing }) { Text(if (erasing) "Eraser on" else "Eraser") }
        TextButton({
            if (strokes.isNotEmpty()) {
                strokes = strokes.dropLast(1)
                save()
            }
        }) {
            Text("Undo")
        }
        TextButton({
            strokes = emptyList()
            save()
        }) {
            Text("Clear")
        }
    }
    Slider(thickness, { thickness = it }, valueRange = 1f..12f)
    Canvas(
        Modifier.fillMaxWidth()
            .height(240.dp)
            .background(Color.White)
            .onSizeChanged { dimensions = it }
            .pointerInput(block.id, erasing, color, thickness) {
                detectDragGestures(
                    onDragStart = { p ->
                        val point = Offset(p.x / size.width, p.y / size.height)
                        if (erasing) erase(point) else current = listOf(point)
                    },
                    onDragEnd = {
                        if (!erasing && strokes.sumOf { it.points.size } < 50000)
                            strokes = strokes + InkStroke(current, color, thickness)
                        current = emptyList()
                        save()
                    },
                    onDragCancel = { current = emptyList() },
                ) { change, _ ->
                    change.consume()
                    val point =
                        Offset(
                            (change.position.x / size.width).coerceIn(0f, 1f),
                            (change.position.y / size.height).coerceIn(0f, 1f),
                        )
                    if (erasing) erase(point)
                    else if (current.size < 5000) current = current + point
                }
            }
    ) {
        strokes.forEachIndexed { i, stroke ->
            drawPath(
                paths[i],
                Color(stroke.color),
                style =
                    androidx.compose.ui.graphics.drawscope.Stroke(
                        stroke.width,
                        cap = StrokeCap.Round,
                    ),
            )
        }
        drawPath(
            currentPath,
            Color(color),
            style = androidx.compose.ui.graphics.drawscope.Stroke(thickness, cap = StrokeCap.Round),
        )
    }
}
