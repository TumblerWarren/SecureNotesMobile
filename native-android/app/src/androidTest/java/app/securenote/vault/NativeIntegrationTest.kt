package app.securenote.vault

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeIntegrationTest {
    @Test
    fun encryptedNativeReadersAndRevocation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-readers-${UUID.randomUUID()}"
        val store = VaultStore(context, name)
        try {
            store.create("Synthetic native reader password".toByteArray()).fill(0)
            val png =
                ByteArrayOutputStream().use { out ->
                    val image = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
                    try {
                        image.eraseColor(Color.BLUE)
                        image.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    } finally {
                        image.recycle()
                    }
                }
            val imageId = store.importBytes("Synthetic image", "image/png", png)
            val (item, reader) = store.reader(imageId)
            proxyDescriptor(context, reader, item.size).use { fd ->
                val decoded = checkNotNull(BitmapFactory.decodeFileDescriptor(fd.fileDescriptor))
                try {
                    assertEquals(16, decoded.width)
                    assertEquals(24, decoded.height)
                    assertEquals(Color.BLUE, decoded.getPixel(0, 0))
                } finally {
                    decoded.recycle()
                }
                // A native consumer retains its descriptor after the password session ends.
                store.revoke()
                var rejected = false
                try {
                    Os.pread(fd.fileDescriptor, ByteArray(8), 0, 8, 0)
                } catch (e: Exception) {
                    rejected = true
                }
                assertTrue("Locked descriptors must reject further reads", rejected)
            }
            store.close()
            store.unlock("Synthetic native reader password".toByteArray())
            val pdf =
                ByteArrayOutputStream().use { out ->
                    val doc = PdfDocument()
                    try {
                        val page = doc.startPage(PdfDocument.PageInfo.Builder(200, 300, 1).create())
                        page.canvas.drawColor(Color.WHITE)
                        page.canvas.drawText(
                            "Synthetic private PDF",
                            10f,
                            30f,
                            Paint().apply {
                                color = Color.BLACK
                                textSize = 14f
                            },
                        )
                        page.canvas.drawRect(
                            120f,
                            160f,
                            160f,
                            200f,
                            Paint().apply { color = Color.RED },
                        )
                        doc.finishPage(page)
                        doc.writeTo(out)
                    } finally {
                        doc.close()
                    }
                    out.toByteArray()
                }
            val pdfId = store.importBytes("Synthetic PDF", "application/pdf", pdf)
            PdfClient(context).use { client ->
                assertEquals(1, client.open(store, pdfId))
                val page = client.render(0, 256)
                try {
                    assertEquals(256, page.width)
                    assertEquals(384, page.height)
                } finally {
                    page.recycle()
                }
                val tile = client.render(0, 1024, 512, 512)
                try {
                    assertEquals(512, tile.width)
                    assertEquals(512, tile.height)
                    assertEquals(Color.RED, tile.getPixel(128, 384))
                } finally {
                    tile.recycle()
                }
                store.revoke()
                var rejected = false
                try {
                    client.render(0, 256)
                } catch (e: Exception) {
                    rejected = true
                }
                assertTrue("Cached PDF rendering must require the original vault session", rejected)
            }
            png.fill(0)
            pdf.fill(0)
        } finally {
            store.close()
            File(context.noBackupFilesDir, name).deleteRecursively()
        }
    }

    @Test
    fun isolatedLegacyImportPreservesNestedContentAndOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-import-${UUID.randomUUID()}"
        val source = File(context.cacheDir, "synthetic-source-${UUID.randomUUID()}.db")
        val encrypted = File(context.cacheDir, "synthetic-source-${UUID.randomUUID()}.enc")
        val password = "Synthetic legacy fixture password"
        val store = VaultStore(context, name)
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(source, null).use { db ->
                db.execSQL(
                    "CREATE TABLE notes(id TEXT,title TEXT,content TEXT,updatedAt INTEGER,isPinned INTEGER,type TEXT)"
                )
                db.execSQL(
                    "INSERT INTO notes VALUES(?,?,?,?,?,?)",
                    arrayOf<Any>(
                        "old",
                        "Imported café 😀",
                        "Before<div><p><b>Private fixture</b></p><table><tr><td>Nested cell</td></tr></table><p>After</p></div>",
                        123L,
                        1,
                        "text",
                    ),
                )
            }
            val plain = source.readBytes()
            val salt = ByteArray(16) { it.toByte() }
            val iv = ByteArray(12) { (it + 16).toByte() }
            val spec = PBEKeySpec(password.toCharArray(), salt, 100000, 256)
            val key =
                try {
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec)
                        .encoded
                } finally {
                    spec.clearPassword()
                }
            try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    IvParameterSpec(iv.copyOf(16)),
                )
                encrypted.writeBytes(salt + iv + cipher.doFinal(plain))
            } finally {
                key.fill(0)
                plain.fill(0)
            }
            val original = encrypted.readBytes()
            store.create("Synthetic destination password".toByteArray()).fill(0)
            assertEquals(
                1,
                LegacyImporter(context, store).import(
                    Uri.fromFile(encrypted),
                    password.toByteArray(),
                ) {},
            )
            val notes = store.dao.firstPage()
            assertEquals(1, notes.size)
            assertEquals("Imported café 😀", notes.single().name)
            assertTrue(notes.single().pinned)
            val blocks = store.dao.blocks(notes.single().id)
            assertTrue(blocks.any { it.kind == "table" && it.content.contains("Nested cell") })
            assertTrue(blocks.any { it.kind == "text" && it.searchText.contains("Before") })
            assertTrue(blocks.any { it.kind == "text" && it.searchText.contains("After") })
            assertEquals(1L, store.dao.searchCount("Private"))
            assertArrayEquals(original, encrypted.readBytes())
        } finally {
            store.close()
            File(context.noBackupFilesDir, name).deleteRecursively()
            listOf(
                    source,
                    File(source.path + "-journal"),
                    File(source.path + "-wal"),
                    File(source.path + "-shm"),
                    encrypted,
                )
                .forEach { it.delete() }
        }
    }
}
