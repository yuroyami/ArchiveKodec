package io.github.yuroyami.kitearchive.archive

/**
 * A seekable, random-access byte source — the load-bearing read contract for
 * archives. ZIP "browse without extract" (read the central directory at the tail)
 * and single-entry extract (seek to one local header) both need offset reads, not a
 * forward-only stream. Multi-volume archives are modelled as a composed source over
 * an ordered list (a future `ConcatenatedSource`).
 *
 * Platform actuals (file handle on JVM/Android, `FileHandle` on iOS) live in
 * `:kitearchive-io`; [ByteArrayRandomAccessSource] is the in-memory implementation
 * the core ships for tests and small buffers.
 */
interface RandomAccessSource {
    /** Total size in bytes. */
    val size: Long

    /**
     * Read up to [length] bytes starting at absolute [offset] into [into] at
     * [intoOffset]. Returns the number of bytes read, or -1 at end of source.
     */
    fun read(offset: Long, into: ByteArray, intoOffset: Int = 0, length: Int = into.size): Int

    /** Release any underlying handle. No-op for in-memory sources. */
    fun close() {}
}

/**
 * Read exactly [length] bytes at [offset], or fail. Convenience over [RandomAccessSource.read].
 */
fun RandomAccessSource.readFully(offset: Long, length: Int): ByteArray {
    val buf = ByteArray(length)
    var got = 0
    while (got < length) {
        val n = read(offset + got, buf, got, length - got)
        if (n <= 0) throw IllegalStateException("unexpected EOF at ${offset + got} (wanted $length)")
        got += n
    }
    return buf
}

/** A [RandomAccessSource] backed by an in-memory [ByteArray]. */
class ByteArrayRandomAccessSource(private val data: ByteArray) : RandomAccessSource {
    override val size: Long get() = data.size.toLong()

    override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
        if (offset >= data.size) return -1
        val start = offset.toInt()
        val avail = minOf(length, data.size - start)
        data.copyInto(into, intoOffset, start, start + avail)
        return avail
    }
}
