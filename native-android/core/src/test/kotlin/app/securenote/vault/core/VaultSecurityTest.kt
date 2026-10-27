package app.securenote.vault.core

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.*
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

class VaultSecurityTest {
    private val kdf = PasswordKdf { password, salt ->
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(65536)
                .withIterations(3)
                .withParallelism(1)
                .build()
        )
        ByteArray(32).also { generator.generateBytes(password, it) }
    }

    @Test
    fun headerPasswordRecoveryAndTamper() {
        val made = VaultHeader.create("correct long passphrase".toByteArray(), kdf)
        try {
            val decoded = VaultHeader.decode(made.header.encode())
            assertContentEquals(
                made.root,
                decoded.unlock("correct long passphrase".toByteArray(), kdf),
            )
            assertContentEquals(made.root, decoded.recover(made.recovery.copyOf()))
            assertFails { decoded.unlock("incorrect passphrase".toByteArray(), kdf) }
            val modified = made.header.encode().also { it[65] = (it[65].toInt() xor 1).toByte() }
            assertFails {
                VaultHeader.decode(modified).unlock("correct long passphrase".toByteArray(), kdf)
            }
            assertFails { VaultHeader.decode(made.header.encode() + 0) }
            val changed =
                decoded.changePassword(made.root, "replacement long password".toByteArray(), kdf)
            assertContentEquals(
                made.root,
                changed.unlock("replacement long password".toByteArray(), kdf),
            )
            assertContentEquals(made.root, changed.recover(made.recovery.copyOf()))
            assertFails { changed.unlock("correct long passphrase".toByteArray(), kdf) }
        } finally {
            made.root.fill(0)
            made.recovery.fill(0)
        }
    }

    @Test
    fun randomAccessAuthenticationAndLocking() {
        val dir = Files.createTempDirectory("vault-test").toFile()
        try {
            VaultSession(Crypto.random(32)).use { session ->
                val objects = EncryptedObjects(dir, "vault-a")
                val key = Crypto.fileKey()
                val plain = ByteArray(3 * 1024 * 1024 + 47) { (it % 251).toByte() }
                val (id, size) = objects.import(ByteArrayInputStream(plain), key, session)
                assertEquals(plain.size.toLong(), size)
                val reader = objects.open(id, key, session)
                assertEquals(size, reader.size())
                for (offset in listOf(0, 1048500, 2 * 1024 * 1024, plain.size - 100, 42)) {
                    val buffer = ByteBuffer.allocate(100)
                    assertEquals(100, reader.read(offset.toLong(), buffer))
                    assertContentEquals(plain.copyOfRange(offset, offset + 100), buffer.array())
                }
                assertEquals(-1, reader.read(size, ByteBuffer.allocate(1)))
                assertFails {
                    EncryptedObjects(dir, "different-vault").open(id, key, session).use {
                        it.read(0, ByteBuffer.allocate(1))
                    }
                }
                session.close()
                assertFails { reader.read(0, ByteBuffer.allocate(1)) }
                assertFails { objects.open(id, key, session) }
                assertFails { objects.open("../../outside", key, session) }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun corruptedCiphertextNeverReturnsUnauthenticatedBytes() {
        val dir = Files.createTempDirectory("vault-tamper").toFile()
        try {
            VaultSession(Crypto.random(32)).use { s ->
                val objects = EncryptedObjects(dir, "vault")
                val key = Crypto.fileKey()
                val (id, _) = objects.import(ByteArrayInputStream(ByteArray(2000000) { 7 }), key, s)
                val file = java.io.File(dir, "$id.svf")
                java.io.RandomAccessFile(file, "rw").use { f ->
                    f.seek(500)
                    val original = f.readUnsignedByte()
                    f.seek(500)
                    f.write(original xor 1)
                }
                assertFails {
                    objects.open(id, key, s).use { reader ->
                        reader.read(0, ByteBuffer.allocate(100))
                    }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun interruptedImportLeavesNoCommittedObject() {
        val dir = Files.createTempDirectory("vault-cancel").toFile()
        try {
            VaultSession(Crypto.random(32)).use { s ->
                val objects = EncryptedObjects(dir, "vault")
                assertFails {
                    objects.import(ByteArrayInputStream(ByteArray(2000000)), Crypto.fileKey(), s) {
                        s.close()
                    }
                }
                assertTrue(dir.walk().none { it.isFile })
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun emptyFilesAndObjectSubstitution() {
        val dir = Files.createTempDirectory("vault-empty").toFile()
        try {
            VaultSession(Crypto.random(32)).use { s ->
                val objects = EncryptedObjects(dir, "vault")
                val key = Crypto.fileKey()
                val (first, size) = objects.import(ByteArrayInputStream(byteArrayOf()), key, s)
                assertEquals(0L, size)
                objects.open(first, key, s).use {
                    assertEquals(-1, it.read(0, ByteBuffer.allocate(1)))
                }
                val (second, _) = objects.import(ByteArrayInputStream(byteArrayOf(1)), key, s)
                java.io
                    .File(dir, "$second.svf")
                    .copyTo(java.io.File(dir, "$first.svf"), overwrite = true)
                assertFails {
                    objects.open(first, key, s).use { it.read(0, ByteBuffer.allocate(1)) }
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun utf8TextPagesPreserveBoundaryCharacters() {
        val dir = Files.createTempDirectory("vault-text").toFile()
        try {
            VaultSession(Crypto.random(32)).use { s ->
                val text = "a".repeat(65535) + "😀é中" + "b".repeat(65530) + "é😀tail"
                val source = text.toByteArray(Charsets.UTF_8)
                val objects = EncryptedObjects(dir, "text")
                val key = Crypto.fileKey()
                val (id, length) = objects.import(source.inputStream(), key, s)
                objects.open(id, key, s).use { reader ->
                    assertEquals(
                        text,
                        (0 until TextPage.count(length)).joinToString("") {
                            TextPage.read(reader, length, it)
                        },
                    )
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
