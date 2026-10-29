package app.securenote.vault.core

import java.text.Normalizer
import java.util.Locale

/** A shared file/directory namespace, also safe on case-insensitive destination providers. */
class ExportNames(existing: Iterable<String> = emptyList()) {
    private val used = existing.mapTo(mutableSetOf(), ::key)
    private val nextSuffix = mutableMapOf<Pair<String, Boolean>, Int>()

    fun reserve(preferred: String, directory: Boolean = false): String {
        val name = safe(preferred)
        if (used.add(key(name))) return name
        val dot =
            if (directory) -1
            else name.lastIndexOf('.').takeIf { it > 0 && it < name.lastIndex } ?: -1
        val stem = if (dot >= 0) name.substring(0, dot) else name
        val extension = if (dot >= 0) name.substring(dot) else ""
        val collisionKey = key(name) to directory
        var suffix = nextSuffix[collisionKey] ?: 2
        while (true) {
            val candidate = "$stem (${suffix++})$extension"
            if (used.add(key(candidate))) {
                nextSuffix[collisionKey] = suffix
                return candidate
            }
        }
    }

    fun contains(name: String): Boolean = key(name) in used

    fun observe(actual: String) {
        used.add(key(actual))
    }

    companion object {
        fun key(name: String): String =
            Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

        fun safe(name: String): String =
            name
                .replace(Regex("[\\\\/\\p{Cntrl}]"), "_")
                .ifBlank { "file" }
                .let { if (it == "." || it == "..") "file" else it }
    }
}
