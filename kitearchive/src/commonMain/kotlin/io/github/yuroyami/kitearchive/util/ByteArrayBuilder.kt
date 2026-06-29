package io.github.yuroyami.kitearchive.util

/**
 * A minimal growable byte buffer — the pure-Kotlin equivalent of a `ByteArrayOutputStream`,
 * with no `java.io` dependency so it lives in `commonMain`. Amortised O(1) append by
 * doubling. Used by the DEFLATE encoder and the gzip/zlib framers.
 */
class ByteArrayBuilder(initialCapacity: Int = 64) {
    private var buf = ByteArray(if (initialCapacity < 16) 16 else initialCapacity)
    private var len = 0

    /** Number of bytes written so far. */
    val size: Int get() = len

    /** Append a single byte. */
    fun append(b: Byte) {
        ensure(1)
        buf[len++] = b
    }

    /** Append a slice of [bytes]. */
    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        if (length == 0) return
        ensure(length)
        bytes.copyInto(buf, len, offset, offset + length)
        len += length
    }

    private fun ensure(extra: Int) {
        val needed = len + extra
        if (needed > buf.size) {
            var cap = buf.size
            while (cap < needed) cap = cap shl 1
            buf = buf.copyOf(cap)
        }
    }

    /** Snapshot the written bytes into a right-sized array. */
    fun toByteArray(): ByteArray = buf.copyOf(len)
}
