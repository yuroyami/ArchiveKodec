package io.github.yuroyami.archivekodec.archive

/**
 * A seekable, random-access byte source. Every container reader works against this
 * read contract. ZIP "browse without extract" (read the central directory at the
 * tail) and single-entry extract (seek to one local header) both need offset reads
 * rather than a forward-only stream.
 *
 * [ByteArrayRandomAccessSource] is the only implementation shipped, so an archive
 * has to be fully in memory before a reader can open it. Implement this interface
 * yourself to back a reader with a real file handle.
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
