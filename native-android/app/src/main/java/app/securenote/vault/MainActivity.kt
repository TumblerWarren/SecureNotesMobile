@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.securenote.vault

import android.content.*
import android.os.*
import android.view.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.*
import app.securenote.vault.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

class MainActivity : ComponentActivity() {
    private val model: VaultModel by viewModels()

    private val screenOff =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) model.lock()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(
                screenOff,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                RECEIVER_NOT_EXPORTED,
            )
        else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        setContent {
            PaperTheme() {
                Surface(Modifier.fillMaxSize()) { VaultApp(model) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.clear()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        model.touch()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        model.previews.trim()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onPause() {
        model.lock()
        super.onPause()
    }
}

@Composable
internal fun VaultApp(model: VaultModel) {
    val navigationLabels = LocalDensity.current.fontScale <= 1.3f
    val imeVisible = paperKeyboardVisible()
    var selectingFiles by remember { mutableStateOf(false) }
    val open by model.unlocked.collectAsState()
    val busy by model.busy.collectAsState()
    val libraryState = key(open) { rememberLazyGridState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val message by model.message.collectAsState()
    val messageSuccess by model.messageSuccess.collectAsState()
    val selected by model.screen.collectAsState()
    val status by model.status.collectAsState()
    val category by model.category.collectAsState()
    val legacySource by model.legacySource.collectAsState()
    val attach by model.attachmentPicker.collectAsState()
    val recovery by model.recovery.collectAsState()
    var settings by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    fun openAdd() {
        focus.clearFocus()
        keyboard?.hide()
        adding = true
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            model.importUris(it)
        }
    val treePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    model
                        .getApplication<android.app.Application>()
                        .contentResolver
                        .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    model.importTree(uri)
                } catch (e: Exception) {
                    model.messageSuccess.value = false
                    model.message.value =
                        "Folder permission could not be retained. Select the folder again."
                }
            }
        }
    val oldPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            model.legacySource.value = it
        }
    LaunchedEffect(open) {
        if (!open) {
            settings = false
            adding = false
        }
    }
    LaunchedEffect(attach) {
        if (attach != null) {
            model.attachmentPicker.value = null
            picker.launch(arrayOf("*/*"))
        }
    }
    val folderParent by model.folderId.collectAsState()
    val browsingFolders by model.folderBrowsing.collectAsState()
    BackHandler(open && selected == null && browsingFolders && folderParent != null) {
        model.folderUp()
    }
    BackHandler(open && selected != null) {
        focus.clearFocus()
        keyboard?.hide()
        model.back()
    }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .background(Paper.Background)
            .safeDrawingPadding()
            .imePadding()
            .semantics { testTagsAsResourceId = true }
            .secureEditing()
    ) {
        val hideChrome = imeVisible || maxHeight < 180.dp
        Column(Modifier.fillMaxSize()) {
            if ((!open || recovery != null) && !hideChrome) {
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "SecureNote",
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontFamily = FontFamily.Serif
                            ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (open)
                        TextButton({
                            model.lock()
                            focus.clearFocus()
                            keyboard?.hide()
                        }) {
                            PaperIcon(PaperGlyph.Lock)
                            Spacer(Modifier.width(6.dp))
                            Text("Lock")
                        }
                    else PaperIcon(PaperGlyph.Lock, color = Paper.Muted)
                }
            }
            if (busy)
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(2.dp),
                    color = Paper.Accent,
                    trackColor = Paper.Inset,
                )
            if (message.isNotEmpty()) {
                Surface(
                    color =
                        if (messageSuccess) Paper.AccentSoft
                        else MaterialTheme.colorScheme.errorContainer,
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        Modifier.padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton({ model.message.value = "" }) {
                            PaperIcon(PaperGlyph.Close, "Dismiss message")
                        }
                    }
                }
            }
            if (status.isNotBlank())
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Muted,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
            when {
                !open ->
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val viewportHeight = maxHeight
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Column(
                                Modifier.widthIn(max = 420.dp)
                                    .fillMaxWidth()
                                    .heightIn(min = viewportHeight)
                                    .padding(
                                        horizontal = 16.dp,
                                        vertical = if (hideChrome) 4.dp else 16.dp,
                                    ),
                                verticalArrangement =
                                    Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                LockScreen(model, busy, hideChrome)
                            }
                        }
                    }
                recovery != null ->
                    Column(
                        Modifier.weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Text(
                            "Record your recovery key",
                            style = MaterialTheme.typography.headlineLarge,
                        )
                        Text(
                            "Keep this key somewhere safe, outside this phone. Anyone with it can unlock your vault. It cannot restore deleted items.",
                            color = Paper.Muted,
                        )
                        Surface(
                            color = Paper.Surface,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, Paper.Line),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                recovery!!
                                    .joinToString("") { "%02x".format(it) }
                                    .chunked(16)
                                    .forEach { line ->
                                        Text(
                                            line.chunked(8).joinToString("  "),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 18.sp,
                                        )
                                    }
                            }
                        }
                        var confirmed by remember { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(confirmed, { confirmed = it })
                            Text(
                                "I recorded the key in a safe place",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(
                            { model.dismissRecovery() },
                            enabled = confirmed,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Text("Continue")
                        }
                    }
                selected != null ->
                    Box(Modifier.weight(1f).fillMaxWidth()) { ItemScreen(model, selected!!) }
                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        PaperLibrary(model, libraryState, { selectingFiles = it }) { openAdd() }
                    }
                    if (!hideChrome && !selectingFiles) {
                        HorizontalDivider(color = Paper.Line)
                        Row(
                            Modifier.fillMaxWidth()
                                .background(Paper.Background)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            listOf(
                                    Triple("all", "Vault", PaperGlyph.Shield),
                                    Triple("notes", "Notes", PaperGlyph.Note),
                                    Triple("folders", "Folders", PaperGlyph.Vault),
                                    Triple("settings", "Settings", PaperGlyph.Settings),
                                )
                                .forEach { (key, label, glyph) ->
                                    val active =
                                        if (key == "all")
                                            !model.folderBrowsing.collectAsState().value &&
                                                category == "all"
                                        else if (key == "folders")
                                            model.folderBrowsing.collectAsState().value
                                        else category == key
                                    TextButton(
                                        {
                                            if (key == "settings") settings = true
                                            else if (key == "folders") model.openFolder(null)
                                            else {
                                                model.folderBrowsing.value = false
                                                model.showLibrary(key)
                                            }
                                        },
                                        modifier =
                                            Modifier.weight(1f).testTag("nav-$key").semantics {
                                                this.selected = active
                                                contentDescription = label
                                            },
                                        contentPadding =
                                            PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Box(
                                                Modifier.size(48.dp, 28.dp)
                                                    .background(
                                                        if (active) Paper.AccentSoft
                                                        else Color.Transparent,
                                                        RoundedCornerShape(14.dp),
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                PaperIcon(
                                                    glyph,
                                                    color =
                                                        if (active) Paper.Accent else Paper.Muted,
                                                )
                                            }
                                            if (navigationLabels)
                                                Text(
                                                    label,
                                                    color =
                                                        if (active) Paper.Accent else Paper.Muted,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                        }
                                    }
                                }
                        }
                    }
                }
            }
        }
    }
    if (open && recovery == null) {
        if (adding)
            PaperSheet("Add item", { adding = false }) {
                listOf(
                        "New note" to PaperGlyph.Note,
                        "Import files" to PaperGlyph.Import,
                        "Import folder" to PaperGlyph.Vault,
                    )
                    .forEach { (label, icon) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(
                                enabled = !busy
                            ) {
                                adding = false
                                when (label) {
                                    "New note" -> model.newNote()
                                    "Import files" -> {
                                        model.prepareImport()
                                        picker.launch(arrayOf("*/*"))
                                    }
                                    else -> {
                                        model.prepareImport()
                                        treePicker.launch(null)
                                    }
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PaperIcon(icon, modifier = Modifier.padding(12.dp))
                            Text(
                                label,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            PaperIcon(PaperGlyph.Next)
                        }
                    }
                Text(
                    "Imports make encrypted copies. Originals stay at their source.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Muted,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        if (settings)
            SettingsDialog(
                model,
                { settings = false },
                {
                    settings = false
                    oldPicker.launch(arrayOf("*/*"))
                },
                {
                    settings = false
                    model.prepareImport()
                    picker.launch(arrayOf("*/*"))
                },
                {
                    settings = false
                    model.prepareImport()
                    treePicker.launch(null)
                },
            )
        if (legacySource != null) LegacyPasswordDialog(model)
    }
}

@Composable
private fun LockScreen(model: VaultModel, busy: Boolean, compactInput: Boolean) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var recoveryMode by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val creating = !model.store.exists
    val keyboardOpen = paperKeyboardVisible() || compactInput
    if (!keyboardOpen) {
        Text(
            if (recoveryMode) "A way back in."
            else if (creating) "A private place for everything." else "Welcome back.",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            if (recoveryMode) "Use your recovery key to choose a new password."
            else if (creating)
                "Your thoughts, documents and moments. Kept on this device, behind your password."
            else "Enter your password to open your vault.",
            color = Paper.Muted,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
    if (recoveryMode)
        OutlinedTextField(
            secret,
            { secret = it },
            label = { Text("Recovery key", maxLines = 1) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().keepInputVisible(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            keyboardActions =
                androidx.compose.foundation.text.KeyboardActions(
                    onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) }
                ),
            enabled = !busy,
        )
    OutlinedTextField(
        password,
        { password = it },
        label = { Text(if (recoveryMode) "New password" else "Vault password") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().keepInputVisible().testTag("vault-password"),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (creating || recoveryMode) ImeAction.Next else ImeAction.Done,
            ),
        keyboardActions =
            androidx.compose.foundation.text.KeyboardActions(
                onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) },
                onDone = {
                    focus.clearFocus()
                    keyboard?.hide()
                },
            ),
        enabled = !busy,
    )
    if (creating || recoveryMode) {
        OutlinedTextField(
            confirmation,
            { confirmation = it },
            label = { Text("Confirm password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().keepInputVisible().testTag("vault-confirmation"),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions =
                androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        focus.clearFocus()
                        keyboard?.hide()
                    }
                ),
            enabled = !busy,
        )
        Text(
            "Choose a strong passphrase of at least 16 characters.",
            style = MaterialTheme.typography.bodySmall,
            color = Paper.Muted,
        )
    }
    val valid =
        if (creating || recoveryMode) password.length >= 16 && password == confirmation
        else password.isNotEmpty()
    Button(
        onClick = {
            focus.clearFocus()
            keyboard?.hide()
            val pw = password.toByteArray(Charsets.UTF_8)
            password = ""
            confirmation = ""
            if (recoveryMode) {
                val code = secret.filterNot(Char::isWhitespace)
                secret = ""
                val bytes = runCatching {
                    require(code.length == 64)
                    code.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                }
                    .getOrNull()
                if (bytes != null) model.recover(bytes, pw)
                else {
                    pw.fill(0)
                    model.messageSuccess.value = false
                    model.message.value = "Invalid recovery key."
                }
            } else model.unlock(pw, creating)
        },
        enabled = !busy && valid,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Text(if (creating) "Create vault" else if (recoveryMode) "Recover" else "Unlock")
    }
    if (!creating)
        TextButton(
            {
                recoveryMode = !recoveryMode
                password = ""
                confirmation = ""
                secret = ""
            },
            enabled = !busy,
        ) {
            Text(if (recoveryMode) "Use password" else "Use recovery key")
        }
    HorizontalDivider(color = Paper.Line, modifier = Modifier.padding(top = 12.dp))
    Text(
        if (model.pendingImports.isNotEmpty())
            "Unlock to import your selected files. Originals remain outside the vault."
        else
            "On this device. No cloud backup. Deleting app storage permanently deletes your vault.",
        style = MaterialTheme.typography.bodySmall,
        color = Paper.Muted,
    )
    DisposableEffect(Unit) {
        onDispose {
            password = ""
            confirmation = ""
            secret = ""
        }
    }
}

@Composable
private fun ItemScreen(model: VaultModel, id: String) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var item by remember(id) { mutableStateOf<VaultItem?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var menu by remember(id) { mutableStateOf(false) }
    var deleting by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        try {
            model.store.dao.observeDisplay(id).distinctUntilChanged().collect { latest ->
                item = latest
                failed = latest == null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    val keyboardOpen = paperKeyboardVisible()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactKeyboard =
            maxHeight < 180.dp ||
                (keyboardOpen &&
                    androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
                        android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        Column(Modifier.fillMaxSize()) {
            if (!compactKeyboard)
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperAction("Back", PaperGlyph.Back) {
                        focus.clearFocus()
                        keyboard?.hide()
                        model.back()
                    }
                    Text(
                        when {
                            item?.mime == "application/x-securenote" -> "Notes"
                            item?.mime?.startsWith("audio/") == true -> "Now playing"
                            item?.mime?.startsWith("video/") == true -> "Video"
                            else -> item?.name.orEmpty()
                        },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    PaperAction("Lock", PaperGlyph.Lock) {
                        model.lock()
                        focus.clearFocus()
                        keyboard?.hide()
                    }
                    Box {
                        IconButton({ menu = true }, enabled = item != null) {
                            PaperIcon(PaperGlyph.More, "Item options")
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (item?.pinned == true) "Unpin" else "Pin") },
                                onClick = {
                                    menu = false
                                    model.pin(id)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    deleting = true
                                },
                            )
                        }
                    }
                }
            HorizontalDivider(color = Paper.Line)
            if (failed) {
                Text("This item is unavailable.", modifier = Modifier.padding(24.dp))
                return@BoxWithConstraints
            }
            val value = item
            if (value == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@BoxWithConstraints
            }
            if (deleting)
                ConfirmPaperDelete(value.name, { deleting = false }) {
                    deleting = false
                    model.delete(id)
                }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    value.mime == "application/x-securenote" -> NativeNoteEditor(model, value)
                    isEpubFile(value.mime, value.name) -> EpubViewer(model, id)
                    value.mime.startsWith("audio/") || value.mime.startsWith("video/") ->
                        SecureMediaViewer(model, id, value)
                    value.mime == "application/pdf" -> SecurePdfReader(model, id)
                    value.mime.startsWith("image/") -> ImageViewer(model, id)
                    value.mime.startsWith("text/") -> TextViewer(model, id)
                    else ->
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            PaperFileBadge(value.mime)
                            Text(value.name, style = MaterialTheme.typography.headlineMedium)
                            Text(
                                "${fileKind(value.mime)} · ${fileSize(value.size)}",
                                color = Paper.Muted,
                            )
                            Text(
                                "Your file is stored in this vault. Internal preview is not available for this format.",
                                color = Paper.Muted,
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun ImageViewer(model: VaultModel, id: String) {
    val context = LocalContext.current
    var imageAttempt by remember(id) { mutableIntStateOf(0) }
    val view =
        remember(id, imageAttempt) { TiledImageView(context, model.store, model.previews, id) }
    DisposableEffect(view) {
        val unregister = model.onLock { view.close() }
        onDispose {
            unregister()
            view.close()
        }
    }
    var state by remember(id) { mutableStateOf("loading") }
    var zoom by remember(id) { mutableIntStateOf(100) }
    Box(Modifier.fillMaxSize().clipToBounds()) {
        key(view) {
            AndroidView(
                factory = {
                    view.also {
                        it.onState = { state = it }
                        it.onZoom = { zoom = it }
                        state = it.state
                    }
                },
                modifier = Modifier.fillMaxSize().clipToBounds(),
                onRelease = {
                    it.onState = null
                    it.onZoom = null
                    it.close()
                },
            )
        }
        if (state == "loading") CircularProgressIndicator(Modifier.align(Alignment.Center))
        if (state == "error")
            Column(
                Modifier.align(Alignment.Center)
                    .background(Paper.Background)
                    .padding(PaperSpacing.Screen)
            ) {
                TextButton({ imageAttempt++ }) { Text("Retry image") }
                Text(
                    "Image preview unavailable. The encrypted file is kept.",
                    color = Paper.Ink,
                )
            }
        if (state == "ready")
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                shape = RoundedCornerShape(28.dp),
                color = Paper.Media.copy(alpha = .93f),
            ) {
                Row(
                    Modifier.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperAction("Fit", PaperGlyph.Fit, dark = true) { view.fitToScreen() }
                    PaperAction("Zoom out", PaperGlyph.Minus, dark = true) {
                        view.changeZoom(1 / 1.5f)
                    }
                    Text(
                        "$zoom%",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                    )
                    PaperAction("Zoom in", PaperGlyph.Zoom, dark = true) { view.changeZoom(1.5f) }
                    PaperAction("Rotate image", PaperGlyph.Rotate, dark = true) {
                        view.rotateClockwise()
                    }
                }
            }
    }
}

@Composable
private fun TextViewer(model: VaultModel, id: String) {
    var page by remember(id) { mutableLongStateOf(0) }
    var count by remember(id) { mutableLongStateOf(1) }
    var text by remember(id) { mutableStateOf("") }
    var loading by remember(id) { mutableStateOf(false) }
    var failure by remember(id) { mutableStateOf(false) }
    var attempt by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(id, page, attempt) {
        text = ""
        loading = true
        failure = false
        text =
            try {
                withContext(Dispatchers.IO) {
                    val (item, reader) = model.store.reader(id)
                    count = TextPage.count(item.size)
                    reader.use { TextPage.read(it, item.size, page) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = true
                "Text preview is unavailable."
            }
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (failure) TextButton({ attempt++ }) { Text("Retry text preview") }
        val scroll = remember(page) { ScrollState(0) }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif),
            modifier =
                Modifier.weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(PaperSpacing.Screen),
        )
        HorizontalDivider(color = Paper.Line)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ page-- }, enabled = page > 0) {
                PaperIcon(PaperGlyph.Back, "Previous page")
            }
            Text(
                "${page+1} / $count",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton({ page++ }, enabled = page + 1 < count) {
                PaperIcon(PaperGlyph.Next, "Next page")
            }
        }
    }
    DisposableEffect(Unit) { onDispose { text = "" } }
}
