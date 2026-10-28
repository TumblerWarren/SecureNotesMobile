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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
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
        setLineSpacing(3 * resources.displayMetrics.density, 1.12f)
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
                Html.FROM_HTML_MODE_COMPACT,
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

/** Native scrolling keeps TextView measurement and swipe paging in the same coordinate system. */
internal class EpubScrollView(context: Context, private val reader: EpubTextView) :
    ScrollView(context) {
    var onPage: (Int) -> Unit = {}
    var onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> }
    private var appliedFrame: EpubFrame? = null
    private var restored = false
    private var startX = 0f
    private var startY = 0f
    private var pageGesture = false

    init {
        tag = "epub-scroll"
        isFillViewport = true
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(
            reader,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    val maximum: Int
        get() =
            ((getChildAt(0)?.height ?: 0) - height + paddingTop + paddingBottom).coerceAtLeast(0)

    private fun report() = onProgress(scrollY, maximum, height)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        report()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        report()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                pageGesture = true
            }
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_CANCEL -> pageGesture = false
            MotionEvent.ACTION_UP -> {
                val dx = event.x - startX
                val dy = event.y - startY
                val page =
                    pageGesture &&
                        kotlin.math.abs(dx) >= 48 * resources.displayMetrics.density &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                pageGesture = false
                if (page) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    onPage(if (dx < 0) 1 else -1)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    fun display(frame: EpubFrame, font: Int, restoreOffset: Int, onLink: (String) -> Unit) {
        val changed = appliedFrame !== frame
        reader.display(frame, font, onLink)
        if (changed) {
            val target = if (!restored) restoreOffset else 0
            restored = true
            appliedFrame = frame
            doOnLayout {
                scrollTo(0, target.coerceIn(0, maximum))
                report()
            }
        }
    }

    fun clearContent() {
        reader.clearContent()
        appliedFrame = null
        onPage = {}
        onProgress = { _, _, _ -> }
    }
}

@Composable
internal fun EpubViewer(model: VaultModel, id: String) {
    val context = LocalContext.current
    val client = remember(id) { EpubClient(context) }
    val view = remember(id) { EpubTextView(context) }
    val readerScroll = remember(id) { EpubScrollView(context, view) }
    var scrollOffset by remember(id) { mutableIntStateOf(0) }
    var scrollMaximum by remember(id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var frame by remember(id) { mutableStateOf<EpubFrame?>(null) }
    var catalog by remember(id) { mutableStateOf<EpubCatalog?>(null) }
    var font by remember(id) { mutableIntStateOf(18) }
    var loading by remember(id) { mutableStateOf(true) }
    var failure by remember(id) { mutableStateOf("") }
    var contents by remember(id) { mutableStateOf(false) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var initialPosition by remember(id) { mutableStateOf<EpubPosition?>(null) }
    fun savePosition(value: EpubFrame) {
        model.queueReadingState(
            id,
            EpubPosition(value.chapter, value.section, font, scrollOffset).encode(),
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
                readerScroll.scrollTo(0, 0)
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
    fun turn(direction: Int) {
        if (loading || failure.isNotEmpty()) return
        val step = (viewportHeight * .9f).toInt().coerceAtLeast(1)
        if (
            (direction > 0 && scrollOffset < scrollMaximum) || (direction < 0 && scrollOffset > 0)
        ) {
            readerScroll.smoothScrollTo(
                0,
                (scrollOffset + direction * step).coerceIn(0, scrollMaximum),
            )
        } else if (
            (direction > 0 && frame?.next == true) || (direction < 0 && frame?.previous == true)
        ) {
            navigate { client.move(direction) }
        }
    }
    DisposableEffect(client, readerScroll) {
        val unregister = model.onLock {
            readerScroll.clearContent()
            frame?.close()
            frame = null
            catalog = null
            client.close()
        }
        onDispose {
            unregister()
            readerScroll.clearContent()
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
    LaunchedEffect(frame?.chapter, frame?.section, font) {
        if (frame == null) return@LaunchedEffect
        snapshotFlow { scrollOffset }
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
                PaperIcon(PaperGlyph.List, "Contents")
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
            else {
                val current = frame
                if (current != null)
                    AndroidView(
                        factory = { readerScroll },
                        modifier =
                            Modifier.fillMaxSize()
                                .padding(PaperSpacing.Screen)
                                .testTag("epub-content"),
                        update = {
                            it.onPage = ::turn
                            it.onProgress = { offset, maximum, height ->
                                scrollOffset = offset
                                scrollMaximum = maximum
                                viewportHeight = height
                            }
                            it.display(current, font, initialPosition?.scroll ?: 0) { link ->
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
                { turn(-1) },
                enabled =
                    (frame?.previous == true || scrollOffset > 0) && !loading && failure.isEmpty(),
            ) {
                PaperIcon(PaperGlyph.Back, "Previous section")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                frame?.let { current ->
                    Text(
                        if (current.contentIndex >= 0)
                            "Chapter ${current.contentIndex + 1} / ${catalog?.contents?.size ?: 0}"
                        else
                            "Reading part ${current.chapter + 1} / ${catalog?.chapters?.size ?: 0}",
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
                { turn(1) },
                enabled =
                    (frame?.next == true || scrollOffset < scrollMaximum) &&
                        !loading &&
                        failure.isEmpty(),
            ) {
                PaperIcon(PaperGlyph.Next, "Next section")
            }
        }
    }
    if (contents)
        PaperSheet("Chapters", { contents = false }) {
            val chapters = catalog?.contents.orEmpty()
            val list =
                rememberLazyListState(
                    initialFirstVisibleItemIndex = (frame?.contentIndex ?: -1).coerceAtLeast(0)
                )
            val available =
                androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp * .55f
            @Composable
            fun chapterRow(index: Int) {
                val entry = chapters[index]
                val label = entry.title
                val active = index == frame?.contentIndex
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    color = if (active) Paper.AccentSoft else Paper.Background,
                ) {
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable {
                                contents = false
                                navigate { client.follow(entry.link) }
                            }
                            .padding(
                                start = 12.dp + (entry.depth * 12).dp,
                                end = 12.dp,
                                top = 12.dp,
                                bottom = 12.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (active)
                            PaperIcon(
                                PaperGlyph.Play,
                                modifier = Modifier.size(16.dp),
                                color = Paper.Accent,
                            )
                        Text(
                            "${index + 1}",
                            Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            label,
                            Modifier.weight(1f),
                            maxLines = 2,
                            style = MaterialTheme.typography.bodyMedium,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        if (active)
                            PaperIcon(
                                PaperGlyph.Check,
                                modifier = Modifier.size(16.dp),
                                color = Paper.Accent,
                            )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (chapters.size <= 8) {
                chapters.indices.forEach { chapterRow(it) }
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = available), state = list) {
                    items(chapters.size) { chapterRow(it) }
                }
            }
        }
}
