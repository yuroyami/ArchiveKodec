package io.github.yuroyami.archivekodec.util

/**
 * A minimal growable byte buffer. It is the pure-Kotlin equivalent of a
 * `ByteArrayOutputStream`, with no `java.io` dependency, so it lives in `commonMain`.
 * Amortised O(1) append by doubling. Used by the DEFLATE encoder and the gzip and
 * zlib framers.
 * Appends throw [io.github.yuroyami.archivekodec.ArchiveSizeException] when the
 * result cannot fit in one ByteArray.
 */
class ByteArrayBuilder(initialCapacity: Int = 64) {
    private var buf = ByteArray(if (initialCapacity < 16) 16 else initialCapacity)
    private var len = 0
    private var maximum = Int.MAX_VALUE

    internal constructor(initialCapacity: Int, maximum: Int) : this(minOf(initialCapacity, maximum)) {
        require(maximum >= 0)
        this.maximum = maximum
        if (buf.size > maximum) buf = ByteArray(maximum)
    }

    /** Number of bytes written so far. */
    val size: Int get() = len

    /** Append a single byte. */
    fun append(b: Byte) {
        ensure(1)
        buf[len++] = b
    }

    /** Append a slice of [bytes]. */
    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset) {
            "invalid source slice: offset $offset, length $length, size ${bytes.size}"
        }
        if (length == 0) return
        ensure(length)
        bytes.copyInto(buf, len, offset, offset + length)
        len += length
    }

    private fun ensure(extra: Int) {
        val capacity = byteArrayCapacity(buf.size, len, extra, maximum)
        if (capacity != buf.size) buf = buf.copyOf(capacity)
    }

    /** Snapshot the written bytes into a right-sized array. */
    fun toByteArray(): ByteArray = buf.copyOf(len)
}
