@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.securenote.vault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.*
import androidx.media3.common.*
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*

@Composable
internal fun SecureMediaViewer(model: VaultModel, id: String, item: VaultItem) {
    val context = LocalContext.current
    val hostActivity = remember(context) { context.videoHostActivity() }
    val initialOrientation = remember(hostActivity, id) { hostActivity?.requestedOrientation }
    var rotated by remember(id) { mutableStateOf(false) }
    DisposableEffect(hostActivity, id) {
        onDispose {
            if (
                rotated &&
                    hostActivity != null &&
                    !hostActivity.isFinishing &&
                    !hostActivity.isDestroyed
            )
                initialOrientation?.let { hostActivity.requestedOrientation = it }
        }
    }
    val audio = item.mime.startsWith("audio/")
    var error by remember(id) { mutableStateOf("") }
    var queue by remember(id) { mutableStateOf(emptyList<ItemSummary>()) }
    var index by remember(id) { mutableIntStateOf(0) }
    var playing by remember(id) { mutableStateOf(false) }
    var buffering by remember(id) { mutableStateOf(true) }
    var position by remember(id) { mutableLongStateOf(0) }
    var duration by remember(id) { mutableLongStateOf(0) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var volume by remember { mutableFloatStateOf(1f) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var showQueue by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(false) }
    var deadline by remember { mutableLongStateOf(0) }
    var full by remember { mutableStateOf(false) }
    var captions by remember { mutableStateOf(false) }
    var controlsVisible by remember(id) { mutableStateOf(true) }
    var controlsInteraction by remember(id) { mutableIntStateOf(0) }
    val orientation = LocalConfiguration.current.orientation
    var hasCaptions by remember { mutableStateOf(false) }
    var title by remember(id) { mutableStateOf(item.name.substringBeforeLast('.')) }
    var artist by remember { mutableStateOf("On this device") }
    val player =
        remember(id) {
            ExoPlayer.Builder(context)
                .setLooper(android.os.Looper.getMainLooper())
                .setSeekBackIncrementMs(10000)
                .setSeekForwardIncrementMs(10000)
                .setMediaSourceFactory(
                    ProgressiveMediaSource.Factory(
                        DataSource.Factory { VaultMediaSource(model.store) }
                    )
                )
                .setLoadControl(
                    DefaultLoadControl.Builder()
                        .setBufferDurationsMs(1000, 5000, 250, 500)
                        .setTargetBufferBytes(24 * 1024 * 1024)
                        .build()
                )
                .build()
        }
    LaunchedEffect(id) {
        withContext(Dispatchers.Main.immediate) {
            try {
                val siblings =
                    withContext(Dispatchers.IO) { model.store.dao.mediaSiblings(id, audio) }
                val selected =
                    ItemSummary(
                        item.rowId,
                        item.id,
                        item.name,
                        item.mime,
                        item.size,
                        item.updated,
                        item.pinned,
                    )
                queue = if (siblings.any { it.id == id }) siblings else listOf(selected) + siblings
                player.setMediaItems(
                    queue.map {
                        MediaItem.Builder().setUri("vault://${it.id}").setMimeType(it.mime).build()
                    },
                    queue.indexOfFirst { it.id == id }.coerceAtLeast(0),
                    0,
                )
                player.prepare()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (
                    context.applicationInfo.flags and
                        android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
                )
                    android.util.Log.w(
                        "VaultMedia",
                        "Setup failed: ${e.javaClass.name}\n" +
                            e.stackTrace.take(12).joinToString("\n"),
                    )
                error = "This media could not be opened. The encrypted file is kept."
            }
        }
    }
    DisposableEffect(player) {
        val listener =
            object : Player.Listener {
                override fun onEvents(p: Player, events: Player.Events) {
                    playing = p.isPlaying
                    model.playing = p.isPlaying
                    buffering = p.playbackState == Player.STATE_BUFFERING
                    index = p.currentMediaItemIndex.coerceAtLeast(0)
                    duration = p.duration.coerceAtLeast(0)
                    title =
                        p.mediaMetadata.title?.toString()?.take(255)
                            ?: queue.getOrNull(index)?.name?.substringBeforeLast('.')
                            ?: item.name.substringBeforeLast('.')
                    artist = p.mediaMetadata.artist?.toString()?.take(255) ?: "On this device"
                    hasCaptions =
                        p.currentTracks.groups.any {
                            it.type == C.TRACK_TYPE_TEXT && it.isSupported
                        }
                }

                override fun onPlayerError(e: PlaybackException) {
                    error =
                        "This media could not be played. Its format may be unsupported or the file may be damaged."
                    model.playing = false
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
        val unregister = model.onLock(::release)
        onDispose {
            unregister()
            release()
        }
    }
    LaunchedEffect(player) {
        withContext(Dispatchers.Main.immediate) {
            while (isActive && model.unlocked.value) {
                position = player.currentPosition.coerceAtLeast(0)
                duration = player.duration.coerceAtLeast(0)
                delay(250)
            }
        }
    }
    LaunchedEffect(deadline) {
        withContext(Dispatchers.Main.immediate) {
            if (deadline > 0) {
                delay((deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (model.unlocked.value) player.pause()
                deadline = 0
            }
        }
    }
    LaunchedEffect(
        playing,
        controlsVisible,
        controlsInteraction,
        seeking,
        settings,
        full,
        orientation,
        error,
    ) {
        if (
            !audio && playing && controlsVisible && seeking == null && !settings && error.isEmpty()
        ) {
            delay(3000)
            controlsVisible = false
        }
    }
    val showControls = audio || controlsVisible || !playing || error.isNotEmpty()
    fun playPause() {
        controlsVisible = true
        controlsInteraction++
        if (player.isPlaying) player.pause()
        else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }
    fun changeSpeed() {
        speed =
            when (speed) {
                1f -> 1.25f
                1.25f -> 1.5f
                1.5f -> 2f
                2f -> .75f
                else -> 1f
            }
        player.setPlaybackSpeed(speed)
    }
    @Composable
    fun transport(compact: Boolean = false) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Paper.Surface,
            border = BorderStroke(1.dp, Paper.Line),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .testTag("media-controls")
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            controlsInteraction++
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                if (error.isNotEmpty())
                    Text(
                        error,
                        modifier = Modifier.testTag("media-error"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                PaperSlider(
                    seeking ?: position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                    { seeking = it },
                    0f..duration.coerceAtLeast(1).toFloat(),
                    Modifier.fillMaxWidth().testTag("media-seek"),
                    finished = {
                        seeking?.let {
                            player.seekTo(it.toLong())
                            position = it.toLong()
                        }
                        seeking = null
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        mediaTime(position),
                        style = MaterialTheme.typography.labelSmall,
                        color = Paper.Muted,
                    )
                    Text(
                        mediaTime(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = Paper.Muted,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (audio)
                        PaperAction(
                            "Shuffle",
                            PaperGlyph.Shuffle,
                            Modifier.weight(1f),
                            selected = shuffle,
                            enabled = queue.size > 1,
                        ) {
                            shuffle = !shuffle
                            player.shuffleModeEnabled = shuffle
                        }
                    else
                        PaperAction("Back 10 seconds", PaperGlyph.Rewind, Modifier.weight(1f)) {
                            player.seekBack()
                        }
                    if (!compact)
                        PaperAction(
                            "Previous track",
                            PaperGlyph.Previous,
                            Modifier.weight(1f),
                            enabled = player.hasPreviousMediaItem(),
                        ) {
                            player.seekToPreviousMediaItem()
                        }
                    Surface(
                        shape = CircleShape,
                        color = Paper.Accent,
                        modifier = Modifier.weight(1f),
                    ) {
                        PaperAction(
                            if (playing) "Pause" else "Play",
                            if (playing) PaperGlyph.Pause else PaperGlyph.Play,
                            dark = true,
                            action = ::playPause,
                        )
                    }
                    if (!compact)
                        PaperAction(
                            "Next track",
                            PaperGlyph.Skip,
                            Modifier.weight(1f),
                            enabled = player.hasNextMediaItem(),
                        ) {
                            player.seekToNextMediaItem()
                        }
                    if (audio)
                        PaperAction(
                            "Repeat",
                            PaperGlyph.Repeat,
                            Modifier.weight(1f),
                            selected = repeat != Player.REPEAT_MODE_OFF,
                        ) {
                            repeat = (repeat + 1) % 3
                            player.repeatMode = repeat
                        }
                    else
                        PaperAction("Forward 10 seconds", PaperGlyph.Forward, Modifier.weight(1f)) {
                            player.seekForward()
                        }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (audio)
                        PaperAction("Queue", PaperGlyph.List, Modifier.weight(1f)) {
                            showQueue = true
                        }
                    TextButton(::changeSpeed, Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(
                            "${speed.toString().removeSuffix(".0")}×",
                            maxLines = 1,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    PaperAction("Playback settings", PaperGlyph.Volume, Modifier.weight(1f)) {
                        settings = true
                    }
                    if (!audio)
                        PaperAction(
                            "Rotate video",
                            PaperGlyph.Rotate,
                            Modifier.weight(1f),
                            selected =
                                orientation ==
                                    android.content.res.Configuration.ORIENTATION_LANDSCAPE,
                            enabled = hostActivity != null,
                        ) {
                            controlsVisible = true
                            controlsInteraction++
                            rotated = true
                            hostActivity?.requestedOrientation =
                                if (
                                    orientation ==
                                        android.content.res.Configuration.ORIENTATION_LANDSCAPE
                                )
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                    if (audio)
                        PaperAction(
                            "Sleep timer",
                            PaperGlyph.Timer,
                            Modifier.weight(1f),
                            selected = deadline > 0,
                        ) {
                            timer = true
                        }
                    else
                        PaperAction(
                            "Full screen",
                            PaperGlyph.Fit,
                            Modifier.weight(1f),
                            selected = full,
                        ) {
                            full = !full
                        }
                }
            }
        }
    }
    @Composable
    fun picture(modifier: Modifier) {
        Box(modifier.background(Paper.Media).clipToBounds(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = {
                    PlayerView(it).apply {
                        useController = false
                        this.player = player
                        (videoSurfaceView as? SurfaceView)?.setSecure(true)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.player = null },
            )
            Box(
                Modifier.matchParentSize().testTag("video-picture").clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel =
                        if (showControls) "Hide video controls" else "Show video controls",
                ) {
                    controlsVisible = !showControls
                    controlsInteraction++
                }
            )
            if (buffering) CircularProgressIndicator(Modifier.size(32.dp), color = Paper.Accent)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("native-media-player")) {
        val short = maxHeight < 360.dp
        @Composable
        fun video(modifier: Modifier, close: Boolean) {
            BoxWithConstraints(modifier.background(Paper.Media)) {
                picture(Modifier.fillMaxSize())
                AnimatedVisibility(
                    visible = showControls,
                    modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 460.dp),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Column(
                        Modifier.heightIn(max = maxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp)
                    ) {
                        transport(compact = true)
                    }
                }
                if (close && showControls)
                    PaperAction(
                        "Close full screen",
                        PaperGlyph.Back,
                        Modifier.align(Alignment.TopStart),
                        dark = true,
                    ) {
                        full = false
                    }
            }
        }
        if (!audio && full) {
            Dialog(
                onDismissRequest = { full = false },
                properties =
                    DialogProperties(
                        usePlatformDefaultWidth = false,
                        securePolicy = SecureFlagPolicy.SecureOn,
                    ),
            ) {
                video(Modifier.fillMaxSize().safeDrawingPadding(), true)
            }
        } else if (!audio) {
            video(Modifier.fillMaxSize(), false)
        } else if (audio && short)
            Row(
                Modifier.fillMaxSize().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).padding(end = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AlbumArt(Modifier.size(80.dp))
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Column(Modifier.weight(2f).verticalScroll(rememberScrollState())) { transport() }
            }
        else
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (audio) {
                    AlbumArt(
                        Modifier.padding(top = 16.dp, bottom = 12.dp)
                            .widthIn(max = 260.dp)
                            .fillMaxWidth(.68f)
                            .aspectRatio(1f)
                    )
                    Text(
                        title,
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artist,
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Paper.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (!full) picture(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
                if (!audio)
                    Text(
                        title,
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                Text(
                    "${(queue.getOrNull(index)?.name ?: item.name).substringAfterLast('.', "Media").uppercase(java.util.Locale.ROOT).take(8)} · Encrypted ${if (audio) "audio" else "video"}",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Paper.Muted,
                    maxLines = 1,
                )
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    transport()
                }
                if (!audio && queue.size > 1)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Up next",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            PaperAction("Queue", PaperGlyph.List) { showQueue = true }
                        }
                        queue.drop(index + 1).take(2).forEach { entry ->
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable {
                                    player.seekTo(queue.indexOf(entry), 0)
                                    player.play()
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PaperThumbnail(
                                    model,
                                    entry,
                                    Modifier.size(64.dp, 44.dp).clip(RoundedCornerShape(8.dp)),
                                )
                                Text(
                                    entry.name,
                                    Modifier.weight(1f).padding(12.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                PaperIcon(PaperGlyph.Play)
                            }
                        }
                    }
            }
    }
    if (showQueue)
        PaperSheet("Queue", { showQueue = false }) {
            queue.forEachIndexed { i, entry ->
                TextButton(
                    {
                        player.seekTo(i, 0)
                        player.play()
                        showQueue = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (i == index) PaperIcon(PaperGlyph.Play)
                    Text(
                        entry.name,
                        Modifier.weight(1f).padding(8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    if (settings)
        PaperSheet("Playback", { settings = false }) {
            Text("Volume", style = MaterialTheme.typography.labelLarge)
            PaperSlider(
                volume,
                {
                    volume = it
                    player.volume = it
                },
                0f..1f,
            )
            if (!audio)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Captions", Modifier.weight(1f))
                    Switch(
                        captions,
                        {
                            captions = it
                            player.trackSelectionParameters =
                                player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !it)
                                    .build()
                        },
                        enabled = hasCaptions,
                    )
                }
            if (!audio)
                TextButton({
                    settings = false
                    showQueue = true
                }) {
                    Text("Queue")
                }
        }
    if (timer)
        PaperSheet("Sleep timer", { timer = false }) {
            listOf(0, 15, 30, 60).forEach { minutes ->
                TextButton(
                    {
                        deadline =
                            if (minutes == 0) 0
                            else android.os.SystemClock.elapsedRealtime() + minutes * 60000L
                        timer = false
                    },
                    Modifier.fillMaxWidth(),
                ) {
                    Text(if (minutes == 0) "Off" else "$minutes minutes")
                }
            }
        }
}

private fun mediaTime(ms: Long): String {
    val sec = ms.coerceAtLeast(0) / 1000
    return if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
    else "%02d:%02d".format(sec / 60, sec % 60)
}

@Composable
private fun AlbumArt(modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xffe8ddc8))) {
        drawCircle(
            Paper.Accent.copy(alpha = .8f),
            size.width * .18f,
            Offset(size.width * .72f, size.height * .26f),
        )
        val back =
            Path().apply {
                moveTo(0f, size.height * .6f)
                lineTo(size.width * .28f, size.height * .29f)
                lineTo(size.width * .68f, size.height * .7f)
                lineTo(size.width, size.height * .48f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(back, Color(0xff567368))
        val front =
            Path().apply {
                moveTo(0f, size.height * .74f)
                lineTo(size.width * .23f, size.height * .59f)
                lineTo(size.width * .72f, size.height * .84f)
                lineTo(size.width, size.height * .65f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        drawPath(front, Color(0xff294c40))
    }
}

private tailrec fun Context.videoHostActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.videoHostActivity()
        else -> null
    }
