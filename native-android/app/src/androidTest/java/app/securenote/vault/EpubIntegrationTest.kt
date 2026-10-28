package app.securenote.vault

import android.graphics.Bitmap
import android.graphics.Color
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class EpubIntegrationTest {
    private fun fixture(legacy: Boolean = false): ByteArray {
        val image =
            ByteArrayOutputStream().use { out ->
                val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.BLUE)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                } finally {
                    bitmap.recycle()
                }
            }
        val files =
            linkedMapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to
                    """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
                        .toByteArray(),
                "OPS/book.opf" to
                    """<package xmlns="http://www.idpf.org/2007/opf" version="3.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Synthetic private book</dc:title><dc:creator>Test author</dc:creator></metadata><manifest><item id="one" href="Text/one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="Text/two.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" properties="nav" media-type="application/xhtml+xml"/><item id="image" href="Images/pixel.png" media-type="image/png"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>"""
                        .toByteArray(),
                "OPS/nav.xhtml" to
                    """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="Text/one.xhtml">Morning</a><ol><li><a href="Text/one.xhtml#dawn">Dawn</a></li></ol></li><li><a href="Text/two.xhtml#second">Evening</a></li></ol></nav></body></html>"""
                        .toByteArray(),
                "OPS/Text/one.xhtml" to
                    """<html><body><h1>Morning</h1><p>First chapter body.</p><img src="../Images/pixel.png"/><a href="two.xhtml#second">Continue reading</a><a href="https://example.com">External link</a><script>PRIVATE_SCRIPT_MARKER</script><h2 id="dawn">Dawn</h2><p>Nested subchapter body.</p></body></html>"""
                        .toByteArray(),
                "OPS/Text/two.xhtml" to
                    """<html><body><h1 id="second">Evening</h1><p>Second chapter body.</p></body></html>"""
                        .toByteArray(),
                "OPS/Images/pixel.png" to image,
            )
        if (legacy) {
            files["OPS/book.opf"] =
                files
                    .getValue("OPS/book.opf")
                    .toString(Charsets.UTF_8)
                    .replace("properties=\"nav\"", "properties=\"\"")
                    .replace(
                        "</manifest>",
                        "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/></manifest>",
                    )
                    .replace("<spine>", "<spine toc=\"ncx\">")
                    .toByteArray()
            files["OPS/toc.ncx"] =
                """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint id="morning"><navLabel><text>Morning</text></navLabel><content src="Text/one.xhtml"/><navPoint id="dawn"><navLabel><text>Dawn</text></navLabel><content src="Text/one.xhtml#dawn"/></navPoint></navPoint><navPoint id="evening"><navLabel><text>Evening</text></navLabel><content src="Text/two.xhtml#second"/></navPoint></navMap></ncx>"""
                    .toByteArray()
        }
        return ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                files.forEach { (name, bytes) ->
                    val entry = ZipEntry(name)
                    if (name == "mimetype") {
                        entry.method = ZipEntry.STORED
                        entry.size = bytes.size.toLong()
                        entry.crc = CRC32().apply { update(bytes) }.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            out.toByteArray()
        }
    }

    @Test
    fun encryptedEpubContentsImagesLinksPositionAndLock() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "test-epub-${UUID.randomUUID()}"
        val store = VaultStore(context, directory)
        try {
            store.create("Synthetic EPUB test password".toByteArray()).fill(0)
            val bytes = fixture()
            val id =
                try {
                    store.importBytes("Private book.epub", "application/epub+zip", bytes)
                } finally {
                    bytes.fill(0)
                }
            EpubClient(context).use { client ->
                val first = client.open(store, id, EpubPosition())
                assertEquals("Synthetic private book", client.catalog?.title)
                assertEquals(listOf("Morning", "Evening"), client.catalog?.chapters)
                assertEquals(
                    listOf("Morning", "Dawn", "Evening"),
                    client.catalog?.contents?.map { it.title },
                )
                assertEquals(listOf(0, 1, 0), client.catalog?.contents?.map { it.depth })
                assertTrue(first.html.contains("First chapter body"))
                assertFalse(first.html.contains("PRIVATE_SCRIPT_MARKER"))
                assertEquals(Color.BLUE, first.images.values.single().getPixel(0, 0))
                val links = Jsoup.parseBodyFragment(first.html).select("a[href]")
                assertEquals(1, links.size)
                assertTrue(links.single().attr("href").startsWith("vault-epub:"))
                val link = links.single().attr("href")
                first.close()
                val dawn = client.follow(checkNotNull(client.catalog).contents[1].link)
                assertEquals(0, dawn.chapter)
                assertTrue(dawn.html.contains("Nested subchapter body"))
                assertFalse(dawn.html.contains("First chapter body"))
                dawn.close()
                val second = client.follow(link)
                assertEquals(1, second.chapter)
                assertTrue(second.html.contains("Second chapter body"))
                store.dao.saveReadingState(
                    id,
                    EpubPosition(second.chapter, second.section, 22, 123).encode(),
                )
                second.close()
                // Cached service HTML must not bypass the host's password-session authority.
                store.revoke()
                var rejected = false
                try {
                    client.move(-1)
                } catch (e: Exception) {
                    rejected = true
                }
                assertTrue(rejected)
            }
            store.close()
            store.unlock("Synthetic EPUB test password".toByteArray())
            EpubClient(context).use { client ->
                val position = EpubPosition.decode(store.dao.readingState(id))
                assertEquals(22, position.fontSize)
                assertEquals(123, position.scroll)
                val resumed = client.open(store, id, position)
                assertEquals(1, resumed.chapter)
                assertTrue(resumed.html.contains("Second chapter body"))
                resumed.close()
            }
            store.close()
            val index =
                File(context.noBackupFilesDir, "$directory/index.db")
                    .readBytes()
                    .toString(Charsets.ISO_8859_1)
            assertFalse(index.contains("epub-v1"))
            assertTrue(
                File(context.noBackupFilesDir, directory)
                    .walk()
                    .filter { it.isFile }
                    .none { it.extension in setOf("epub", "xhtml", "html", "png") }
            )
        } finally {
            store.close()
            File(context.noBackupFilesDir, directory).deleteRecursively()
        }
    }

    @Test
    fun legacyNcxPreservesNestedTargetsAndOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = "test-epub-ncx-${UUID.randomUUID()}"
        val store = VaultStore(context, directory)
        try {
            store.create("Synthetic NCX test password".toByteArray()).fill(0)
            val bytes = fixture(legacy = true)
            val id =
                try {
                    store.importBytes("Legacy.epub", "application/epub+zip", bytes)
                } finally {
                    bytes.fill(0)
                }
            EpubClient(context).use { client ->
                client.open(store, id, EpubPosition()).close()
                val entries = checkNotNull(client.catalog).contents
                assertEquals(listOf("Morning", "Dawn", "Evening"), entries.map { it.title })
                assertEquals(listOf(0, 0, 1), entries.map { it.chapter })
                assertEquals(listOf(0, 1, 0), entries.map { it.depth })
                val nested = client.follow(entries[1].link)
                assertTrue(nested.html.contains("Nested subchapter body"))
                assertFalse(nested.html.contains("First chapter body"))
                nested.close()
            }
        } finally {
            store.close()
            File(context.noBackupFilesDir, directory).deleteRecursively()
        }
    }

    @Test
    fun nativeReaderKeepsLinksInternalAndDisablesTextActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = EpubTextView(instrumentation.targetContext)
            val frame =
                EpubFrame(
                    "<p><a href=\"vault-epub:1#second\">Continue</a> <a href=\"https://example.com\">External</a></p>",
                    mutableMapOf(),
                    0,
                    0,
                    1,
                    false,
                    true,
                )
            try {
                var followed: String? = null
                view.display(frame, 18) { followed = it }
                val text = view.text as Spanned
                assertEquals(0, text.getSpans(0, text.length, URLSpan::class.java).size)
                val links = text.getSpans(0, text.length, ClickableSpan::class.java)
                assertEquals(1, links.size)
                links.single().onClick(view)
                assertEquals("vault-epub:1#second", followed)
                assertFalse(view.isTextSelectable)
                assertFalse(view.isLongClickable)
                assertFalse(view.isSaveEnabled)
                assertFalse(view.onTextContextMenuItem(android.R.id.copy))
                view.clearContent()
                assertEquals("", view.text.toString())
            } finally {
                view.clearContent()
                frame.close()
            }
        }
    }
}
