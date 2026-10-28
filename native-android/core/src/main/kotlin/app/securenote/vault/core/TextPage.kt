package app.securenote.vault.core

import java.nio.ByteBuffer

object TextPage {
    const val BYTES = 65536

    fun count(length: Long): Long = if (length == 0L) 1 else (length - 1) / BYTES + 1

    /** Four overlap bytes make UTF-8 characters crossing page boundaries appear exactly once. */
    fun read(reader: EncryptedObjects.Reader, length: Long, page: Long): String {
        require(page in 0 until count(length))
        val start = Math.multiplyExact(page, BYTES.toLong())
        val from = (start - 4).coerceAtLeast(0)
        val limit = minOf(length, Math.addExact(start, BYTES.toLong() + 4))
        val buffer = ByteBuffer.allocate((limit - from).toInt())
        try {
            var position = from
            while (buffer.hasRemaining()) {
                val n = reader.read(position, buffer)
                if (n < 0) break
                position += n
            }
            val bytes = buffer.array()
            val available = buffer.position()
            var a = (start - from).toInt().coerceAtMost(available)
            var b = (minOf(length, start + BYTES) - from).toInt().coerceAtMost(available)
            fun continuation(i: Int) = i < available && (bytes[i].toInt() and 0xc0) == 0x80
            while (continuation(a)) a++
            while (continuation(b)) b++
            if (
                start == 0L &&
                    available >= 3 &&
                    bytes[0] == 0xef.toByte() &&
                    bytes[1] == 0xbb.toByte() &&
                    bytes[2] == 0xbf.toByte()
            )
                a = 3
            return String(bytes, a, (b - a).coerceAtLeast(0), Charsets.UTF_8)
        } finally {
            buffer.array().fill(0)
        }
    }
}
