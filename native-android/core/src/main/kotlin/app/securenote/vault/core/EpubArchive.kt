package app.securenote.vault.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Bounded ZIP32 access over a seekable source. No extraction, temporary files or ZIP filesystem.
 */
class EpubArchive(
    private val source: SeekableByteChannel,
    private val checkActive: () -> Unit = {},
) : AutoCloseable {
    data class Entry
    internal constructor(
        val name: String,
        val size: Long,
        internal val compressed: Long,
        internal val offset: Long,
        internal val crc: Long,
        internal val method: Int,
        internal val flags: Int,
    )

    private val length = source.size()
    private var directoryOffset = 0L
    val entries: Map<String, Entry> = index()

    init {
        require(read("mimetype", 64).toString(Charsets.US_ASCII).trim() == "application/epub+zip") {
            "Not an EPUB book"
        }
        require(entries.containsKey("META-INF/container.xml")) { "Missing EPUB container" }
    }

    private fun bytes(offset: Long, count: Int): ByteArray {
        checkActive()
        require(offset >= 0 && count >= 0 && offset <= length - count) { "Invalid book range" }
        val result = ByteArray(count)
        val buffer = ByteBuffer.wrap(result)
        source.position(offset)
        while (buffer.hasRemaining()) {
            checkActive()
            if (source.read(buffer) <= 0) throw IOException("Truncated EPUB")
        }
        return result
    }

    private fun index(): Map<String, Entry> {
        require(length >= 22) { "Invalid EPUB archive" }
        val tail = bytes((length - 65557).coerceAtLeast(0), minOf(length, 65557).toInt())
        var end = -1
        for (i in tail.size - 22 downTo 0) {
            if (u32(tail, i) == 0x06054b50L && i + 22 + u16(tail, i + 20) == tail.size) {
                end = i
                break
            }
        }
        require(end >= 0) { "Missing EPUB directory" }
        require(u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) {
            "Split EPUB archives are unsupported"
        }
        val count = u16(tail, end + 10)
        require(count in 1..20000 && u16(tail, end + 8) == count) { "Too many book resources" }
        val size = u32(tail, end + 12)
        directoryOffset = u32(tail, end + 16)
        require(size in 46..16L * 1024 * 1024 && directoryOffset != 0xffffffffL) {
            "ZIP64 EPUBs are unsupported"
        }
        val endOffset = length - tail.size + end
        require(directoryOffset >= 0 && directoryOffset + size == endOffset) {
            "Invalid EPUB directory range"
        }
        val directory = bytes(directoryOffset, size.toInt())
        val result = linkedMapOf<String, Entry>()
        val offsets = hashSetOf<Long>()
        var p = 0
        repeat(count) {
            checkActive()
            require(p <= directory.size - 46 && u32(directory, p) == 0x02014b50L) {
                "Invalid book resource"
            }
            val flags = u16(directory, p + 8)
            val method = u16(directory, p + 10)
            require(flags and 0x2041 == 0 && method in listOf(0, 8)) {
                "Encrypted or unsupported ZIP resource"
            }
            val compressed = u32(directory, p + 20)
            val plain = u32(directory, p + 24)
            val offset = u32(directory, p + 42)
            val nameLength = u16(directory, p + 28)
            val extraLength = u16(directory, p + 30)
            val commentLength = u16(directory, p + 32)
            require(
                nameLength in 1..2048 &&
                    p + 46L + nameLength + extraLength + commentLength <= directory.size
            ) {
                "Invalid book resource name"
            }
            require(
                u16(directory, p + 34) == 0 &&
                    compressed != 0xffffffffL &&
                    plain != 0xffffffffL &&
                    offset != 0xffffffffL
            ) {
                "ZIP64 EPUBs are unsupported"
            }
            val name = utf8(directory.copyOfRange(p + 46, p + 46 + nameLength))
            require(validEntryName(name) && !result.containsKey(name) && offsets.add(offset)) {
                "Unsafe or duplicate book resource"
            }
            require(
                offset >= 0 && offset < directoryOffset && compressed <= directoryOffset - offset
            ) {
                "Invalid book resource range"
            }
            result[name] =
                Entry(name, plain, compressed, offset, u32(directory, p + 16), method, flags)
            p += 46 + nameLength + extraLength + commentLength
        }
        require(p == directory.size) { "Unexpected EPUB directory data" }
        return result
    }

    @Synchronized
    fun read(name: String, limit: Int): ByteArray {
        checkActive()
        require(limit in 1..16 * 1024 * 1024)
        val entry = entries[name] ?: throw IOException("Missing book resource")
        require(entry.size <= limit && entry.compressed <= limit.toLong() * 2 + 65536) {
            "Book resource is too large"
        }
        require(entry.size <= maxOf(65536L, entry.compressed * 200)) {
            "Excessive EPUB compression ratio"
        }
        val header = bytes(entry.offset, 30)
        require(
            u32(header, 0) == 0x04034b50L &&
                u16(header, 6) == entry.flags &&
                u16(header, 8) == entry.method
        ) {
            "Invalid EPUB local header"
        }
        val nameLength = u16(header, 26)
        val extraLength = u16(header, 28)
        require(nameLength in 1..2048)
        require(utf8(bytes(entry.offset + 30, nameLength)) == name) {
            "EPUB resource name mismatch"
        }
        val start = entry.offset + 30 + nameLength + extraLength
        require(start <= directoryOffset && entry.compressed <= directoryOffset - start) {
            "Invalid EPUB data range"
        }
        if (entry.flags and 8 == 0)
            require(
                u32(header, 14) == entry.crc &&
                    u32(header, 18) == entry.compressed &&
                    u32(header, 22) == entry.size
            ) {
                "EPUB resource length mismatch"
            }
        val out =
            object : ByteArrayOutputStream(minOf(entry.size, 65536).toInt()) {
                override fun close() {
                    buf.fill(0)
                    super.close()
                }
            }
        val scratch = ByteArray(65536)
        val crc = CRC32()
        val inflater = if (entry.method == 8) Inflater(true) else null
        var loaded = 0L
        var compressedInput: ByteArray? = null
        try {
            if (inflater == null) {
                require(entry.compressed == entry.size)
                while (loaded < entry.compressed) {
                    checkActive()
                    val chunk =
                        bytes(
                            start + loaded,
                            minOf(scratch.size.toLong(), entry.compressed - loaded).toInt(),
                        )
                    try {
                        out.write(chunk)
                        crc.update(chunk)
                        loaded += chunk.size
                    } finally {
                        chunk.fill(0)
                    }
                }
            } else {
                while (!inflater.finished()) {
                    checkActive()
                    if (inflater.needsInput()) {
                        require(loaded < entry.compressed) { "Truncated compressed EPUB resource" }
                        compressedInput?.fill(0)
                        val chunk =
                            bytes(
                                start + loaded,
                                minOf(scratch.size.toLong(), entry.compressed - loaded).toInt(),
                            )
                        compressedInput = chunk
                        // Inflater retains this input until its next needsInput() call.
                        inflater.setInput(chunk)
                        loaded += chunk.size
                    }
                    val n = inflater.inflate(scratch)
                    require(
                        out.size().toLong() + n <= entry.size && out.size().toLong() + n <= limit
                    ) {
                        "EPUB decompression limit exceeded"
                    }
                    if (n > 0) {
                        out.write(scratch, 0, n)
                        crc.update(scratch, 0, n)
                    } else
                        require(inflater.finished() || inflater.needsInput()) {
                            "Invalid EPUB compression"
                        }
                }
                require(inflater.bytesRead == entry.compressed) {
                    "Unexpected compressed EPUB data"
                }
            }
            require(out.size().toLong() == entry.size && crc.value == entry.crc) {
                "Corrupt EPUB resource"
            }
            checkActive()
            return out.toByteArray()
        } finally {
            scratch.fill(0)
            inflater?.end()
            compressedInput?.fill(0)
            out.close()
        }
    }

    override fun close() = source.close()

    companion object {
        private fun u16(b: ByteArray, p: Int) =
            (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)

        private fun u32(b: ByteArray, p: Int) =
            u16(b, p).toLong() or (u16(b, p + 2).toLong() shl 16)

        private fun utf8(b: ByteArray): String =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b))
                .toString()

        private fun validEntryName(name: String): Boolean =
            name.length <= 2048 &&
                !name.startsWith('/') &&
                !name.contains('\\') &&
                !name.contains('\u0000') &&
                !name.contains(':') &&
                name.trimEnd('/').split('/').none { it.isEmpty() || it == "." || it == ".." }

        /**
         * Resolve publication-relative URLs inside the ZIP root; never resolve a filesystem/HTTP
         * URL.
         */
        fun resolve(base: String, reference: String): Pair<String, String>? = runCatching {
            if (reference.length > 4096 || reference.contains('\\') || reference.contains('\u0000'))
                return null
            val uri = URI(reference)
            if (uri.isAbsolute || uri.rawAuthority != null || uri.rawQuery != null) return null
            val path = uri.path.orEmpty()
            if (
                path.startsWith('/') ||
                    path.contains('\\') ||
                    path.contains('\u0000') ||
                    path.contains(':')
            )
                return null
            if (path.isEmpty()) return base to uri.fragment.orEmpty().take(512)
            val parts =
                base
                    .substringBeforeLast('/', "")
                    .split('/')
                    .filter { it.isNotEmpty() }
                    .toMutableList()
            for (part in path.split('/')) when (part) {
                "",
                "." -> Unit
                ".." -> {
                    if (parts.isEmpty()) return null
                    parts.removeAt(parts.lastIndex)
                }
                else -> parts.add(part)
            }
            val result = parts.joinToString("/")
            if (!validEntryName(result)) null else result to uri.fragment.orEmpty().take(512)
        }
            .getOrNull()
    }
}
