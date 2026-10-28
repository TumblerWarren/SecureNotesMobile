package app.securenote.vault.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Read-only compatibility with the original CryptoJS salt16 + iv12 + CBC format. CryptoJS pads the
 * missing CBC IV word with zeros. Never use this format for new writes.
 */
object LegacyCodec {
    const val MAX_BYTES = 64 * 1024 * 1024

    fun decrypt(input: InputStream, password: ByteArray): ByteArray {
        var key: ByteArray? = null
        val chars = password.toString(Charsets.UTF_8).toCharArray()
        password.fill(0)
        try {
            fun read(n: Int): ByteArray {
                val bytes = ByteArray(n)
                var p = 0
                while (p < n) {
                    val count = input.read(bytes, p, n - p)
                    require(count > 0) { "Truncated legacy file" }
                    p += count
                }
                return bytes
            }
            val salt = read(16)
            val iv = read(12).copyOf(16)
            val spec = PBEKeySpec(chars, salt, 100000, 256)
            key =
                try {
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(spec)
                        .encoded
                } finally {
                    spec.clearPassword()
                }
            val cipher =
                Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                }
            val buffer = ByteArray(65536)
            val plain =
                try {
                    CipherInputStream(input, cipher).use { source ->
                        ByteArrayOutputStream().use { out ->
                            while (true) {
                                val n = source.read(buffer)
                                if (n < 0) break
                                require(out.size().toLong() + n <= MAX_BYTES) {
                                    "Legacy database exceeds 64 MiB limit"
                                }
                                out.write(buffer, 0, n)
                            }
                            out.toByteArray()
                        }
                    }
                } finally {
                    buffer.fill(0)
                }
            val magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
            if (plain.size < 100 || !plain.copyOfRange(0, 16).contentEquals(magic)) {
                plain.fill(0)
                error("Invalid legacy database or password")
            }
            return plain
        } finally {
            chars.fill('\u0000')
            key?.fill(0)
            password.fill(0)
        }
    }
}
