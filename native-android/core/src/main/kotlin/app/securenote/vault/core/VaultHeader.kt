package app.securenote.vault.core

import java.nio.ByteBuffer
import java.util.UUID

/**
 * Fixed-width, bounded format. KDF is Argon2id v1.3, 64 MiB, t=3, p=1. Changing those parameters
 * requires a new format version, never attacker-selected costs.
 */
class VaultHeader
private constructor(
    val id: UUID,
    private val salt: ByteArray,
    private val passwordNonce: ByteArray,
    private val passwordWrap: ByteArray,
    private val recoveryNonce: ByteArray,
    private val recoveryWrap: ByteArray,
) {
    private fun aad(slot: String) =
        MAGIC +
            ByteBuffer.allocate(16)
                .putLong(id.mostSignificantBits)
                .putLong(id.leastSignificantBits)
                .array() +
            salt +
            slot.toByteArray()

    fun unlock(password: ByteArray, kdf: PasswordKdf): ByteArray {
        var key: ByteArray? = null
        return try {
            key = kdf.derive(password, salt.copyOf())
            Crypto.unseal(key, passwordNonce, passwordWrap, aad("password"))
        } finally {
            key?.fill(0)
            password.fill(0)
        }
    }

    fun recover(secret: ByteArray): ByteArray =
        try {
            require(secret.size == 32)
            Crypto.unseal(secret, recoveryNonce, recoveryWrap, aad("recovery"))
        } finally {
            secret.fill(0)
        }

    fun changePassword(root: ByteArray, password: ByteArray, kdf: PasswordKdf): VaultHeader {
        // Fresh salt means recovery must also be rewrapped with a newly generated recovery secret.
        // Keep the existing salt here to preserve the recovery wrapper; nonce is always fresh.
        var key: ByteArray? = null
        val nonce = Crypto.random(12)
        return try {
            key = kdf.derive(password, salt.copyOf())
            VaultHeader(
                id,
                salt,
                nonce,
                Crypto.seal(key, nonce, root, aad("password")),
                recoveryNonce,
                recoveryWrap,
            )
        } finally {
            key?.fill(0)
            password.fill(0)
        }
    }

    fun encode(): ByteArray =
        ByteBuffer.allocate(SIZE)
            .put(MAGIC)
            .putLong(id.mostSignificantBits)
            .putLong(id.leastSignificantBits)
            .put(salt)
            .put(passwordNonce)
            .put(passwordWrap)
            .put(recoveryNonce)
            .put(recoveryWrap)
            .array()

    companion object {
        private val MAGIC = "SVLT0001".toByteArray(Charsets.US_ASCII)
        const val SIZE = 160

        data class Created(val header: VaultHeader, val root: ByteArray, val recovery: ByteArray)

        fun create(password: ByteArray, kdf: PasswordKdf): Created {
            val root = Crypto.random(32)
            val recovery = Crypto.random(32)
            val salt = Crypto.random(16)
            val pn = Crypto.random(12)
            val rn = Crypto.random(12)
            val h = VaultHeader(UUID.randomUUID(), salt, pn, byteArrayOf(), rn, byteArrayOf())
            var key: ByteArray? = null
            return try {
                key = kdf.derive(password, salt.copyOf())
                Created(
                    VaultHeader(
                        h.id,
                        salt,
                        pn,
                        Crypto.seal(key, pn, root, h.aad("password")),
                        rn,
                        Crypto.seal(recovery, rn, root, h.aad("recovery")),
                    ),
                    root,
                    recovery,
                )
            } catch (e: Exception) {
                root.fill(0)
                recovery.fill(0)
                throw e
            } finally {
                key?.fill(0)
                password.fill(0)
            }
        }

        fun decode(bytes: ByteArray): VaultHeader {
            require(bytes.size == SIZE) { "Invalid vault header" }
            val b = ByteBuffer.wrap(bytes)
            fun take(n: Int) = ByteArray(n).also(b::get)
            require(take(8).contentEquals(MAGIC)) { "Unsupported vault version" }
            return VaultHeader(
                UUID(b.long, b.long),
                take(16),
                take(12),
                take(48),
                take(12),
                take(48),
            )
        }
    }
}
