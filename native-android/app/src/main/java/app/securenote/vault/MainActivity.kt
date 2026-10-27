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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.paging.compose.collectAsLazyPagingItems
import app.securenote.vault.core.*
import kotlinx.coroutines.*

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
        if (Build.VERSION.SDK_INT >= 31) window.setHideOverlayWindows(true)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
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
    val message by model.message.collectAsState()
    val selected by model.screen.collectAsState()
    val status by model.status.collectAsState()
    val legacySource by model.legacySource.collectAsState()
    val attach by model.attachmentPicker.collectAsState()
    var settings by remember { mutableStateOf(false) }
    val recovery by model.recovery.collectAsState()
    val context = LocalContext.current
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            model.importUris(uris)
        }
    val oldPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            model.legacySource.value = uri
        }
    LaunchedEffect(attach) {
        if (attach != null) {
            model.attachmentPicker.value = null
            picker.launch(arrayOf("*/*"))
        }
    }
    BackHandler(open && selected != null) { model.back() }
    Column(
        Modifier.fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .then(
                if (!open || recovery != null) Modifier.verticalScroll(rememberScrollState())
                else Modifier
            )
            .semantics { testTagsAsResourceId = true }
            .secureEditing()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("SecureNote Vault", style = MaterialTheme.typography.headlineMedium)
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotBlank()) Text(status)
        if (!open) {
            LockScreen(model, busy)
        } else if (recovery != null) {
            Text("Record your recovery key", style = MaterialTheme.typography.titleLarge)
            Text(
                "Keep this key outside this phone. Anyone with it can unlock the vault. It cannot restore deleted files. This screen cannot be shared or exported."
            )
            Text(recovery!!.joinToString("") { "%02x".format(it) }.chunked(8).joinToString(" "))
            var confirmed by remember { mutableStateOf(false) }
            Row {
                Checkbox(confirmed, { confirmed = it })
                Text("I recorded the key in a safe place")
            }
            Button({ model.dismissRecovery() }, enabled = confirmed) { Text("Continue") }
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (selected != null) Button({ model.back() }) { Text("Back") }
                Button({ model.lock() }) { Text("Lock") }
                TextButton({ settings = true }) { Text("Settings") }
                if (selected == null) {
                    Button({ picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Import") }
                    Button({ model.newNote() }, enabled = !busy) { Text("New note") }
                }
            }
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
            if (selected == null) Collection(model) else ItemScreen(model, selected!!)
        }
    }
}

@Composable
private fun LockScreen(model: VaultModel, busy: Boolean) {
    var recoveryMode by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val exists = model.store.exists
    val creating = !exists
    Text(
        if (creating) "Create a local encrypted vault"
        else if (recoveryMode) "Recover vault" else "Unlock your vault"
    )
    Text(
        "Files and notes stay encrypted on this device. Deleting app storage permanently deletes the vault."
    )
    if (recoveryMode)
        OutlinedTextField(
            secret,
            { secret = it },
            label = { Text("Recovery key") },
            visualTransformation = PasswordVisualTransformation(),
        )
    OutlinedTextField(
        password,
        { password = it },
        modifier = Modifier.testTag("vault-password"),
        label = { Text(if (recoveryMode) "New password" else "Vault password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        enabled = !busy,
    )
    if (creating || recoveryMode) {
        Text("Use a strong passphrase of at least 16 characters.")
        OutlinedTextField(
            confirmation,
            { confirmation = it },
            modifier = Modifier.testTag("vault-confirmation"),
            label = { Text("Confirm password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    }
    val valid =
        if (creating || recoveryMode) password.length >= 16 && password == confirmation
        else password.isNotEmpty()
    Button(
        enabled = !busy && valid,
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
    ) {
        Text(if (creating) "Create vault" else if (recoveryMode) "Recover" else "Unlock")
    }
    if (exists)
        TextButton(
            {
                recoveryMode = !recoveryMode
                secret = ""
                password = ""
                confirmation = ""
            },
            enabled = !busy,
        ) {
            Text(if (recoveryMode) "Use password" else "Use recovery key")
        }
    if (model.pendingImports.isNotEmpty())
        Text("Unlock to import your selected files. Originals remain outside the vault.")
    DisposableEffect(Unit) {
        onDispose {
            password = ""
            confirmation = ""
            secret = ""
        }
    }
}

@Composable
private fun Collection(model: VaultModel) {
    val query by model.query.collectAsState()
    OutlinedTextField(
        query,
        { model.query.value = it },
        label = { Text("Search names and notes") },
        modifier = Modifier.fillMaxWidth(),
    )
    val items = model.pages.collectAsLazyPagingItems()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items.itemCount, key = { index -> items.peek(index)?.id ?: "placeholder-$index" }) {
            index ->
            items[index]?.let { item ->
                Card(Modifier.fillMaxWidth().clickable { model.navigate(item.id) }) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ItemThumbnail(model, item)
                        Column {
                            Text(
                                (if (item.pinned) "★ " else "") + item.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                if (item.mime == "application/x-securenote") "Note"
                                else "${item.mime} · ${item.size/1024} KiB"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemScreen(model: VaultModel, id: String) {
    var item by remember(id) { mutableStateOf<VaultItem?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        try {
            item = model.store.dao.display(id)
            if (item == null) failed = true
        } catch (e: Exception) {
            failed = true
        }
    }
    if (failed) {
        Text("Cannot read this item.")
        return
    }
    val value = item ?: return
    Text(value.name, style = MaterialTheme.typography.titleLarge)
    var deleting by remember { mutableStateOf(false) }
    Row {
        TextButton({ model.pin(id) }) { Text("Toggle pin") }
        TextButton({ deleting = true }) { Text("Delete") }
    }
    if (deleting)
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete permanently?") },
            text = { Text("There is no backup or recovery for deleted data.") },
            confirmButton = {
                TextButton({
                    deleting = false
                    model.delete(id)
                }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton({ deleting = false }) { Text("Cancel") } },
        )
    when {
        value.mime == "application/x-securenote" -> NativeNoteEditor(model, value)
        value.mime.startsWith("audio/") || value.mime.startsWith("video/") -> MediaViewer(model, id)
        value.mime == "application/pdf" -> PdfViewer(model, id)
        value.mime.startsWith("image/") -> ImageViewer(model, id)
        value.mime.startsWith("text/") -> TextViewer(model, id)
        else -> Text("Internal preview is not supported for this file format.")
    }
}

@Composable
private fun MediaViewer(model: VaultModel, id: String) {
    val context = LocalContext.current
    val player =
        remember(id) {
            val factory = DataSource.Factory { VaultMediaSource(model.store, id) }
            ExoPlayer.Builder(context)
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
    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                (videoSurfaceView as? SurfaceView)?.setSecure(true)
            }
        },
        modifier = Modifier.fillMaxWidth().height(300.dp),
        onRelease = { it.player = null },
    )
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
        modifier = Modifier.fillMaxWidth().height(450.dp),
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
    Row {
        TextButton({ page-- }, enabled = page > 0) { Text("Previous") }
        Text("${page+1} / $count")
        TextButton({ page++ }, enabled = page + 1 < count) { Text("Next") }
    }
    val scroll = remember(page) { ScrollState(0) }
    Text(text, modifier = Modifier.verticalScroll(scroll).fillMaxWidth())
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
        } catch (e: Exception) {
            failed = true
        }
    }
    LaunchedEffect(page, count, width) {
        if (count > 0)
            try {
                bitmap = client.render(page, width)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
            }
    }
    DisposableEffect(client) {
        val unregister = model.onLock { client.close() }
        onDispose {
            unregister()
            client.close()
            bitmap = null
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        TextButton({ page-- }, enabled = page > 0) { Text("Previous") }
        Text("${page+1} / $count")
        TextButton({ page++ }, enabled = page + 1 < count) { Text("Next") }
        TextButton({ width = if (width < 1536) 1536 else 768 }) { Text("Sharper / fit") }
    }
    var scale by remember(id) { mutableFloatStateOf(1f) }
    var offset by remember(id) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    bitmap?.let {
        Image(
            it.asImageBitmap(),
            "PDF page",
            Modifier.fillMaxWidth()
                .heightIn(max = 500.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .transformable(transform),
        )
    }
    if (failed)
        Text(
            "This PDF cannot be previewed. It may be damaged, unsupported, or require its own document password."
        )
}

@Composable
private fun ItemThumbnail(model: VaultModel, item: ItemSummary) {
    var bitmap by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    val busy by model.busy.collectAsState()
    LaunchedEffect(item.id, busy) {
        if (!busy)
            try {
                bitmap = model.previews.thumbnail(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {}
    }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(56.dp)) }
}
