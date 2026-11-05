package app.securenote.vault

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.CancellationException

/** Vault content deliberately uses session-only remember state, never saved instance state. */
@Composable
internal fun TrackerScreen(model: VaultModel) {
    var selected by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var categories by remember { mutableStateOf<List<TrackerCategory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var editingFormat by remember { mutableStateOf<TrackerCategory?>(null) }
    var newFormat by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<TrackerEntry?>(null) }
    var deletingCategory by remember { mutableStateOf<TrackerCategory?>(null) }
    LaunchedEffect(attempt) {
        loading = true
        failed = false
        try {
            model.store.trackerDao.categories().collect {
                it.forEach { category -> TrackerFormat.decode(category.format) }
                categories = it
                loading = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            loading = false
            failed = true
        }
    }
    val category = categories.firstOrNull { it.id == selected }
    BackHandler(selected != null) { selected = null }
    Column(Modifier.fillMaxSize().testTag("tracker-screen")) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected != null)
                PaperAction("Tracker categories", PaperGlyph.Back) { selected = null }
            Column(Modifier.weight(1f).padding(4.dp)) {
                Text(
                    category?.name ?: "Your trackers",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (category == null) "A little progress, in your own format."
                    else
                        "${TrackerFormat.decode(category.format).fields.size + 1} fields · Encrypted in your vault",
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Muted,
                )
            }
            if (category != null) {
                var menu by remember { mutableStateOf(false) }
                Box {
                    PaperAction("Category options", PaperGlyph.More) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Edit format") },
                            onClick = {
                                menu = false
                                newFormat = false
                                editingFormat = category
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text("Delete category", color = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                menu = false
                                deletingCategory = category
                            },
                        )
                    }
                }
            }
        }
        if (failed) {
            TrackerEmpty("Trackers could not be loaded.", "Your saved data has been kept.") {
                TextButton({ attempt++ }) { Text("Retry") }
            }
        } else if (category == null) {
            Button(
                { naming = true },
                Modifier.padding(horizontal = 16.dp).testTag("new-tracker-category"),
            ) {
                PaperIcon(PaperGlyph.Add)
                Spacer(Modifier.width(8.dp))
                Text("New category")
            }
            if (!loading && categories.isEmpty()) {
                TrackerEmpty(
                    "Make room for progress.",
                    "Track books, chapters, projects, or a checklist. Start with a category and choose its format.",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(categories, key = { it.id }) { value ->
                        Surface(
                            Modifier.fillMaxWidth().clickable {
                                selected = value.id
                                model.touch()
                            },
                            shape = RoundedCornerShape(14.dp),
                            color = Paper.Surface,
                            border = BorderStroke(1.dp, Paper.Line),
                        ) {
                            Row(
                                Modifier.padding(12.dp).heightIn(min = 52.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(44.dp)
                                        .background(Paper.AccentSoft, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    PaperIcon(PaperGlyph.List, color = Paper.Accent)
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(value.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        (listOf("Title") +
                                                TrackerFormat.decode(value.format).fields.map {
                                                    it.label
                                                })
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Paper.Muted,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                PaperIcon(PaperGlyph.Next)
                            }
                        }
                    }
                }
            }
        } else {
            TrackerEntries(model, category) { editingEntry = it }
        }
    }
    if (naming)
        TrackerNameSheet({ naming = false }) { name ->
            naming = false
            newFormat = true
            editingFormat =
                TrackerCategory(
                    UUID.randomUUID().toString(),
                    name,
                    TrackerFormat.reading().encode(),
                    System.currentTimeMillis(),
                )
        }
    editingFormat?.let { value ->
        TrackerFormatSheet(model, value, newFormat, { editingFormat = null }) { updated ->
            model.saveTrackerCategory(updated) {
                selected = updated.id
                editingFormat = null
            }
        }
    }
    editingEntry?.let { entry ->
        category?.let { value ->
            TrackerEntrySheet(model, value, entry, { editingEntry = null }) { updated ->
                model.saveTrackerEntry(updated) { editingEntry = null }
            }
        }
    }
    deletingCategory?.let { value ->
        PaperSheet(
            "Delete category?",
            { deletingCategory = null },
            footer = {
                Button(
                    {
                        model.deleteTrackerCategory(value.id) {
                            deletingCategory = null
                            selected = null
                        }
                    },
                    enabled = !model.busy.collectAsState().value,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete category and entries")
                }
            },
        ) {
            Text(
                "Delete “${value.name}” and all of its tracker entries? This cannot be undone. Images stored in your vault are kept."
            )
            TrackerSaveError(model)
        }
    }
}

@Composable
private fun TrackerEmpty(title: String, detail: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Paper.Muted)
        action()
    }
}

@Composable
private fun TrackerNameSheet(dismiss: () -> Unit, next: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    PaperSheet(
        "New tracker category",
        dismiss,
        footer = {
            Button(
                { next(name.trim()) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose format")
            }
        },
    ) {
        Text("Give this collection a name. You’ll choose its fields next.", color = Paper.Muted)
        TrackerText(name, { name = it.take(100) }, "Category name", "tracker-category-name")
    }
}

@Composable
private fun TrackerFormatSheet(
    model: VaultModel,
    category: TrackerCategory,
    creating: Boolean,
    dismiss: () -> Unit,
    save: (TrackerCategory) -> Unit,
) {
    var name by remember(category.id) { mutableStateOf(category.name) }
    var fields by
        remember(category.id) { mutableStateOf(TrackerFormat.decode(category.format).fields) }
    val originalIds =
        remember(category.id) { if (creating) emptySet() else fields.map { it.id }.toSet() }
    var field by remember { mutableStateOf<TrackerField?>(null) }
    var error by remember { mutableStateOf("") }
    val busy by model.busy.collectAsState()
    PaperSheet(
        if (creating) "Choose tracker format" else "Edit tracker format",
        dismiss,
        footer = {
            Button(
                {
                    error = trackerFormatError(name, fields)
                    if (error.isEmpty())
                        save(
                            category.copy(
                                name = name.trim(),
                                format = TrackerFormat(fields).encode(),
                                updated = System.currentTimeMillis(),
                            )
                        )
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("save-tracker-format"),
            ) {
                Text(if (creating) "Create category" else "Save format")
            }
        },
    ) {
        TrackerText(name, { name = it.take(100) }, "Category name")
        if (creating) {
            Text("Start with a format, then make it yours.", color = Paper.Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ fields = TrackerFormat.reading().fields }) { Text("Reading") }
                TextButton({ fields = TrackerFormat.checklist().fields }) { Text("Checklist") }
                TextButton({ fields = emptyList() }) { Text("Custom") }
            }
        }
        Text(
            "Title",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            "Required for every entry",
            style = MaterialTheme.typography.bodySmall,
            color = Paper.Muted,
        )
        fields.forEachIndexed { index, value ->
            Surface(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                color = Paper.Surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Paper.Line),
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(4.dp)) {
                            Text(value.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                value.type.trackerLabel(),
                                style = MaterialTheme.typography.bodySmall,
                                color = Paper.Muted,
                            )
                        }
                        PaperAction("Edit ${value.label}", PaperGlyph.Draw, enabled = !busy) {
                            field = value
                        }
                        PaperAction("Remove ${value.label}", PaperGlyph.Close, enabled = !busy) {
                            fields = fields.filterNot { it.id == value.id }
                        }
                    }
                    Row {
                        TextButton(
                            {
                                fields =
                                    fields.toMutableList().apply { add(index - 1, removeAt(index)) }
                            },
                            enabled = index > 0 && !busy,
                        ) {
                            Text("Move up")
                        }
                        TextButton(
                            {
                                fields =
                                    fields.toMutableList().apply { add(index + 1, removeAt(index)) }
                            },
                            enabled = index < fields.lastIndex && !busy,
                        ) {
                            Text("Move down")
                        }
                    }
                }
            }
        }
        TextButton(
            { field = TrackerField(label = "", type = TrackerFieldType.TEXT) },
            enabled = fields.size < 20 && !busy,
        ) {
            PaperIcon(PaperGlyph.Add)
            Text("Add field", Modifier.padding(start = 8.dp))
        }
        Text(
            "Rename or reorder fields without losing progress. Removed fields are hidden; their saved values are kept. Existing field types stay fixed.",
            style = MaterialTheme.typography.bodySmall,
            color = Paper.Muted,
        )
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        TrackerSaveError(model)
    }
    field?.let { value ->
        TrackerFieldSheet(value, value.id in originalIds, fields, { field = null }) { updated ->
            fields =
                if (fields.any { it.id == updated.id })
                    fields.map { if (it.id == updated.id) updated else it }
                else fields + updated
            field = null
        }
    }
}

private fun trackerFormatError(name: String, fields: List<TrackerField>): String =
    if (name.isBlank()) "Enter a category name."
    else
        try {
            TrackerFormat(fields).validate()
            ""
        } catch (e: IllegalArgumentException) {
            e.message ?: "Check the tracker fields."
        }

private fun TrackerFieldType.trackerLabel(): String =
    when (this) {
        TrackerFieldType.TEXT -> "Text"
        TrackerFieldType.NUMBER -> "Count"
        TrackerFieldType.STATUS -> "Status"
        TrackerFieldType.CHECKBOX -> "Checkbox"
        TrackerFieldType.IMAGE -> "Thumbnail image"
    }

@Composable
private fun TrackerFieldSheet(
    value: TrackerField,
    fixedType: Boolean,
    siblings: List<TrackerField>,
    dismiss: () -> Unit,
    save: (TrackerField) -> Unit,
) {
    var label by remember(value.id) { mutableStateOf(value.label) }
    var type by remember(value.id) { mutableStateOf(value.type) }
    var options by remember(value.id) { mutableStateOf(value.options.joinToString("\n")) }
    var error by remember { mutableStateOf("") }
    PaperSheet(
        if (value.label.isEmpty()) "Add field" else "Edit field",
        dismiss,
        footer = {
            Button(
                {
                    val choices = options.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    error =
                        when {
                            label.isBlank() -> "Enter a field name."
                            label.trim().equals("Title", true) ||
                                siblings.any {
                                    it.id != value.id && it.label.trim().equals(label.trim(), true)
                                } -> "A field with that name already exists."
                            type == TrackerFieldType.STATUS &&
                                (choices.isEmpty() ||
                                    choices.size > 30 ||
                                    choices.any { it.length > 80 } ||
                                    choices
                                        .map { it.lowercase(java.util.Locale.ROOT) }
                                        .distinct()
                                        .size != choices.size) ->
                                "Add 1–30 unique status choices, one per line (up to 80 characters each)."
                            else -> ""
                        }
                    if (error.isEmpty())
                        save(
                            value.copy(
                                label = label.trim(),
                                type = type,
                                options =
                                    if (type == TrackerFieldType.STATUS) choices else emptyList(),
                            )
                        )
                },
                Modifier.fillMaxWidth(),
            ) {
                Text("Save field")
            }
        },
    ) {
        TrackerText(label, { label = it.take(80) }, "Field name", "tracker-field-name")
        Text(
            "Field type",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (fixedType) Text(type.trackerLabel(), color = Paper.Muted)
        else
            TrackerFieldType.entries.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().clickable { type = option }.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(type == option, { type = option })
                    Text(option.trackerLabel())
                }
            }
        if (type == TrackerFieldType.STATUS) {
            OutlinedTextField(
                options,
                { options = it.take(4000) },
                label = { Text("Status choices, one per line") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().keepInputVisible(),
            )
            Text(
                "Existing entries keep their saved status if a choice is removed.",
                style = MaterialTheme.typography.bodySmall,
                color = Paper.Muted,
            )
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ColumnScope.TrackerEntries(
    model: VaultModel,
    category: TrackerCategory,
    edit: (TrackerEntry) -> Unit,
) {
    var entries by remember(category.id) { mutableStateOf<List<TrackerEntry>>(emptyList()) }
    var loading by remember(category.id) { mutableStateOf(true) }
    var failed by remember(category.id) { mutableStateOf(false) }
    var attempt by remember(category.id) { mutableIntStateOf(0) }
    var search by remember(category.id) { mutableStateOf("") }
    var deleting by remember(category.id) { mutableStateOf<TrackerEntry?>(null) }
    val format = remember(category.format) { TrackerFormat.decode(category.format) }
    val busy by model.busy.collectAsState()
    LaunchedEffect(category.id, attempt) {
        loading = true
        failed = false
        try {
            model.store.trackerDao.entries(category.id).collect { rows ->
                rows.forEach { TrackerValues.decode(it.values) }
                entries = rows
                loading = false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
            loading = false
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            search,
            {
                search = it
                model.touch()
            },
            placeholder = { Text("Search entries") },
            singleLine = true,
            leadingIcon = { PaperIcon(PaperGlyph.Search) },
            modifier = Modifier.weight(1f).testTag("tracker-search"),
            shape = RoundedCornerShape(16.dp),
        )
        PaperAction("Add tracker entry", PaperGlyph.Add, enabled = !busy) {
            edit(
                TrackerEntry(
                    UUID.randomUUID().toString(),
                    category.id,
                    "",
                    TrackerValues.encode(emptyMap()),
                    System.currentTimeMillis(),
                )
            )
        }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
    val visible =
        remember(entries, search, category.format) {
            entries.filter { entry ->
                val values = TrackerValues.decode(entry.values)
                entry.title.contains(search, true) ||
                    format.fields
                        .filter { it.type != TrackerFieldType.IMAGE }
                        .any { values[it.id].orEmpty().contains(search, true) }
            }
        }
    if (failed)
        TrackerEmpty("Entries could not be loaded.", "Your saved data has been kept.") {
            TextButton({ attempt++ }) { Text("Retry") }
        }
    else if (!loading && visible.isEmpty())
        TrackerEmpty(
            if (search.isBlank()) "A fresh start." else "No matching entries.",
            if (search.isBlank()) "Add your first entry with the + button."
            else "Try another title or field value.",
        )
    LazyColumn(
        Modifier.weight(1f),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(visible, key = { it.id }) { entry ->
            val values = remember(entry.values) { TrackerValues.decode(entry.values) }
            Surface(
                Modifier.fillMaxWidth(),
                color = Paper.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Paper.Line),
            ) {
                Column(Modifier.padding(12.dp)) {
                    val image = format.fields.firstOrNull { it.type == TrackerFieldType.IMAGE }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (image != null)
                            TrackerThumbnail(
                                model,
                                values[image.id],
                                Modifier.padding(end = 12.dp).size(56.dp),
                            )
                        Box(
                            Modifier.weight(1f).heightIn(min = 48.dp).clickable { edit(entry) },
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            PaperAction("Options for ${entry.title}", PaperGlyph.More) {
                                menu = true
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Edit entry") },
                                    onClick = {
                                        menu = false
                                        edit(entry)
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Delete entry",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        menu = false
                                        deleting = entry
                                    },
                                )
                            }
                        }
                    }
                    format.fields
                        .filter { it.id != image?.id }
                        .forEach { field ->
                            if (field.type == TrackerFieldType.IMAGE) {
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    TrackerThumbnail(model, values[field.id], Modifier.size(48.dp))
                                    Text(field.label, style = MaterialTheme.typography.bodyMedium)
                                }
                            } else if (field.type == TrackerFieldType.CHECKBOX) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        values[field.id] == "true",
                                        { model.setTrackerCheckbox(entry.id, field.id, it) },
                                        enabled = !busy,
                                        modifier =
                                            Modifier.semantics {
                                                contentDescription =
                                                    "${entry.title}: ${field.label}"
                                            },
                                    )
                                    Text(field.label, style = MaterialTheme.typography.bodyMedium)
                                }
                            } else if (!values[field.id].isNullOrEmpty()) {
                                Text(
                                    "${field.label}: ${values[field.id]}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Paper.Muted,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                }
            }
        }
    }
    deleting?.let { entry ->
        PaperSheet(
            "Delete entry?",
            { deleting = null },
            footer = {
                Button(
                    { model.deleteTrackerEntry(entry.id) { deleting = null } },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete entry")
                }
            },
        ) {
            Text("Delete “${entry.title}”? This cannot be undone.")
            TrackerSaveError(model)
        }
    }
}

@Composable
private fun TrackerEntrySheet(
    model: VaultModel,
    category: TrackerCategory,
    entry: TrackerEntry,
    dismiss: () -> Unit,
    save: (TrackerEntry) -> Unit,
) {
    var title by remember(entry.id) { mutableStateOf(entry.title) }
    var values by remember(entry.id) { mutableStateOf(TrackerValues.decode(entry.values)) }
    var error by remember { mutableStateOf("") }
    val format = remember(category.format) { TrackerFormat.decode(category.format) }
    val busy by model.busy.collectAsState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var imageField by remember { mutableStateOf<String?>(null) }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            model.finishDocumentPicker()
            val id = imageField
            imageField = null
            if (uri != null && id != null && model.unlocked.value)
                model.importTrackerImage(uri) { image -> values = values + (id to image) }
        }
    PaperSheet(
        if (entry.title.isEmpty()) "New tracker entry" else "Edit tracker entry",
        dismiss,
        footer = {
            Button(
                {
                    error =
                        when {
                            title.isBlank() -> "Enter a title."
                            format.fields.any {
                                it.type == TrackerFieldType.NUMBER &&
                                    !values[it.id].isNullOrBlank() &&
                                    (values[it.id]?.toLongOrNull()?.let { n -> n >= 0 } != true)
                            } -> "Counts must be whole numbers of zero or more."
                            else -> ""
                        }
                    if (error.isEmpty()) {
                        try {
                            save(
                                entry.copy(
                                    title = title.trim(),
                                    values = TrackerValues.encode(values),
                                    updated = System.currentTimeMillis(),
                                )
                            )
                        } catch (e: IllegalArgumentException) {
                            error = e.message ?: "Check the entry values."
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("save-tracker-entry"),
            ) {
                Text("Save entry")
            }
        },
    ) {
        TrackerText(
            title,
            { title = it.take(255) },
            "Title",
            "tracker-entry-title",
            enabled = !busy,
        )
        format.fields.forEach { field ->
            val current = values[field.id].orEmpty()
            when (field.type) {
                TrackerFieldType.TEXT,
                TrackerFieldType.NUMBER ->
                    TrackerText(
                        current,
                        {
                            values =
                                values +
                                    (field.id to
                                        it.take(
                                            if (field.type == TrackerFieldType.NUMBER) 19 else 4000
                                        ))
                        },
                        field.label,
                        enabled = !busy,
                        number = field.type == TrackerFieldType.NUMBER,
                    )
                TrackerFieldType.CHECKBOX ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            current == "true",
                            { values = values + (field.id to it.toString()) },
                            enabled = !busy,
                            modifier = Modifier.semantics { contentDescription = field.label },
                        )
                        Text(field.label)
                    }
                TrackerFieldType.STATUS -> {
                    var expanded by remember(field.id) { mutableStateOf(false) }
                    Text(
                        field.label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Box {
                        OutlinedButton(
                            { expanded = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(current.ifBlank { "Choose status" })
                        }
                        DropdownMenu(expanded, { expanded = false }) {
                            (listOf("") +
                                    field.options +
                                    listOf(current).filter { it.isNotEmpty() })
                                .distinct()
                                .forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.ifBlank { "No status" }) },
                                        onClick = {
                                            values = values + (field.id to option)
                                            expanded = false
                                        },
                                    )
                                }
                        }
                    }
                }
                TrackerFieldType.IMAGE -> {
                    Text(
                        field.label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrackerThumbnail(model, current, Modifier.size(64.dp))
                        TextButton(
                            {
                                if (model.beginDocumentPicker()) {
                                    imageField = field.id
                                    focus.clearFocus()
                                    keyboard?.hide()
                                    try {
                                        picker.launch(arrayOf("image/*"))
                                    } catch (_: Exception) {
                                        model.finishDocumentPicker()
                                        imageField = null
                                        error = "The image picker could not be opened."
                                    }
                                }
                            },
                            enabled = !busy,
                        ) {
                            Text(if (current.isBlank()) "Choose image" else "Change image")
                        }
                        if (current.isNotEmpty())
                            TextButton({ values = values + (field.id to "") }, enabled = !busy) {
                                Text("Remove")
                            }
                    }
                    Text(
                        "Images are imported into your vault and kept even if this entry is cancelled or deleted.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Paper.Muted,
                    )
                }
            }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        TrackerSaveError(model)
    }
}

@Composable
private fun TrackerThumbnail(model: VaultModel, id: String?, modifier: Modifier) {
    var bitmap by remember(id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id) {
        bitmap = null
        if (!id.isNullOrBlank()) {
            try {
                val item = model.store.dao.display(id)
                if (item != null && item.mime.startsWith("image/"))
                    bitmap =
                        model.previews.thumbnail(
                            ItemSummary(
                                item.rowId,
                                item.id,
                                item.name,
                                item.mime,
                                item.size,
                                item.updated,
                                item.pinned,
                            )
                        )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                /* The vault image may have been deleted independently. */
            }
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(Paper.Inset),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image == null) PaperIcon(PaperGlyph.Image, color = Paper.Muted)
        else
            Image(
                image.asImageBitmap(),
                "Tracker thumbnail",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
    }
    DisposableEffect(id) { onDispose { bitmap = null } }
}

@Composable
private fun TrackerText(
    value: String,
    change: (String) -> Unit,
    label: String,
    tag: String = "tracker-field",
    enabled: Boolean = true,
    number: Boolean = false,
) {
    OutlinedTextField(
        value,
        change,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions =
            KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).keepInputVisible().testTag(tag),
    )
}

@Composable
private fun TrackerSaveError(model: VaultModel) {
    val busy by model.busy.collectAsState()
    val message by model.message.collectAsState()
    val success by model.messageSuccess.collectAsState()
    if (!busy && !success && message.isNotEmpty())
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
}
