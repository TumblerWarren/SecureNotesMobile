package app.securenote.vault

import app.securenote.vault.core.PasswordKdf

object Sodium : PasswordKdf {
    init {
        System.loadLibrary("vault_kdf")
    }

    private external fun deriveNative(password: ByteArray, salt: ByteArray): ByteArray

    override fun derive(password: ByteArray, salt: ByteArray): ByteArray =
        deriveNative(password, salt)
}
