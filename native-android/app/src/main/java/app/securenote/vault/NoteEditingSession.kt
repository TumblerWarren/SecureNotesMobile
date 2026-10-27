package app.securenote.vault

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.*
import android.text.style.*

/** Local drafts outlive recycled block views, but never outlive the unlocked editor. */
internal class NoteEditingSession {
    private val rich = mutableMapOf<String, SpannedString>()
    private val encoded = mutableMapOf<String, String>()
    private val views = mutableSetOf<NoteEditText>()
    private val tables = mutableMapOf<String, NoteTable>()

    fun table(block: NoteBlock): NoteTable =
        tables.getOrPut(block.id) { NoteTable.decode(content(block)) }

    fun edited(id: String, table: NoteTable) {
        if (!closed) tables[id] = table
    }

    private val objects = mutableMapOf<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> draft(id: String, create: () -> T): T = objects.getOrPut(id, create) as T

    var closed = false
        private set

    fun text(block: NoteBlock): SpannedString =
        rich.getOrPut(block.id) {
            val decoded =
                SpannableStringBuilder(Html.fromHtml(block.content, Html.FROM_HTML_MODE_LEGACY))
            // Normalize imported Android list spans to the editable bullet representation.
            val bullets =
                decoded
                    .getSpans(0, decoded.length, BulletSpan::class.java)
                    .map { it to decoded.getSpanStart(it) }
                    .sortedByDescending { it.second }
            bullets.forEach { (span, at) ->
                decoded.removeSpan(span)
                if (at >= 0 && !decoded.substring(at).startsWith("• ")) decoded.insert(at, "• ")
            }
            // Android's HTML writer adds paragraph terminators; do not grow them on every reopen.
            val end = if (decoded.endsWith("\n\n")) decoded.length - 2 else decoded.length
            snapshot(decoded.subSequence(0, end))
        }

    fun content(block: NoteBlock): String = encoded[block.id] ?: block.content

    fun edited(id: String, value: SpannedString) {
        if (!closed) rich[id] = value
    }

    fun edited(id: String, value: String) {
        if (!closed) encoded[id] = value
    }

    fun attach(view: NoteEditText) {
        check(!closed)
        views.add(view)
    }

    fun release(view: NoteEditText) {
        views.remove(view)
        view.dispose()
    }

    fun contains(view: NoteEditText) = !closed && view in views

    fun retainBlocks(ids: Set<String>) {
        rich.keys.retainAll(ids)
        encoded.keys.retainAll(ids)
        tables.keys.retainAll(ids)
        objects.keys.removeAll { it.removePrefix("drawing/") !in ids }
    }

    fun close() {
        if (closed) return
        closed = true
        views.toList().forEach { it.dispose() }
        views.clear()
        rich.clear()
        encoded.clear()
        tables.clear()
        objects.clear()
    }

    companion object {
        fun safeLink(value: String): Boolean = runCatching {
            val uri = Uri.parse(value)
            value.length in 1..4096 &&
                uri.scheme?.lowercase() in setOf("https", "http") &&
                !uri.host.isNullOrBlank() &&
                value.none { it.isISOControl() }
        }
            .getOrDefault(false)

        /** Copy approved spans only: never retain composing markers, watchers or editor objects. */
        fun snapshot(value: CharSequence): SpannedString {
            val clean = SpannableString(value.toString())
            if (value is Spanned)
                value.getSpans(0, value.length, Any::class.java).forEach { span ->
                    val start = value.getSpanStart(span)
                    val end = value.getSpanEnd(span)
                    if (start < 0 || end <= start || end > value.length) return@forEach
                    val copy: Any =
                        when (span) {
                            is StyleSpan -> StyleSpan(span.style)
                            is UnderlineSpan -> UnderlineSpan()
                            is StrikethroughSpan -> StrikethroughSpan()
                            is SuperscriptSpan -> SuperscriptSpan()
                            is SubscriptSpan -> SubscriptSpan()
                            is ForegroundColorSpan -> ForegroundColorSpan(span.foregroundColor)
                            is BackgroundColorSpan -> BackgroundColorSpan(span.backgroundColor)
                            is BulletSpan -> BulletSpan()
                            is QuoteSpan -> QuoteSpan()
                            is URLSpan ->
                                if (safeLink(span.url)) URLSpan(span.url) else return@forEach
                            else -> return@forEach
                        }
                    // Paragraph spans must retain paragraph flags; all other snapshots are inert.
                    val flags =
                        if (span is android.text.style.ParagraphStyle)
                            value.getSpanFlags(span) and Spanned.SPAN_POINT_MARK_MASK
                        else Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    clean.setSpan(copy, start, end, flags)
                }
            return SpannedString(clean)
        }
    }
}

/** One native Editable per visible block. Toolbar commands mutate spans without setText(). */
internal enum class FormatState {
    Off,
    On,
    Mixed,
}

internal class NoteEditText(context: Context) : SecureEditText(context) {
    var onEdit: ((SpannedString) -> Unit)? = null
    var onActive: ((NoteEditText) -> Unit)? = null
    var onSelection: (() -> Unit)? = null

    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        onSelection?.invoke()
    }

    fun formatState(kind: String): FormatState {
        val value = text ?: return FormatState.Off
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, value.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(start, value.length)
        if (kind == "list") {
            val first = value.toString().lastIndexOf('\n', start - 1) + 1
            val starts =
                listOf(first) +
                    (first until end).filter { value[it] == '\n' && it + 1 < end }.map { it + 1 }
            val n = starts.count { value.substring(it).startsWith("• ") }
            return if (n == 0) FormatState.Off
            else if (n == starts.size) FormatState.On else FormatState.Mixed
        }
        val spans =
            value.getSpans(0, value.length, Any::class.java).filter {
                when (kind) {
                    "bold" -> it is StyleSpan && it.style and Typeface.BOLD != 0
                    "italic" -> it is StyleSpan && it.style and Typeface.ITALIC != 0
                    else -> it is UnderlineSpan
                }
            }
        val ranges =
            spans
                .map { value.getSpanStart(it) to value.getSpanEnd(it) }
                .filter { (a, b) ->
                    if (start == end) a <= start && b >= start else a < end && b > start
                }
                .sortedBy { it.first }
        if (ranges.isEmpty()) return FormatState.Off
        if (start == end) return FormatState.On
        var covered = start
        for ((a, b) in ranges) if (a <= covered) covered = maxOf(covered, b)
        return if (covered >= end) FormatState.On else FormatState.Mixed
    }

    private var silent = false
    private var disposed = false
    private var newline = -1
    var revision = 0L
        private set

    private val watcher =
        object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                newline =
                    if (!silent && before == 0 && count == 1 && s?.get(start) == '\n') start else -1
            }

            override fun afterTextChanged(s: Editable?) {
                if (silent || disposed || s == null) return
                val entered = newline
                newline = -1
                if (entered >= 0) {
                    val lineStart = s.toString().lastIndexOf('\n', entered - 1) + 1
                    if (s.subSequence(lineStart, entered).startsWith("• ")) {
                        silent = true
                        try {
                            if (entered - lineStart == 2) s.delete(lineStart, entered + 1)
                            else {
                                s.insert(entered + 1, "• ")
                                setSelection(entered + 3)
                            }
                        } finally {
                            silent = false
                        }
                    }
                }
                emit()
            }
        }

    init {
        restrictExternalActions()
        linksClickable = false
        autoLinkMask = 0
        addTextChangedListener(watcher)
        setOnFocusChangeListener { _, focused -> if (focused && !disposed) onActive?.invoke(this) }
    }

    fun load(value: SpannedString) {
        check(!disposed)
        silent = true
        try {
            setText(value, BufferType.EDITABLE)
        } finally {
            silent = false
        }
    }

    private fun emit() {
        if (disposed) return
        revision++
        onSelection?.invoke()
        onEdit?.invoke(NoteEditingSession.snapshot(text))
    }

    private fun edit(action: (Editable) -> Unit) {
        if (disposed) return
        silent = true
        beginBatchEdit()
        try {
            action(text)
        } finally {
            endBatchEdit()
            silent = false
        }
        emit()
    }

    fun toggle(kind: String) {
        if (disposed) return
        val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(start)
        edit { value ->
            val bit =
                when (kind) {
                    "bold" -> Typeface.BOLD
                    "italic" -> Typeface.ITALIC
                    else -> 0
                }
            fun matches(span: Any) =
                if (bit != 0) span is StyleSpan && span.style and bit != 0
                else span is UnderlineSpan
            val spans = value.getSpans(0, value.length, Any::class.java).filter(::matches)
            val ranges =
                spans.map { value.getSpanStart(it) to value.getSpanEnd(it) }.sortedBy { it.first }
            var covered = start
            for ((a, b) in ranges) if (a <= covered && b > covered) covered = b
            val remove =
                if (start == end) ranges.any { (a, b) -> a <= start && b >= start }
                else covered >= end
            fun span(): Any = if (bit != 0) StyleSpan(bit) else UnderlineSpan()
            for (old in spans) {
                val a = value.getSpanStart(old)
                val b = value.getSpanEnd(old)
                if (b < start || a > end) continue
                value.removeSpan(old)
                if (old is StyleSpan && old.style and bit.inv() != 0)
                    value.setSpan(
                        StyleSpan(old.style and bit.inv()),
                        a,
                        b,
                        Spanned.SPAN_EXCLUSIVE_INCLUSIVE,
                    )
                if (a < start) value.setSpan(span(), a, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (b > end) value.setSpan(span(), end, b, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
            }
            if (!remove)
                value.setSpan(
                    span(),
                    start,
                    end,
                    if (start == end) Spanned.SPAN_INCLUSIVE_INCLUSIVE
                    else Spanned.SPAN_EXCLUSIVE_INCLUSIVE,
                )
        }
    }

    fun bullets() {
        if (disposed) return
        edit { value ->
            val selection = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
            val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(selection)
            val first = value.toString().lastIndexOf('\n', selection - 1) + 1
            val last = if (end > selection && value[end - 1] == '\n') end - 1 else end
            val starts = mutableListOf(first)
            for (i in first until last) if (value[i] == '\n') starts.add(i + 1)
            val remove = starts.all { value.substring(it).startsWith("• ") }
            for (at in starts.asReversed()) {
                if (remove) value.delete(at, at + 2)
                else if (!value.substring(at).startsWith("• ")) value.insert(at, "• ")
            }
        }
    }

    fun link(url: String, start: Int, end: Int, expectedRevision: Long): Boolean {
        if (
            disposed ||
                revision != expectedRevision ||
                !NoteEditingSession.safeLink(url) ||
                start !in 0..length() ||
                end !in start..length()
        )
            return false
        edit { value ->
            val finish =
                if (start == end) {
                    value.insert(start, url)
                    start + url.length
                } else end
            for (span in value.getSpans(start, finish, URLSpan::class.java)) value.removeSpan(span)
            value.setSpan(URLSpan(url), start, finish, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSelection(finish)
        }
        return true
    }

    override fun onTextContextMenuItem(id: Int): Boolean =
        if (disposed) false
        else
            super.onTextContextMenuItem(
                if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id
            )

    fun dispose() {
        if (disposed) return
        disposed = true
        onEdit = null
        onActive = null
        onSelection = null
        removeTextChangedListener(watcher)
        setOnFocusChangeListener(null)
        clearFocus()
        text.clear()
        isEnabled = false
    }
}
