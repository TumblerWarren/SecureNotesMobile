@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.securenote.vault

import android.content.*
import android.graphics.Bitmap
import android.os.*
import android.view.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
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
private fun VaultApp(model: VaultModel) {
    val open by model.unlocked.collectAsState()
    val busy by model.busy.collectAsState()
    val libraryState = key(open) { rememberLazyGridState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val message by model.message.collectAsState()
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
                    .padding(horizontal = 24.dp, vertical = 8.dp)
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
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
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
                            Triple("media", "Media", PaperGlyph.Media),
                            Triple("settings", "Settings", PaperGlyph.Settings),
                        )
                        .forEach { (key, label, glyph) ->
                            val active =
                                if (key == "all") category == "all" || category == "documents"
                                else category == key
                            TextButton(
                                {
                                    if (key == "settings") settings = true
                                    else model.showLibrary(key)
                                },
                                modifier = Modifier.weight(1f).testTag("nav-$key"),
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
            )
        if (legacySource != null) LegacyPasswordDialog(model)
    }
}

@Composable
private fun LockScreen(model: VaultModel, busy: Boolean) {
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
                PaperIcon(PaperGlyph.Back)
                Spacer(Modifier.width(4.dp))
                Text("Back")
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
                value.mime == "application/pdf" -> PdfViewer(model, id)
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
            Spacer(Modifier.height(20.dp))
            Surface(
                color = Paper.Surface,
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Paper.Line),
            ) {
                Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                    PaperIcon(
                        PaperGlyph.Audio,
                        color = Paper.Accent,
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
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
            Spacer(Modifier.height(20.dp))
        }
        if (playbackError.isNotEmpty())
            Text(
                playbackError,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
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
                    .then(if (audio) Modifier.height(160.dp) else Modifier.weight(1f))
                    .testTag("native-media-player"),
            onRelease = { it.player = null },
        )
        if (!audio) {
            Text(
                item.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp),
            )
            Text(
                "Video · ${fileSize(item.size)} · On this device",
                color = Paper.Muted,
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ImageViewer(model: VaultModel, id: String) {
    val context = LocalContext.current
    val view = remember(id) { TiledImageView(context, model.store, model.previews, id) }
    DisposableEffect(view) {
        val unregister = model.onLock { view.close() }
        onDispose {
            unregister()
            view.close()
        }
    }
    AndroidView(
        factory = { view },
        modifier = Modifier.fillMaxSize(),
        onRelease = { it.close() },
    )
}

@Composable
private fun TextViewer(model: VaultModel, id: String) {
    var page by remember(id) { mutableLongStateOf(0) }
    var count by remember(id) { mutableLongStateOf(1) }
    var text by remember(id) { mutableStateOf("") }
    LaunchedEffect(id, page) {
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
                "Text preview is unavailable."
            }
    }
    Column(Modifier.fillMaxSize()) {
        val scroll = remember(page) { ScrollState(0) }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif),
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(24.dp),
        )
        HorizontalDivider(color = Paper.Line)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton({ page-- }, enabled = page > 0) { Text("Previous") }
            Text(
                "${page + 1} / $count",
                style = MaterialTheme.typography.bodySmall,
                color = Paper.Muted,
            )
            TextButton({ page++ }, enabled = page + 1 < count) { Text("Next") }
        }
    }
    DisposableEffect(Unit) { onDispose { text = "" } }
}

@Composable
private fun PdfViewer(model: VaultModel, id: String) {
    val context = LocalContext.current
    val client = remember(id) { PdfClient(context) }
    var page by remember(id) { mutableIntStateOf(0) }
    var count by remember(id) { mutableIntStateOf(0) }
    var width by remember(id) { mutableIntStateOf(768) }
    var bitmap by remember(id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        try {
            count = client.open(model.store, id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }
    LaunchedEffect(page, count, width) {
        if (count > 0)
            try {
                bitmap = null
                failed = false
                bitmap = client.render(page, width)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
    }
    DisposableEffect(client) {
        val unregister = model.onLock {
            bitmap = null
            client.close()
        }
        onDispose {
            unregister()
            client.close()
            bitmap = null
        }
    }
    var scale by remember(id) { mutableFloatStateOf(1f) }
    var offset by remember(id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    LaunchedEffect(page) {
        scale = 1f
        offset = androidx.compose.ui.geometry.Offset.Zero
    }
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .background(Paper.Inset)
                .padding(16.dp)
                .then(Modifier.clipToBounds()),
            contentAlignment = Alignment.Center,
        ) {
            val image = bitmap
            if (image != null)
                Image(
                    image.asImageBitmap(),
                    "PDF page",
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .transformable(transform),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                )
            else if (!failed)
                CircularProgressIndicator(color = Paper.Accent, modifier = Modifier.size(28.dp))
            if (failed)
                Text(
                    "This PDF cannot be previewed. It may be damaged, unsupported, or require its own password.",
                    color = Paper.Muted,
                    modifier = Modifier.padding(16.dp),
                )
        }
        HorizontalDivider(color = Paper.Line)
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(Paper.Background)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton({ page-- }, enabled = page > 0) {
                PaperIcon(PaperGlyph.Back)
                Spacer(Modifier.width(4.dp))
                Text("Previous")
            }
            Text(
                if (count > 0) "${page + 1} / $count" else "—",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton({ page++ }, enabled = count > 0 && page + 1 < count) {
                Text("Next")
                Spacer(Modifier.width(4.dp))
                PaperIcon(PaperGlyph.Next)
            }
            IconButton(
                {
                    width = if (width < 1536) 1536 else 768
                    scale = if (scale > 1f) 1f else 1.5f
                    offset = androidx.compose.ui.geometry.Offset.Zero
                },
                enabled = count > 0,
            ) {
                PaperIcon(PaperGlyph.Zoom, "Zoom")
            }
        }
    }
}
