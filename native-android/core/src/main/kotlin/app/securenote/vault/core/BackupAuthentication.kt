package app.securenote.vault.core

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticates the ordered snapshot inventory as well as each encrypted payload's digest. */
class BackupAuthentication(root: ByteArray) {
    private val mac = Mac.getInstance("HmacSHA256")

    init {
        val key = Crypto.subkey(root, "backup-authentication-v1")
        try {
            mac.init(SecretKeySpec(key, "HmacSHA256"))
        } finally {
            key.fill(0)
        }
        mac.update("SecureNote encrypted vault v1".toByteArray())
    }

    fun entry(name: String, digest: ByteArray) {
        require(digest.size == 32)
        val bytes = name.toByteArray(Charsets.UTF_8)
        mac.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        mac.update(bytes)
        mac.update(digest)
    }

    fun finish(): ByteArray = mac.doFinal()
}
