package app.securenote.vault

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

internal data class NoteStroke(
    val points: List<Offset>,
    val color: Int,
    val width: Float,
    val id: String = java.util.UUID.randomUUID().toString(),
)

internal class NoteDrawing(value: String) {
    val decoded = runCatching { decode(value) }
    var strokes by mutableStateOf(decoded.getOrDefault(emptyList()))
    var aspect by
        mutableFloatStateOf(
            runCatching {
                    JSONObject(value).getDouble("aspect").toFloat().also {
                        require(it.isFinite() && it in 0.25f..4f)
                    }
                }
                .getOrDefault(0.7071f)
        )
    var paper by
        mutableStateOf(runCatching { JSONObject(value).getString("paper") }.getOrDefault("Blank"))
    private var legacySizePending = !value.trimStart().startsWith("{") && strokes.isNotEmpty()

    fun establishLegacySize(widthDp: Float) {
        if (!legacySizePending || widthDp <= 0f) return
        legacySizePending = false
        aspect = (widthDp / 240f).coerceIn(0.25f, 4f)
        strokes = strokes.map { it.copy(width = (it.width * 360f / widthDp).coerceIn(0.1f, 96f)) }
    }

    private val future = ArrayDeque<List<NoteStroke>>()
    var canRedo by mutableStateOf(false)
        private set

    private val history = ArrayDeque<List<NoteStroke>>()
    var canUndo by mutableStateOf(false)
        private set

    fun commit(before: List<NoteStroke>): Boolean {
        if (before == strokes) return false
        future.clear()
        canRedo = false
        history.addLast(before)
        while (
            history.size > 20 || history.sumOf { it.sumOf { line -> line.points.size } } > 200000
        ) history.removeFirst()
        canUndo = history.isNotEmpty()
        return true
    }

    fun undo(): Boolean {
        if (history.isEmpty()) return false
        future.addLast(strokes)
        canRedo = true
        strokes = history.removeLast()
        canUndo = history.isNotEmpty()
        return true
    }

    fun redo(): Boolean {
        if (future.isEmpty()) return false
        history.addLast(strokes)
        strokes = future.removeLast()
        canUndo = true
        canRedo = future.isNotEmpty()
        return true
    }

    fun encode(): String = encode(strokes, aspect, paper)

    companion object {
        fun encode(strokes: List<NoteStroke>, aspect: Float, paper: String): String =
            JSONArray()
                .apply {
                    strokes.forEach { stroke ->
                        put(
                            JSONObject()
                                .put("id", stroke.id)
                                .put("color", stroke.color)
                                .put("width", stroke.width.toDouble())
                                .put(
                                    "points",
                                    JSONArray().apply {
                                        stroke.points.forEach {
                                            put(
                                                JSONArray()
                                                    .put(it.x.toDouble())
                                                    .put(it.y.toDouble())
                                            )
                                        }
                                    },
                                )
                        )
                    }
                }
                .let {
                    JSONObject()
                        .put("version", 1)
                        .put("aspect", aspect.toDouble())
                        .put("paper", paper)
                        .put("strokes", it)
                        .toString()
                }

        const val MAX_POINTS = 50000
        const val MAX_STROKE_POINTS = 5000

        fun decode(value: String): List<NoteStroke> {
            val data =
                if (value.trimStart().startsWith("{")) {
                    val document = JSONObject(value)
                    require(document.getInt("version") == 1)
                    val aspect = document.getDouble("aspect")
                    require(aspect.isFinite() && aspect in 0.25..4.0)
                    require(document.getString("paper") in setOf("Blank", "Lined", "Grid", "Dots"))
                    document.getJSONArray("strokes")
                } else JSONArray(value)
            require(data.length() <= MAX_POINTS)
            var total = 0
            val seen = hashSetOf<String>()
            return List(data.length()) { i ->
                val obj = data.optJSONObject(i)
                val points = obj?.getJSONArray("points") ?: data.getJSONArray(i)
                total += points.length()
                require(total <= MAX_POINTS && points.length() in 1..MAX_STROKE_POINTS)
                val width = obj?.optDouble("width", 4.0)?.toFloat() ?: 4f
                require(width.isFinite() && width in 0.1f..96f)
                val id =
                    obj?.optString("id")?.takeIf { it.isNotBlank() }
                        ?: java.util.UUID.randomUUID().toString()
                require(id.length <= 64 && seen.add(id))
                NoteStroke(
                    List(points.length()) { j ->
                        val pair = points.getJSONArray(j)
                        val x = pair.getDouble(0).toFloat()
                        val y = pair.getDouble(1).toFloat()
                        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
                        Offset(x, y)
                    },
                    obj?.optInt("color", android.graphics.Color.BLACK)
                        ?: android.graphics.Color.BLACK,
                    width,
                    id,
                )
            }
        }

        /** Hit the line segments too; erasing must work between sparse sampled vertices. */
        fun hit(stroke: NoteStroke, point: Offset, size: IntSize, radius: Float): Boolean {
            fun px(p: Offset) = Offset(p.x * size.width, p.y * size.height)
            val target = px(point)
            val radiusSquared = radius * radius
            return stroke.points.indices.any { i ->
                val a = px(stroke.points[i])
                val b = px(stroke.points[maxOf(0, i - 1)])
                val delta = b - a
                val length = delta.getDistanceSquared()
                val t =
                    if (length == 0f) 0f
                    else
                        (((target.x - a.x) * delta.x + (target.y - a.y) * delta.y) / length)
                            .coerceIn(0f, 1f)
                (target - (a + delta * t)).getDistanceSquared() <= radiusSquared
            }
        }
    }
}

/** Mutable input is private to the gesture; persistence receives one immutable copy on commit. */
internal class LiveDrawingStroke {
    private val points = ArrayList<Offset>(256)
    val path = Path()
    var revision by mutableIntStateOf(0)
        private set

    val size
        get() = points.size

    fun isEmpty() = points.isEmpty()

    fun isNotEmpty() = points.isNotEmpty()

    fun last() = points.last()

    operator fun get(index: Int) = points[index]

    fun snapshot(): List<Offset> = points.toList()

    fun add(point: Offset, dimensions: IntSize, line: Boolean) {
        if (line && points.isNotEmpty()) {
            val first = points.first()
            points.clear()
            points.add(first)
            points.add(point)
            path.reset()
            path.moveTo(first.x * dimensions.width, first.y * dimensions.height)
            path.lineTo(point.x * dimensions.width, point.y * dimensions.height)
        } else {
            if (points.isEmpty())
                path.moveTo(point.x * dimensions.width, point.y * dimensions.height)
            else path.lineTo(point.x * dimensions.width, point.y * dimensions.height)
            points.add(point)
        }
        revision++
    }

    fun clear() {
        points.clear()
        path.reset()
        revision++
    }
}

private class StrokePathCache {
    private var dimensions = IntSize.Zero
    private val paths = mutableMapOf<String, Pair<NoteStroke, Path>>()

    fun resolve(strokes: List<NoteStroke>, size: IntSize): List<Path> {
        if (size != dimensions) {
            paths.clear()
            dimensions = size
        }
        paths.keys.retainAll(strokes.map { it.id }.toSet())
        return strokes.map { stroke ->
            val old = paths[stroke.id]
            if (old?.first === stroke) old.second
            else
                Path()
                    .apply {
                        stroke.points.forEachIndexed { i, p ->
                            if (i == 0) moveTo(p.x * size.width, p.y * size.height)
                            else lineTo(p.x * size.width, p.y * size.height)
                        }
                    }
                    .also { paths[stroke.id] = stroke to it }
        }
    }
}

@Composable
internal fun DrawingBlock(
    block: NoteBlock,
    session: NoteEditingSession,
    onEdit: (List<NoteStroke>, Float, String) -> Unit,
) {
    val drawing =
        remember(block.id) {
            session.draft("drawing/${block.id}") { NoteDrawing(session.content(block)) }
        }
    if (drawing.decoded.isFailure) {
        Text(
            "This drawing could not be edited. Its stored content is unchanged.",
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    var expanded by remember(block.id) { mutableStateOf(false) }
    if (!expanded) {
        Column {
            Box(Modifier.fillMaxWidth().heightIn(max = 220.dp).clipToBounds()) {
                DrawingPreview(drawing)
            }
            TextButton({ expanded = true }) { Text("Edit drawing") }
        }
        return
    }
    Dialog(
        onDismissRequest = { expanded = false },
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                securePolicy = SecureFlagPolicy.SecureOn,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false,
            ),
    ) {
        PaperDialogBars()
        Surface(Modifier.fillMaxSize(), color = Paper.Background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                val current = remember(block.id) { LiveDrawingStroke() }
                var color by remember(block.id) { mutableIntStateOf(android.graphics.Color.BLACK) }
                var thickness by remember(block.id) { mutableFloatStateOf(4f) }
                val landscape =
                    androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                var properties by remember { mutableStateOf(false) }
                var customColor by remember { mutableStateOf(false) }
                var hex by remember { mutableStateOf("") }
                var opacity by remember { mutableFloatStateOf(1f) }
                var navigating by remember { mutableStateOf(false) }
                var zoom by remember { mutableFloatStateOf(1f) }
                var pan by remember { mutableStateOf(Offset.Zero) }
                var notice by remember { mutableStateOf("") }
                var tool by remember { mutableStateOf("Pen") }
                var erasing by remember(block.id) { mutableStateOf(false) }
                var dimensions by remember { mutableStateOf(IntSize.Zero) }
                val saveCallback by rememberUpdatedState(onEdit)
                val density = androidx.compose.ui.platform.LocalDensity.current.density
                fun save() {
                    if (!session.closed)
                        saveCallback(drawing.strokes, drawing.aspect, drawing.paper)
                }
                val strokes = drawing.strokes
                val pathCache = remember { StrokePathCache() }
                val paths = remember(strokes, dimensions) { pathCache.resolve(strokes, dimensions) }
                var options by remember { mutableStateOf(false) }
                @Composable
                fun strokeProperties(showHeading: Boolean = true) {
                    Column(Modifier.padding(12.dp)) {
                        if (showHeading)
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (erasing) "Eraser" else tool,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                PaperAction("Hide properties", PaperGlyph.Close) {
                                    properties = false
                                }
                            }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            listOf(
                                    "Black" to android.graphics.Color.BLACK,
                                    "Red" to android.graphics.Color.rgb(169, 81, 63),
                                    "Green" to android.graphics.Color.rgb(47, 106, 76),
                                    "Blue" to android.graphics.Color.rgb(49, 95, 145),
                                )
                                .forEach { (name, value) ->
                                    IconButton(
                                        {
                                            color = value
                                            erasing = false
                                            navigating = false
                                        },
                                        modifier =
                                            Modifier.semantics {
                                                contentDescription = "$name pen"
                                                selected = color == value && !erasing
                                            },
                                    ) {
                                        Box(
                                            Modifier.size(30.dp)
                                                .background(
                                                    Color(value),
                                                    androidx.compose.foundation.shape
                                                        .RoundedCornerShape(10.dp),
                                                )
                                                .border(
                                                    if (color == value) 2.dp else 0.dp,
                                                    Paper.Accent,
                                                    androidx.compose.foundation.shape
                                                        .RoundedCornerShape(10.dp),
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            if (color == value)
                                                PaperIcon(
                                                    PaperGlyph.Check,
                                                    color = Color.White,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                        }
                                    }
                                }
                            PaperAction("Custom color", PaperGlyph.Add) {
                                customColor = true
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Width", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "${thickness.toInt()} px",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        PaperSlider(
                            thickness,
                            { thickness = it },
                            range = 1f..12f,
                            modifier =
                                Modifier.height(36.dp).semantics {
                                    contentDescription = "Pen width"
                                },
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Opacity", style = MaterialTheme.typography.labelMedium)
                            Text(
                                "${(opacity * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        PaperSlider(
                            opacity,
                            { opacity = it },
                            range = .1f..1f,
                            modifier =
                                Modifier.height(36.dp).semantics {
                                    contentDescription = "Pen opacity"
                                },
                        )
                    }
                }
                if (properties && landscape)
                    PaperSheet(
                        if (erasing) "Eraser" else "$tool properties",
                        { properties = false },
                    ) {
                        strokeProperties(showHeading = false)
                    }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Drawing",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    PaperAction("Undo", PaperGlyph.Undo, enabled = drawing.canUndo) {
                        if (drawing.undo()) save()
                    }
                    PaperAction("Redo", PaperGlyph.Redo, enabled = drawing.canRedo) {
                        if (drawing.redo()) save()
                    }
                    PaperAction("Drawing options", PaperGlyph.More) { options = true }
                    PaperAction("Done", PaperGlyph.Check) { expanded = false }
                }
                BoxWithConstraints(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(Color.White)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(
                        Modifier.fillMaxWidth()
                            .aspectRatio(drawing.aspect)
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = pan.x
                                translationY = pan.y
                            }
                            .background(Color.White)
                            .semantics { contentDescription = "Note drawing canvas" }
                            .onSizeChanged { dimensions = it }
                            .pointerInput(navigating) {
                                if (navigating)
                                    detectTransformGestures { _, delta, scale, _ ->
                                        val next = (zoom * scale).coerceIn(1f, 8f)
                                        val limitX = dimensions.width * (next - 1) / 2
                                        val limitY = dimensions.height * (next - 1) / 2
                                        pan =
                                            Offset(
                                                (pan.x + delta.x).coerceIn(-limitX, limitX),
                                                (pan.y + delta.y).coerceIn(-limitY, limitY),
                                            )
                                        zoom = next
                                    }
                            }
                            .pointerInput(
                                block.id,
                                erasing,
                                color,
                                thickness,
                                density,
                                navigating,
                                tool,
                                opacity,
                            ) {
                                if (navigating) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (session.closed || size.width == 0 || size.height == 0)
                                        return@awaitEachGesture
                                    down.consume()
                                    notice = ""
                                    val before = drawing.strokes
                                    fun normalized(p: Offset) =
                                        Offset(
                                            (p.x / size.width).coerceIn(0f, 1f),
                                            (p.y / size.height).coerceIn(0f, 1f),
                                        )
                                    fun sample(p: Offset) {
                                        val point = normalized(p)
                                        if (erasing)
                                            drawing.strokes =
                                                drawing.strokes.filterNot {
                                                    NoteDrawing.hit(
                                                        it,
                                                        point,
                                                        dimensions,
                                                        12f * density +
                                                            it.width * dimensions.width / 720f,
                                                    )
                                                }
                                        else if (current.size >= NoteDrawing.MAX_STROKE_POINTS) {
                                            notice =
                                                "Stroke limit reached. Lift to start another stroke."
                                        } else if (
                                            (current.isEmpty() ||
                                                Offset(
                                                        (point.x - current.last().x) * size.width,
                                                        (point.y - current.last().y) * size.height,
                                                    )
                                                    .getDistanceSquared() >= density * density)
                                        )
                                            current.add(point, dimensions, tool == "Line")
                                    }
                                    try {
                                        sample(down.position)
                                        while (true) {
                                            val change =
                                                awaitPointerEvent().changes.firstOrNull {
                                                    it.id == down.id
                                                } ?: break
                                            if (session.closed || change.isConsumed) {
                                                drawing.strokes = before
                                                current.clear()
                                                return@awaitEachGesture
                                            }
                                            change.consume()
                                            sample(change.position)
                                            if (!change.pressed) break
                                        }
                                        if (
                                            !erasing &&
                                                current.isNotEmpty() &&
                                                before.sumOf { it.points.size } + current.size <=
                                                    NoteDrawing.MAX_POINTS
                                        )
                                            drawing.strokes =
                                                before +
                                                    NoteStroke(
                                                        current.snapshot(),
                                                        (color and 0x00ffffff) or
                                                            ((opacity * 255).toInt() shl 24),
                                                        thickness,
                                                    )
                                        else if (!erasing && current.isNotEmpty())
                                            notice = "This page is full. Add another drawing block."
                                        current.clear()
                                        if (drawing.commit(before)) save()
                                    } catch (e: CancellationException) {
                                        drawing.strokes = before
                                        current.clear()
                                        throw e
                                    }
                                }
                            }
                    ) {
                        current.revision // Subscribe only the draw phase to live input.
                        drawPaper(drawing.paper)
                        strokes.forEachIndexed { i, stroke ->
                            if (stroke.points.size == 1)
                                drawCircle(
                                    Color(stroke.color),
                                    stroke.width * size.width / 360f / 2,
                                    Offset(
                                        stroke.points[0].x * size.width,
                                        stroke.points[0].y * size.height,
                                    ),
                                )
                            else
                                drawPath(
                                    paths[i],
                                    Color(stroke.color),
                                    style =
                                        Stroke(
                                            stroke.width * size.width / 360f,
                                            cap = StrokeCap.Round,
                                        ),
                                )
                        }
                        if (current.size == 1)
                            drawCircle(
                                Color((color and 0x00ffffff) or ((opacity * 255).toInt() shl 24)),
                                thickness * size.width / 360f / 2,
                                Offset(current[0].x * size.width, current[0].y * size.height),
                            )
                        else
                            drawPath(
                                current.path,
                                Color((color and 0x00ffffff) or ((opacity * 255).toInt() shl 24)),
                                style =
                                    Stroke(thickness * size.width / 360f, cap = StrokeCap.Round),
                            )
                    }
                    if (properties && !landscape)
                        Surface(
                            Modifier.align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .heightIn(max = minOf(320.dp, maxHeight * .70f)),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                            color = Paper.Background,
                            tonalElevation = 3.dp,
                        ) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                strokeProperties()
                            }
                        }
                }
                if (notice.isNotEmpty())
                    Text(
                        notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                    )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.padding(horizontal = 8.dp)
                            .size(24.dp)
                            .background(
                                Color(color),
                                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            )
                    )
                    Text(
                        "${thickness.toInt()} px",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    PaperAction("Fit", PaperGlyph.Fit) {
                        zoom = 1f
                        pan = Offset.Zero
                    }
                    PaperAction("Color & stroke", PaperGlyph.Sliders, selected = properties) {
                        properties = !properties
                    }
                }
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                    color = Paper.Surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Paper.Line),
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        PaperAction(
                            "Pen",
                            PaperGlyph.Draw,
                            Modifier.weight(1f),
                            selected = tool == "Pen" && !erasing && !navigating,
                        ) {
                            tool = "Pen"
                            erasing = false
                            navigating = false
                            opacity = 1f
                        }
                        PaperAction(
                            "Highlighter",
                            PaperGlyph.Marker,
                            Modifier.weight(1f),
                            selected = tool == "Highlighter" && !erasing && !navigating,
                        ) {
                            tool = "Highlighter"
                            erasing = false
                            navigating = false
                            opacity = .3f
                        }
                        PaperAction(
                            "Eraser",
                            PaperGlyph.Eraser,
                            Modifier.weight(1f),
                            selected = erasing,
                        ) {
                            erasing = !erasing
                            navigating = false
                        }
                        PaperAction(
                            "Line",
                            PaperGlyph.Line,
                            Modifier.weight(1f),
                            selected = tool == "Line" && !erasing && !navigating,
                        ) {
                            tool = "Line"
                            erasing = false
                            navigating = false
                            opacity = 1f
                        }
                        PaperAction(
                            "Navigate",
                            PaperGlyph.Hand,
                            Modifier.weight(1f),
                            selected = navigating,
                        ) {
                            navigating = !navigating
                            erasing = false
                        }
                    }
                }
                if (options)
                    PaperSheet("Drawing options", { options = false }) {
                        FlowRow {
                            listOf("Blank", "Lined", "Grid", "Dots").forEach { name ->
                                FilterChip(
                                    selected = drawing.paper == name,
                                    onClick = {
                                        drawing.paper = name
                                        save()
                                        options = false
                                    },
                                    label = { Text(name) },
                                )
                            }
                        }
                        TextButton(
                            {
                                val before = drawing.strokes
                                drawing.strokes = emptyList()
                                if (drawing.commit(before)) save()
                                options = false
                            },
                            enabled = drawing.strokes.isNotEmpty(),
                        ) {
                            PaperIcon(PaperGlyph.Trash)
                            Text("Clear", Modifier.padding(start = 8.dp))
                        }
                    }
                if (customColor)
                    PaperSheet(
                        "Pen color",
                        { customColor = false },
                        footer = {
                            Button(
                                {
                                    color = android.graphics.Color.parseColor(hex)
                                    erasing = false
                                    navigating = false
                                    customColor = false
                                    hex = ""
                                },
                                Modifier.fillMaxWidth(),
                                enabled = hex.matches(Regex("#[0-9a-fA-F]{6}")),
                            ) {
                                Text("Apply")
                            }
                        },
                    ) {
                        OutlinedTextField(
                            hex,
                            { hex = it.take(7) },
                            label = { Text("#RRGGBB") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().secureEditing(),
                        )
                    }
            }
        }
    }
}

@Composable
private fun DrawingPreview(drawing: NoteDrawing) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val cache = remember { StrokePathCache() }
    val strokes = drawing.strokes
    val paths = remember(strokes, dimensions) { cache.resolve(strokes, dimensions) }
    Canvas(
        Modifier.fillMaxWidth()
            .aspectRatio(drawing.aspect)
            .onSizeChanged {
                dimensions = it
                drawing.establishLegacySize(it.width / density)
            }
            .background(Color.White)
    ) {
        drawPaper(drawing.paper)
        strokes.forEachIndexed { index, stroke ->
            if (stroke.points.size == 1)
                drawCircle(
                    Color(stroke.color),
                    stroke.width * size.width / 720f,
                    Offset(stroke.points[0].x * size.width, stroke.points[0].y * size.height),
                )
            else
                drawPath(
                    paths[index],
                    Color(stroke.color),
                    style = Stroke(stroke.width * size.width / 360f, cap = StrokeCap.Round),
                )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPaper(kind: String) {
    if (size.width <= 0 || size.height <= 0) return
    val gap = size.width / 25f
    val ink = Color(0xffe1e5e8)
    if (kind == "Lined" || kind == "Grid") {
        var y = gap
        while (y < size.height) {
            drawLine(ink, Offset(0f, y), Offset(size.width, y), 1f)
            y += gap
        }
    }
    if (kind == "Grid") {
        var x = gap
        while (x < size.width) {
            drawLine(ink, Offset(x, 0f), Offset(x, size.height), 1f)
            x += gap
        }
    }
    if (kind == "Dots") {
        var x = gap
        while (x < size.width) {
            var y = gap
            while (y < size.height) {
                drawCircle(ink, 1.5f, Offset(x, y))
                y += gap
            }
            x += gap
        }
    }
}
