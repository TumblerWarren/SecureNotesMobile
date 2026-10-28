package app.securenote.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.securenote.vault.core.EpubArchive
import java.io.Closeable
import java.net.URLDecoder
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Publication parsing runs only in EpubService's isolated process, without vault keys. */
internal class EpubBook(private val archive: EpubArchive, private val active: () -> Unit) :
    Closeable {
    data class Content(val title: String, val chapter: Int, val anchor: String, val depth: Int)

    data class Chapter(val path: String, var title: String, val linear: Boolean)

    data class Page(
        val html: String,
        val images: Map<String, Bitmap>,
        val chapter: Int,
        val section: Int,
        val sections: Int,
        val previous: Boolean,
        val next: Boolean,
        val contentIndex: Int,
    ) : Closeable {
        override fun close() {
            images.values.forEach { it.recycle() }
        }
    }

    private data class Resource(val path: String, val mime: String, val properties: String)

    private data class Parsed(val pages: List<String>, val anchors: Map<String, Int>)

    val title: String
    val author: String
    val chapters: List<Chapter>
    val contents: List<Content>
    private var cachedIndex = -1
    private var cached: Parsed? = null
    var chapter = 0
        private set

    var section = 0
        private set

    init {
        val container = xml("META-INF/container.xml", 256 * 1024)
        val packageName =
            container
                .getAllElements()
                .firstOrNull {
                    it.local() == "rootfile" &&
                        it.attr("media-type") == "application/oebps-package+xml"
                }
                ?.attr("full-path")
                ?: container
                    .getAllElements()
                    .firstOrNull { it.local() == "rootfile" }
                    ?.attr("full-path")
                ?: error("This EPUB has no publication")
        val packagePath =
            EpubArchive.resolve("", packageName)?.first ?: error("Invalid EPUB publication path")
        val opf = xml(packagePath, 2 * 1024 * 1024)
        val elements = opf.getAllElements()
        require(elements.size <= 50000) { "This EPUB has too many resources" }
        require(
            elements.none {
                it.local() == "meta" &&
                    ((it.attr("property") == "rendition:layout" &&
                        it.text().trim() == "pre-paginated") ||
                        (it.attr("name") == "rendition:layout" &&
                            it.attr("content") == "pre-paginated") ||
                        (it.attr("name") == "fixed-layout" && it.attr("content") == "true"))
            }
        ) {
            "Fixed-layout EPUBs are not supported by this reader"
        }
        title =
            elements
                .firstOrNull { it.local() == "title" }
                ?.text()
                ?.take(255)
                .orEmpty()
                .ifBlank { "Untitled book" }
        author = elements.firstOrNull { it.local() == "creator" }?.text()?.take(255).orEmpty()
        val manifest = linkedMapOf<String, Resource>()
        for (e in elements.filter { it.local() == "item" }) {
            active()
            require(manifest.size < 10000) { "Too many EPUB resources" }
            val path =
                EpubArchive.resolve(packagePath, e.attr("href"))?.first
                    ?: error("Invalid EPUB resource path")
            val id = e.attr("id")
            require(id.isNotEmpty() && !manifest.containsKey(id))
            manifest[id] = Resource(path, e.attr("media-type"), e.attr("properties"))
        }
        chapters =
            elements
                .filter { it.local() == "itemref" }
                .mapIndexed { i, e ->
                    val resource = manifest[e.attr("idref")] ?: error("Missing EPUB chapter")
                    require(resource.mime in setOf("application/xhtml+xml", "text/html")) {
                        "This EPUB contains an unsupported chapter format"
                    }
                    require(!e.attr("properties").contains("rendition:layout-pre-paginated")) {
                        "Fixed-layout EPUBs are not supported by this reader"
                    }
                    require(archive.entries.containsKey(resource.path)) { "Missing EPUB chapter" }
                    Chapter(resource.path, "Chapter ${i + 1}", e.attr("linear") != "no")
                }
        require(chapters.size in 1..1024) { "This EPUB has an unsupported number of chapters" }
        if (archive.entries.containsKey("META-INF/encryption.xml")) {
            val encryption = xml("META-INF/encryption.xml", 256 * 1024)
            for (e in encryption.getAllElements().filter { it.local() == "encrypteddata" }) {
                val algorithm =
                    e.getAllElements()
                        .firstOrNull { it.local() == "encryptionmethod" }
                        ?.attr("Algorithm")
                val uri =
                    e.getAllElements()
                        .firstOrNull { it.local() == "cipherreference" }
                        ?.attr("URI")
                        .orEmpty()
                // Font obfuscation is not content DRM. Embedded fonts are intentionally not loaded.
                val path = EpubArchive.resolve("", uri)?.first
                val font = manifest.values.firstOrNull { it.path == path }?.mime.orEmpty()
                require(
                    algorithm in
                        setOf(
                            "http://www.idpf.org/2008/embedding",
                            "http://ns.adobe.com/pdf/enc#RC",
                        ) &&
                        (font.startsWith("font/") ||
                            font in
                                setOf(
                                    "application/vnd.ms-opentype",
                                    "application/font-sfnt",
                                    "application/font-woff",
                                    "application/x-font-ttf",
                                    "application/x-font-opentype",
                                ))
                ) {
                    "DRM-protected EPUBs are not supported"
                }
            }
        }
        val nav = manifest.values.firstOrNull { "nav" in it.properties.split(Regex("\\s+")) }
        val ncx = elements.firstOrNull { it.local() == "spine" }?.attr("toc")?.let(manifest::get)
        val tocEntries = mutableListOf<Content>()
        fun addContent(base: String, href: String, label: String, depth: Int) {
            val target = EpubArchive.resolve(base, href) ?: return
            val index = chapters.indexOfFirst { it.path == target.first }
            if (index < 0 || label.isBlank()) return
            require(tocEntries.size < 2048) { "Too many EPUB contents entries" }
            tocEntries.add(
                Content(label.trim().take(160), index, target.second, depth.coerceIn(0, 8))
            )
            setLabel(base, href, label)
        }
        if (nav != null) {
            val doc = xml(nav.path, 2 * 1024 * 1024)
            val toc =
                doc.getAllElements().firstOrNull { element ->
                    element.local() == "nav" &&
                        (element.attributes().any { attribute ->
                            attribute.key.substringAfter(':') == "type" &&
                                "toc" in attribute.value.split(Regex("\\s+"))
                        } || "doc-toc" in element.attr("role").split(Regex("\\s+")))
                }
            toc?.getAllElements()
                ?.filter { it.local() == "a" }
                ?.forEach {
                    addContent(
                        nav.path,
                        it.attr("href"),
                        it.text(),
                        (it.parents().count { parent -> parent.local() == "li" } - 1).coerceAtLeast(
                            0
                        ),
                    )
                }
        }
        // EPUB 2 NCX is also the fallback when a publication's EPUB 3 nav has no usable TOC.
        if (tocEntries.isEmpty() && ncx != null) {
            xml(ncx.path, 2 * 1024 * 1024)
                .getAllElements()
                .filter { it.local() == "navpoint" }
                .forEach { point ->
                    val src =
                        point
                            .children()
                            .firstOrNull { it.local() == "content" }
                            ?.attr("src")
                            .orEmpty()
                    val label =
                        point.children().firstOrNull { it.local() == "navlabel" }?.text().orEmpty()
                    addContent(
                        ncx.path,
                        src,
                        label,
                        point.parents().count { it.local() == "navpoint" },
                    )
                }
        }
        contents = tocEntries.ifEmpty {
            chapters.mapIndexedNotNull { index, entry ->
                if (entry.linear) Content(entry.title, index, "", 0) else null
            }
        }
    }

    private fun setLabel(base: String, href: String, label: String) {
        val path = EpubArchive.resolve(base, href)?.first ?: return
        val index = chapters.indexOfFirst { it.path == path }
        if (index >= 0 && chapters[index].title == "Chapter ${index + 1}" && label.isNotBlank())
            chapters[index].title = label.take(160)
    }

    private fun Element.local() = tagName().substringAfter(':').lowercase(java.util.Locale.ROOT)

    private fun text(path: String, limit: Int): String {
        val bytes = archive.read(path, limit)
        return try {
            val encoding =
                when {
                    bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ->
                        Charsets.UTF_16LE
                    bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
                        Charsets.UTF_16BE
                    bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() ->
                        Charsets.UTF_16BE
                    bytes.size >= 4 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() ->
                        Charsets.UTF_16LE
                    else -> Charsets.UTF_8
                }
            encoding
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
                .also {
                    require(!it.contains("<!ENTITY", ignoreCase = true)) {
                        "Custom XML entities are unsupported"
                    }
                }
        } finally {
            bytes.fill(0)
        }
    }

    private fun xml(path: String, limit: Int): Document {
        active()
        // jsoup's XML tokenizer does not fetch DTDs or resolve external XML entities.
        return Jsoup.parse(text(path, limit), "", Parser.xmlParser()).also(::checkMarkup)
    }

    private fun checkMarkup(doc: Document) {
        var count = 0
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    active()
                    require(++count <= 100000 && depth <= 128) { "EPUB markup is too complex" }
                }

                override fun tail(node: Node, depth: Int) = Unit
            },
            doc,
        )
        require(doc.getAllElements().size <= 50000) { "EPUB markup is too complex" }
    }

    private fun parsed(index: Int): Parsed {
        if (cachedIndex == index) return checkNotNull(cached)
        active()
        val path = chapters[index].path
        val doc = Jsoup.parse(text(path, 2 * 1024 * 1024))
        doc.outputSettings().prettyPrint(false)
        checkMarkup(doc)
        doc.select("script,style,link,iframe,object,embed,form,input,button,audio,video,base")
            .remove()
        doc.select("svg,math").forEach {
            it.replaceWith(
                Element("p").text(it.text().take(200).ifBlank { "Illustration unavailable" })
            )
        }
        doc.select("table").forEach { table ->
            val replacement = Element("div")
            table.select("tr").forEach { row ->
                replacement
                    .appendElement("p")
                    .text(row.select("th,td").joinToString(" · ") { it.text() })
            }
            table.replaceWith(replacement)
        }
        doc.select("a[name]").forEach {
            if (it.id().isBlank()) it.attr("id", it.attr("name").take(512))
        }
        doc.select("a[href]").forEach { a ->
            val target = EpubArchive.resolve(path, a.attr("href"))
            val targetIndex =
                target?.let { t -> chapters.indexOfFirst { it.path == t.first } } ?: -1
            if (targetIndex >= 0)
                a.attr(
                    "href",
                    "vault-epub:$targetIndex#${URLEncoder.encode(target!!.second, "UTF-8")}",
                )
            else a.removeAttr("href")
        }
        doc.select("img").forEach { image ->
            val target = EpubArchive.resolve(path, image.attr("src"))?.first
            if (target != null && archive.entries.containsKey(target)) image.attr("src", target)
            else
                image.replaceWith(
                    Element("p").text(image.attr("alt").ifBlank { "Image unavailable" })
                )
        }
        val allowed =
            Safelist()
                .addTags(
                    "p",
                    "br",
                    "h1",
                    "h2",
                    "h3",
                    "h4",
                    "h5",
                    "h6",
                    "b",
                    "strong",
                    "i",
                    "em",
                    "u",
                    "s",
                    "small",
                    "big",
                    "sub",
                    "sup",
                    "blockquote",
                    "ul",
                    "ol",
                    "li",
                    "pre",
                    "code",
                    "a",
                    "img",
                    "span",
                    "div",
                    "section",
                    "article",
                    "main",
                    "hr",
                )
                .addAttributes(":all", "id")
                .addAttributes("a", "href")
                .addAttributes("img", "src", "alt")
        val clean = Cleaner(allowed).clean(doc)
        clean.outputSettings().prettyPrint(false)
        val pages = mutableListOf<String>()
        val anchors = mutableMapOf<String, Int>()
        val pending = mutableListOf<String>()
        var html = StringBuilder()
        var weight = 0
        var images = 0
        fun flush() {
            if (html.isNotEmpty()) {
                require(pages.size < 2048) { "Too many sections in this EPUB chapter" }
                pages.add(html.toString())
                html = StringBuilder()
                weight = 0
                images = 0
            }
        }
        val tocAnchors =
            contents
                .filter { it.chapter == index && it.anchor.isNotEmpty() }
                .map { it.anchor }
                .toSet()
        fun append(fragment: String, plain: Int, imageCount: Int, ids: List<String>) {
            active()
            // A TOC target starts a section, so a subchapter opens at its actual heading.
            if (html.isNotEmpty() && (pending + ids).any { it in tocAnchors }) flush()
            if (
                html.isNotEmpty() &&
                    (weight + plain > 12000 ||
                        html.length + fragment.length > 48000 ||
                        images + imageCount > 4)
            )
                flush()
            require(fragment.length <= 48000) { "EPUB section is too complex" }
            (pending + ids).forEach { if (it.length <= 512) anchors.putIfAbsent(it, pages.size) }
            pending.clear()
            html.append(fragment)
            weight += plain
            images += imageCount
        }
        fun visit(node: Node, depth: Int) {
            require(depth <= 128) { "EPUB nesting is too deep" }
            if (node is TextNode) {
                val text = node.text()
                if (text.isNotBlank()) {
                    var start = 0
                    while (start < text.length) {
                        var end = minOf(start + 6000, text.length)
                        if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                        append(
                            "<p>${org.jsoup.nodes.Entities.escape(text.substring(start,end))}</p>",
                            end - start,
                            0,
                            emptyList(),
                        )
                        start = end
                    }
                }
                return
            }
            val e = node as? Element ?: return
            if (e.local() in setOf("div", "section", "article", "main", "body")) {
                if (e.id().isNotEmpty()) pending.add(e.id())
                e.childNodes().forEach { visit(it, depth + 1) }
                return
            }
            val fragment = e.outerHtml()
            val count = e.getAllElements().count { it.local() == "img" }
            val ids = e.getAllElements().map { it.id() }.filter { it.isNotEmpty() }
            if (fragment.length > 48000 || e.text().length > 12000 || count > 4) {
                val value = e.text()
                var start = 0
                while (start < value.length) {
                    var end = minOf(start + 6000, value.length)
                    if (end < value.length && Character.isHighSurrogate(value[end - 1])) end--
                    append(
                        "<p>${org.jsoup.nodes.Entities.escape(value.substring(start, end))}</p>",
                        end - start,
                        0,
                        if (start == 0) ids else emptyList(),
                    )
                    start = end
                }
                e.getAllElements()
                    .filter { it.local() == "img" }
                    .forEach { append(it.outerHtml(), 0, 1, emptyList()) }
            } else append(fragment, e.text().length, count, ids)
        }
        clean.body().childNodes().forEach { visit(it, 0) }
        flush()
        if (pages.isEmpty()) pages.add("<p>This chapter has no readable text.</p>")
        require(pages.size <= 2048) { "Too many sections in this EPUB chapter" }
        return Parsed(pages, anchors).also {
            cachedIndex = index
            cached = it
        }
    }

    fun go(index: Int, part: Int = 0, anchor: String? = null): Page {
        active()
        require(index in chapters.indices)
        val parsed = parsed(index)
        chapter = index
        section = (anchor?.let(parsed.anchors::get) ?: part).coerceIn(0, parsed.pages.lastIndex)
        val doc = Jsoup.parseBodyFragment(parsed.pages[section])
        doc.outputSettings().prettyPrint(false)
        val bitmaps = linkedMapOf<String, Bitmap>()
        var imageBytes = 0L
        try {
            doc.select("img").forEach { image ->
                active()
                val raw = archive.read(image.attr("src"), 12 * 1024 * 1024)
                val bitmap =
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                        if (
                            bounds.outWidth <= 0 ||
                                bounds.outHeight <= 0 ||
                                bounds.outWidth.toLong() * bounds.outHeight > 64000000L
                        )
                            null
                        else {
                            var sample = 1
                            while (
                                bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1600
                            ) sample *= 2
                            BitmapFactory.decodeByteArray(
                                raw,
                                0,
                                raw.size,
                                BitmapFactory.Options().apply {
                                    inSampleSize = sample
                                    inMutable = true
                                },
                            )
                        }
                    } finally {
                        raw.fill(0)
                    }
                if (bitmap == null)
                    image.replaceWith(
                        Element("p").text(image.attr("alt").ifBlank { "Image unavailable" })
                    )
                else {
                    imageBytes += bitmap.allocationByteCount
                    if (bitmaps.size >= 4 || imageBytes > 12L * 1024 * 1024) {
                        bitmap.recycle()
                        error("EPUB image memory limit exceeded")
                    }
                    val key = "image:${bitmaps.size}"
                    bitmaps[key] = bitmap
                    image.attr("src", key)
                }
            }
            active()
            return Page(
                doc.body().html(),
                bitmaps,
                chapter,
                section,
                parsed.pages.size,
                section > 0 || chapters.indices.any { it < chapter && chapters[it].linear },
                section < parsed.pages.lastIndex ||
                    chapters.indices.any { it > chapter && chapters[it].linear },
                contents.indices
                    .filter { contents[it].chapter == chapter }
                    .filter { (parsed.anchors[contents[it].anchor] ?: 0) <= section }
                    .maxWithOrNull(
                        compareBy<Int> { parsed.anchors[contents[it].anchor] ?: 0 }.thenBy { it }
                    ) ?: -1,
            )
        } catch (e: Exception) {
            bitmaps.values.forEach { it.recycle() }
            throw e
        }
    }

    fun move(direction: Int): Page {
        require(direction == -1 || direction == 1)
        val parts = parsed(chapter).pages
        if (section + direction in parts.indices) return go(chapter, section + direction)
        val next =
            if (direction > 0) chapters.indices.firstOrNull { it > chapter && chapters[it].linear }
            else chapters.indices.lastOrNull { it < chapter && chapters[it].linear }
        return if (next == null) go(chapter, section)
        else go(next, if (direction > 0) 0 else Int.MAX_VALUE)
    }

    fun follow(link: String): Page {
        require(link.startsWith("vault-epub:") && link.length <= 4096)
        val index = link.substringAfter(':').substringBefore('#').toInt()
        val anchor = URLDecoder.decode(link.substringAfter('#', ""), "UTF-8")
        return go(index, anchor = anchor)
    }

    override fun close() {
        cached = null
        cachedIndex = -1
        archive.close()
    }
}
