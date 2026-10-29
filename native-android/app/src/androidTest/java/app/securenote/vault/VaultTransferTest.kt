package app.securenote.vault

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VaultTransferTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val password = "Synthetic transfer password 123"

    private fun directory() = "test-transfer-${UUID.randomUUID()}"

    private fun failed(block: suspend () -> Unit) = runBlocking {
        var rejected = false
        try {
            block()
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("Operation must be rejected", rejected)
    }

    @Test
    fun approvedVerifiedDeletionRemovesOnlyTheSyntheticSource() = runBlocking {
        val name = directory()
        val store = VaultStore(context, name)
        val uri =
            android.provider.DocumentsContract.buildDocumentUri(
                "app.securenote.synthetictransfers",
                UUID.randomUUID().toString(),
            )
        try {
            store.create(password.toByteArray())
            val id = store.import(uri) {}
            val transfer = VaultTransfers(context, store)
            val receipt = transfer.verify(uri, id)
            // Verification alone must not delete anything.
            context.contentResolver.openInputStream(uri)!!.use { assertTrue(it.read() >= 0) }
            assertTrue(transfer.deleteOriginal(receipt))
            failed { context.contentResolver.openInputStream(uri)!!.close() }
            val output = ByteArrayOutputStream()
            transfer.copyFile(id, output)
            assertTrue(output.toString("UTF-8").startsWith("Synthetic private transfer fixture "))
        } finally {
            store.close()
            java.io.File(context.noBackupFilesDir, name).deleteRecursively()
        }
    }

    @Test
    fun changedOriginalAndDeniedDeletionLeaveTheVaultCopyIntact() = runBlocking {
        val name = directory()
        val store = VaultStore(context, name)
        val original = java.io.File(context.cacheDir, "test-original-${UUID.randomUUID()}")
        try {
            store.create(password.toByteArray())
            original.writeText("Original private bytes")
            val id = store.importBytes("original.txt", "text/plain", original.readBytes())
            val transfers = VaultTransfers(context, store)
            val receipt = transfers.verify(android.net.Uri.fromFile(original), id)
            original.writeText("Changed outside the vault")
            failed { transfers.deleteOriginal(receipt) }
            assertEquals("Changed outside the vault", original.readText())
            original.writeText("Original private bytes")
            failed {
                transfers.deleteOriginal(receipt)
            } // file:// sources deliberately cannot be deleted.
            val exported = ByteArrayOutputStream()
            transfers.copyFile(id, exported)
            assertEquals("Original private bytes", exported.toString("UTF-8"))
            assertTrue(original.exists())
        } finally {
            store.close()
            original.delete()
            java.io.File(context.noBackupFilesDir, name).deleteRecursively()
        }
    }

    @Test
    fun authenticatedBackupRestoresMediaNotesAndRecoveryButRejectsTampering() = runBlocking {
        val sourceName = directory()
        val restoreName = directory()
        val recoveryName = directory()
        val badName = directory()
        val source = VaultStore(context, sourceName)
        val restored = VaultStore(context, restoreName)
        val recovered = VaultStore(context, recoveryName)
        val bad = VaultStore(context, badName)
        try {
            val recovery = source.create(password.toByteArray())
            val bytes = ByteArray(140000) { (it % 251).toByte() }
            val id = source.importBytes("private-image.png", "image/png", bytes)
            val note = source.newNote()
            source.addBlock(note, "text", "Private note content")
            val output = ByteArrayOutputStream()
            source.backup(output)
            val backup = output.toByteArray()
            assertFalse(String(backup, Charsets.ISO_8859_1).contains("private-image.png"))
            restored.restore(backup.inputStream(), password.toByteArray())
            restored.unlock(password.toByteArray())
            val exported = ByteArrayOutputStream()
            VaultTransfers(context, restored).copyFile(id, exported)
            assertArrayEquals(bytes, exported.toByteArray())
            assertEquals("Private note content", restored.dao.blocks(note).last().content)
            failed { restored.restore(backup.inputStream(), password.toByteArray()) }
            failed { bad.restore(backup.inputStream(), "wrong password".toByteArray()) }
            assertFalse(bad.exists)
            recovered.restore(
                backup.inputStream(),
                "Synthetic new password 456".toByteArray(),
                recovery,
            )
            recovered.unlock("Synthetic new password 456".toByteArray())
            assertNotNull(recovered.dao.display(id))
            // Remove one authenticated payload while retaining an otherwise valid ZIP.
            val changed = ByteArrayOutputStream()
            ZipOutputStream(changed).use { zip ->
                ZipInputStream(backup.inputStream()).use { input ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        if (!entry.name.startsWith("objects/")) {
                            zip.putNextEntry(ZipEntry(entry.name))
                            input.copyTo(zip)
                            zip.closeEntry()
                        }
                        input.closeEntry()
                    }
                }
            }
            failed { bad.restore(changed.toByteArray().inputStream(), password.toByteArray()) }
            assertFalse(bad.exists)
            val traversal = ByteArrayOutputStream()
            ZipOutputStream(traversal).use {
                it.putNextEntry(ZipEntry("../outside"))
                it.write(1)
                it.closeEntry()
            }
            failed { bad.restore(traversal.toByteArray().inputStream(), password.toByteArray()) }
            assertFalse(bad.exists)
            java.io.File(context.noBackupFilesDir, "$sourceName/header").delete()
            failed { source.backup(ByteArrayOutputStream()) }
            source.revoke()
            failed { VaultTransfers(context, source).copyFile(id, ByteArrayOutputStream()) }
        } finally {
            listOf(source, restored, recovered, bad).forEach { it.close() }
            listOf(sourceName, restoreName, recoveryName, badName).forEach {
                java.io.File(context.noBackupFilesDir, it).deleteRecursively()
            }
        }
    }
}
