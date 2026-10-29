package app.securenote.vault.core

import kotlin.test.*

class BackupAuthenticationTest {
    @Test
    fun inventoryRejectsChangesOmissionsReorderingAndDifferentVault() {
        val root = ByteArray(32) { it.toByte() }
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        fun tag(key: ByteArray = root, entries: List<Pair<String, ByteArray>>): ByteArray =
            BackupAuthentication(key)
                .apply { entries.forEach { (name, digest) -> entry(name, digest) } }
                .finish()
        val entries = listOf("header" to a, "index.db" to b)
        val expected = tag(entries = entries)
        assertContentEquals(expected, tag(entries = entries))
        assertFalse(expected.contentEquals(tag(entries = entries.reversed())))
        assertFalse(expected.contentEquals(tag(entries = entries.take(1))))
        assertFalse(expected.contentEquals(tag(entries = listOf("header" to b, "index.db" to b))))
        assertFalse(expected.contentEquals(tag(ByteArray(32), entries)))
        assertFalse(
            expected.contentEquals(tag(entries = listOf("header" to a, "index.db-wal" to b)))
        )
    }
}
