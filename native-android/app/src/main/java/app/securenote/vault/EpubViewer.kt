package app.securenote.vault

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Native text and internally resolved images/links only. No WebView, disk cache or external
 * intents.
 */
internal class EpubTextView(context: Context) : TextView(context) {
    private var displayed: EpubFrame? = null
    private var displayedFont = -1
    private var renderedWidth = -1
    private var linkAction: (String) -> Unit = {}

    init {
        setTextColor(AndroidColor.rgb(39, 43, 40))
        setLinkTextColor(AndroidColor.rgb(169, 81, 63))
        typeface = Typeface.create("serif", Typeface.NORMAL)
        setLineSpacing(6 * resources.displayMetrics.density, 1.15f)
        setPadding(0, 0, 0, 0)
        setTextIsSelectable(false)
        isLongClickable = false
        linksClickable = true
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        freezesText = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (Build.VERSION.SDK_INT >= 30)
            importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }

    override fun startActionMode(
        callback: android.view.ActionMode.Callback
    ): android.view.ActionMode? = null

    override fun startActionMode(
        callback: android.view.ActionMode.Callback,
        type: Int,
    ): android.view.ActionMode? = null

    override fun onTextContextMenuItem(id: Int): Boolean = false

    override fun onKeyShortcut(keyCode: Int, event: android.view.KeyEvent): Boolean =
        if (
            event.isCtrlPressed &&
                (keyCode == android.view.KeyEvent.KEYCODE_C ||
                    keyCode == android.view.KeyEvent.KEYCODE_X)
        )
            true
        else super.onKeyShortcut(keyCode, event)

    fun display(frame: EpubFrame, font: Int, onLink: (String) -> Unit) {
        linkAction = onLink
        if (displayed === frame && displayedFont == font && renderedWidth == width) return
        textSize = font.toFloat()
        val maxWidth =
            (if (width > 0) width.toFloat()
                else resources.displayMetrics.widthPixels - 48 * resources.displayMetrics.density)
                .toInt()
                .coerceAtLeast(1)
        val maxHeight = minOf(resources.displayMetrics.heightPixels * 1.5f, 2400f)
        val content =
            Html.fromHtml(
                frame.html,
                Html.FROM_HTML_MODE_LEGACY,
                Html.ImageGetter { name ->
                    val bitmap = frame.images[name]
                    if (bitmap == null)
                        ColorDrawable(AndroidColor.TRANSPARENT).apply { setBounds(0, 0, 1, 1) }
                    else
                        BitmapDrawable(resources, bitmap).apply {
                            val scale =
                                minOf(
                                    maxWidth.toFloat() / bitmap.width,
                                    maxHeight / bitmap.height,
                                    2f,
                                )
                            setBounds(
                                0,
                                0,
                                (bitmap.width * scale).toInt().coerceAtLeast(1),
                                (bitmap.height * scale).toInt().coerceAtLeast(1),
                            )
                        }
                },
                null,
            )
        val safe = SpannableStringBuilder(content)
        // Reader font controls own text sizing, including adversarial nested publisher markup.
        safe.getSpans(0, safe.length, android.text.style.RelativeSizeSpan::class.java).forEach {
            safe.removeSpan(it)
        }
        safe.getSpans(0, safe.length, android.text.style.AbsoluteSizeSpan::class.java).forEach {
            safe.removeSpan(it)
        }
        safe.getSpans(0, safe.length, URLSpan::class.java).forEach { span ->
            val start = safe.getSpanStart(span)
            val end = safe.getSpanEnd(span)
            val flags = safe.getSpanFlags(span)
            safe.removeSpan(span)
            val link = span.url
            if (
                start >= 0 &&
                    end >= start &&
                    link.length <= 4096 &&
                    link.matches(Regex("vault-epub:[0-9]{1,4}#[A-Za-z0-9%+._~*\\-]*"))
            ) {
                safe.setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            onLink(link)
                        }

                        override fun updateDrawState(ds: TextPaint) {
                            ds.color = AndroidColor.rgb(169, 81, 63)
                            ds.isUnderlineText = true
                        }
                    },
                    start,
                    end,
                    flags,
                )
            }
        }
        val previous = displayed
        text = safe
        movementMethod = LinkMovementMethod.getInstance()
        isLongClickable = false
        displayed = frame
        displayedFont = font
        renderedWidth = width
        if (previous !== frame) previous?.close()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && w != oldw) displayed?.let { display(it, displayedFont, linkAction) }
    }

    fun clearContent() {
        text = ""
        movementMethod = null
        displayed?.close()
        displayed = null
        displayedFont = -1
        renderedWidth = -1
        linkAction = {}
    }
}

@Composable
internal fun EpubViewer(model: VaultModel, id: String) {
    val context = LocalContext.current
    val client = remember(id) { EpubClient(context) }
    val view = remember(id) { EpubTextView(context) }
    val scope = rememberCoroutineScope()
    var frame by remember(id) { mutableStateOf<EpubFrame?>(null) }
    var catalog by remember(id) { mutableStateOf<EpubCatalog?>(null) }
    var font by remember(id) { mutableIntStateOf(18) }
    var loading by remember(id) { mutableStateOf(true) }
    var failure by remember(id) { mutableStateOf("") }
    var contents by remember(id) { mutableStateOf(false) }
    var initialPosition by remember(id) { mutableStateOf<EpubPosition?>(null) }
    // Reading position belongs only in the encrypted index, never saved-instance state.
    val scroll =
        remember(id, frame != null) {
            ScrollState(if (frame != null) initialPosition?.scroll ?: 0 else 0)
        }
    fun savePosition(value: EpubFrame) {
        model.queueReadingState(
            id,
            EpubPosition(value.chapter, value.section, font, scroll.value).encode(),
        )
    }
    fun navigate(action: suspend () -> EpubFrame) {
        if (loading || !model.unlocked.value || failure.isNotEmpty()) return
        loading = true
        scope.launch {
            try {
                val next = action()
                if (!model.unlocked.value) {
                    next.close()
                    return@launch
                }
                frame = next
                scroll.scrollTo(0)
                savePosition(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e.message?.take(240) ?: "This section could not be read."
                client.close()
            } finally {
                loading = false
            }
        }
    }
    DisposableEffect(client, view) {
        val unregister = model.onLock {
            view.clearContent()
            frame?.close()
            frame = null
            catalog = null
            client.close()
        }
        onDispose {
            unregister()
            view.clearContent()
            frame?.close()
            frame = null
            catalog = null
            client.close()
        }
    }
    LaunchedEffect(id) {
        try {
            val position =
                withContext(Dispatchers.IO) {
                    EpubPosition.decode(model.store.dao.readingState(id))
                }
            font = position.fontSize
            initialPosition = position
            val opened = client.open(model.store, id, position)
            if (!model.unlocked.value) {
                opened.close()
                return@LaunchedEffect
            }
            frame = opened
            catalog = client.catalog
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e.message?.take(240) ?: "This EPUB could not be opened."
            client.close()
        } finally {
            loading = false
        }
    }
    LaunchedEffect(frame?.chapter, frame?.section, font, scroll) {
        if (frame == null) return@LaunchedEffect
        snapshotFlow { scroll.value }
            .collect { offset ->
                val current = frame
                if (current != null && model.unlocked.value && model.store.isOpen) {
                    model.queueReadingState(
                        id,
                        EpubPosition(current.chapter, current.section, font, offset).encode(),
                    )
                }
            }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                { contents = true },
                enabled = catalog != null && !loading && failure.isEmpty(),
            ) {
                PaperIcon(PaperGlyph.List)
                Spacer(Modifier.width(6.dp))
                Text("Contents")
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                {
                    font = (font - 2).coerceAtLeast(16)
                    frame?.let(::savePosition)
                },
                enabled = font > 16 && frame != null && failure.isEmpty(),
            ) {
                Text("A−")
            }
            TextButton(
                {
                    font = (font + 2).coerceAtMost(28)
                    frame?.let(::savePosition)
                },
                enabled = font < 28 && frame != null && failure.isEmpty(),
            ) {
                Text("A+")
            }
        }
        if (loading)
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = Paper.Accent,
                trackColor = Paper.Inset,
            )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (failure.isNotEmpty())
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Unable to read this book",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(failure, color = Paper.Muted)
                    Text(
                        "The encrypted file remains in your vault.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Paper.Muted,
                    )
                }
            else
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(PaperSpacing.Screen)) {
                    val current = frame
                    if (current != null)
                        AndroidView(
                            factory = { view },
                            modifier = Modifier.fillMaxWidth().testTag("epub-content"),
                            update = {
                                it.display(current, font) { link ->
                                    navigate { client.follow(link) }
                                }
                            },
                            onRelease = { it.clearContent() },
                        )
                }
        }
        HorizontalDivider(color = Paper.Line)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                { navigate { client.move(-1) } },
                enabled = frame?.previous == true && !loading && failure.isEmpty(),
            ) {
                PaperIcon(PaperGlyph.Back, "Previous section")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                frame?.let { current ->
                    Text(
                        "Chapter ${current.chapter+1} / ${catalog?.chapters?.size ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                    Text(
                        "Section ${current.section+1} / ${current.sections}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Paper.Muted,
                        maxLines = 1,
                    )
                }
            }
            IconButton(
                { navigate { client.move(1) } },
                enabled = frame?.next == true && !loading && failure.isEmpty(),
            ) {
                PaperIcon(PaperGlyph.Next, "Next section")
            }
        }
    }
    if (contents)
        AlertDialog(
            modifier = Modifier.secureEditing(),
            onDismissRequest = { contents = false },
            title = { Text("Contents", style = MaterialTheme.typography.headlineSmall) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    itemsIndexed(catalog?.chapters.orEmpty()) { index, label ->
                        TextButton(
                            {
                                contents = false
                                navigate { client.go(index) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${index + 1}. $label",
                                modifier = Modifier.fillMaxWidth(),
                                color = if (index == frame?.chapter) Paper.Accent else Paper.Ink,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton({ contents = false }) { Text("Close") } },
        )
}
