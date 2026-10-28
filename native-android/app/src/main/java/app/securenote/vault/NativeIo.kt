package app.securenote.vault

internal object NativeIo {
    init {
        System.loadLibrary("vault_kdf")
    }

    external fun makeUncached(fd: Int)
}
