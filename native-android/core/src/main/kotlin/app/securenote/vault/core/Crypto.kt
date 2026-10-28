package app.securenote.vault.core

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.StreamingAeadKeyTemplates
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun interface PasswordKdf {
    fun derive(password: ByteArray, salt: ByteArray): ByteArray
}

object Crypto {
    private val random = SecureRandom()

    init {
        StreamingAeadConfig.register()
    }

    fun random(size: Int) = ByteArray(size).also(random::nextBytes)

    fun seal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(plain)
        }

    fun unseal(key: ByteArray, nonce: ByteArray, cipher: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(cipher)
        }

    // HKDF-SHA256 with an all-zero salt and a domain-separated context.
    fun subkey(root: ByteArray, label: String): ByteArray {
        fun hmac(k: ByteArray, m: ByteArray) =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(k, "HmacSHA256"))
                doFinal(m)
            }
        val prk = hmac(ByteArray(32), root)
        return try {
            hmac(prk, "SecureNote/v1/$label".toByteArray() + byteArrayOf(1))
        } finally {
            prk.fill(0)
        }
    }

    fun fileKey(): ByteArray =
        ByteArrayOutputStream().use { out ->
            CleartextKeysetHandle.write(
                KeysetHandle.generateNew(StreamingAeadKeyTemplates.AES256_GCM_HKDF_1MB),
                BinaryKeysetWriter.withOutputStream(out),
            )
            out.toByteArray()
        }

    fun stream(key: ByteArray): StreamingAead =
        CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(key))
            .getPrimitive(StreamingAead::class.java)

    fun aad(vault: String, item: String) =
        "SecureNote/object/v1/$vault/$item".toByteArray(Charsets.UTF_8)
}
