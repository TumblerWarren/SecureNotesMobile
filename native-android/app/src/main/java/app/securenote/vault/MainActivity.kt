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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
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
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
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
    Column(
        Modifier.fillMaxSize()
            .background(Paper.Background)
            .safeDrawingPadding()
            .imePadding()
            .semantics { testTagsAsResourceId = true }
            .secureEditing()
    ) {
        if (!open || recovery != null || selected == null) {
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SecureNote",
                    style = MaterialTheme.typography.titleLarge,
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
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).semantics {
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
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LockScreen(model, busy)
                }
            recovery != null ->
                Column(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("Record your recovery key", style = MaterialTheme.typography.headlineLarge)
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
                    PaperLibrary(model, libraryState) { openAdd() }
                    FloatingActionButton(
                        { openAdd() },
                        containerColor = Paper.Accent,
                        contentColor = Color.White,
                        shape = RoundedCornerShape(28.dp),
                        modifier =
                            Modifier.align(Alignment.BottomEnd).padding(24.dp).testTag("add-item"),
                    ) {
                        PaperIcon(PaperGlyph.Add, "Add")
                    }
                }
                HorizontalDivider(color = Paper.Line)
                Row(
                    Modifier.fillMaxWidth()
                        .background(Paper.Background)
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    listOf(
                            Triple("all", "Vault", PaperGlyph.Vault),
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
                                    },
                                contentPadding = PaddingValues(vertical = 8.dp, horizontal = 0.dp),
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    PaperIcon(
                                        glyph,
                                        color = if (active) Paper.Accent else Paper.Muted,
                                    )
                                    Text(
                                        label,
                                        color = if (active) Paper.Accent else Paper.Muted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                }
            }
        }
    }
    if (open && recovery == null) {
        if (adding)
            AlertDialog(
                modifier = Modifier.secureEditing(),
                onDismissRequest = { adding = false },
                title = {
                    Text("Make room for something.", style = MaterialTheme.typography.headlineSmall)
                },
                text = {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            {
                                adding = false
                                model.newNote()
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            PaperIcon(PaperGlyph.Note)
                            Spacer(Modifier.width(12.dp))
                            Text("New note", modifier = Modifier.weight(1f))
                            PaperIcon(PaperGlyph.Next)
                        }
                        HorizontalDivider(color = Paper.Line)
                        TextButton(
                            {
                                adding = false
                                model.prepareImport()
                                picker.launch(arrayOf("*/*"))
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                        ) {
                            PaperIcon(PaperGlyph.Import)
                            Spacer(Modifier.width(12.dp))
                            Text("Import files", modifier = Modifier.weight(1f))
                            PaperIcon(PaperGlyph.Next)
                        }
                        TextButton(
                            {
                                adding = false
                                model.prepareImport()
                                treePicker.launch(null)
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Import folder")
                        }
                        Text(
                            "Imports create encrypted copies. Your originals stay where they are.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Paper.Muted,
                        )
                    }
                },
                confirmButton = { TextButton({ adding = false }) { Text("Cancel") } },
            )
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
private fun LockScreen(model: VaultModel, busy: Boolean) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var recoveryMode by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val creating = !model.store.exists
    Spacer(Modifier.height(16.dp))
    Surface(color = Paper.AccentSoft, shape = RoundedCornerShape(16.dp)) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            PaperIcon(PaperGlyph.Lock, color = Paper.Accent, modifier = Modifier.size(28.dp))
        }
    }
    Text(
        if (recoveryMode) "A way back in."
        else if (creating) "A private place for everything." else "Welcome back.",
        style = MaterialTheme.typography.headlineLarge,
    )
    Text(
        if (recoveryMode) "Use your recovery key to choose a new password."
        else if (creating)
            "Your thoughts, documents and moments. Kept on this device, behind your password."
        else "Enter your password to open your vault.",
        color = Paper.Muted,
    )
    if (recoveryMode)
        OutlinedTextField(
            secret,
            { secret = it },
            label = { Text("Recovery key") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy,
        )
    OutlinedTextField(
        password,
        { password = it },
        label = { Text(if (recoveryMode) "New password" else "Vault password") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("vault-password"),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        enabled = !busy,
    )
    if (creating || recoveryMode) {
        OutlinedTextField(
            confirmation,
            { confirmation = it },
            label = { Text("Confirm password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("vault-confirmation"),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
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
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .heightIn(min = 52.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton({
                focus.clearFocus()
                keyboard?.hide()
                model.back()
            }) {
                PaperIcon(PaperGlyph.Back, "Back")
                Spacer(Modifier.width(4.dp))
            }
            Text(
                if (item?.mime == "application/x-securenote") "Notes" else item?.name.orEmpty(),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
            )
            TextButton({
                model.lock()
                focus.clearFocus()
                keyboard?.hide()
            }) {
                PaperIcon(PaperGlyph.Lock)
                Spacer(Modifier.width(4.dp))
                Text("Lock")
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
            return
        }
        val value = item
        if (value == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return
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
                    MediaViewer(model, id, value)
                value.mime == "application/pdf" -> SecurePdfReader(model, id)
                value.mime.startsWith("image/") -> ImageViewer(model, id)
                value.mime.startsWith("text/") -> TextViewer(model, id)
                else ->
                    Column(
                        Modifier.fillMaxWidth().padding(32.dp),
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

@Composable
private fun MediaViewer(model: VaultModel, id: String, item: VaultItem) {
    val context = LocalContext.current
    var playbackError by remember(id) { mutableStateOf("") }
    val player =
        remember(id) {
            val factory = DataSource.Factory { VaultMediaSource(model.store, id) }
            ExoPlayer.Builder(context)
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(1000, 5000, 250, 500)
                        .setTargetBufferBytes(24 * 1024 * 1024)
                        .setPrioritizeTimeOverSizeThresholds(false)
                        .build()
                )
                .build()
                .apply {
                    setMediaItem(MediaItem.fromUri("vault://$id"))
                    prepare()
                }
        }
    DisposableEffect(player) {
        val listener =
            object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    playbackError =
                        "This media could not be played. Its format may be unsupported or the file may be damaged."
                    model.playing = false
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    model.playing = isPlaying
                }
            }
        player.addListener(listener)
        var released = false
        fun release() {
            if (!released) {
                released = true
                model.playing = false
                player.removeListener(listener)
                player.stop()
                player.clearVideoSurface()
                player.release()
            }
        }
        val unregister = model.onLock { release() }
        onDispose {
            unregister()
            release()
        }
    }
    val audio = item.mime.startsWith("audio/")
    Column(
        Modifier.fillMaxSize()
            .then(if (audio) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (audio) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = Paper.Surface,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Paper.Line),
            ) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    PaperIcon(
                        PaperGlyph.Audio,
                        color = Paper.Accent,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                item.name,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Audio · ${fileSize(item.size)} · On this device",
                style = MaterialTheme.typography.bodySmall,
                color = Paper.Muted,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        if (playbackError.isNotEmpty())
            Text(
                playbackError,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(PaperSpacing.Screen),
            )
        AndroidView(
            factory = {
                (android.view.LayoutInflater.from(it).inflate(R.layout.paper_player, null, false)
                        as PlayerView)
                    .apply {
                        this.player = player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setBackgroundColor(android.graphics.Color.rgb(246, 241, 231))
                        setShutterBackgroundColor(
                            if (audio) android.graphics.Color.rgb(246, 241, 231)
                            else android.graphics.Color.rgb(37, 40, 38)
                        )
                        controllerShowTimeoutMs = if (audio) 0 else 5000
                        controllerHideOnTouch = !audio
                        (videoSurfaceView as? SurfaceView)?.setSecure(true)
                    }
            },
            modifier =
                Modifier.fillMaxWidth()
                    .then(
                        if (audio)
                            Modifier.height(
                                (160 +
                                        24 *
                                            androidx.compose.ui.platform.LocalDensity.current
                                                .fontScale)
                                    .dp
                            )
                        else Modifier.weight(1f)
                    )
                    .testTag("native-media-player"),
            onRelease = { it.player = null },
        )
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
    Box(Modifier.fillMaxSize()) {
        key(view) {
            AndroidView(
                factory = {
                    view.also {
                        it.onState = { state = it }
                        state = it.state
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = {
                    it.onState = null
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
            Row(Modifier.align(Alignment.BottomCenter).background(Paper.Background)) {
                TextButton({ view.fitToScreen() }) { Text("Fit") }
                TextButton({ view.changeZoom(1.5f) }) { Text("Zoom in") }
                TextButton({ view.changeZoom(1 / 1.5f) }) { Text("Zoom out") }
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
