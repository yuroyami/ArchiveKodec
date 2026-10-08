package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException

/**
 * A seekable, random-access byte source. Every container reader works against this
 * read contract. ZIP "browse without extract" (read the central directory at the
 * tail) and single-entry extract (seek to one local header) both need offset reads
 * rather than a forward-only stream.
 *
 * [ByteArrayRandomAccessSource] is the only implementation shipped, so an archive
 * has to be fully in memory before a reader can open it. Implement this interface
 * yourself to back a reader with a real file handle.
 *
 * Supplying this source to a built-in reader transfers ownership to that reader;
 * close the reader to release it. Do not share an owned source with another reader
 * or close it independently while its reader is in use.
 *
 * Implementations need not support concurrent calls. Unless an implementation
 * explicitly promises otherwise, serialize access to [size], [read] and [close].
 * Built-in readers do not synchronize access to their source.
 */
public interface RandomAccessSource {
    /** Total size in bytes. */
    public val size: Long

    /**
     * Read up to [length] bytes starting at absolute [offset] into [into] at
     * [intoOffset]. Returns the number of bytes read, or -1 at end of source.
     * A zero-length read returns 0, including at EOF.
     *
     * @throws ArchiveFormatException for a negative source offset.
     * @throws IllegalArgumentException for an invalid destination slice.
     */
    @Throws(ArchiveException::class, IllegalArgumentException::class)
    public fun read(offset: Long, into: ByteArray, intoOffset: Int = 0, length: Int = into.size): Int

    /** Release any underlying handle. No-op for in-memory sources. */
    @Throws(ArchiveException::class)
    public fun close() {}
}

/**
 * Read exactly [length] bytes at [offset]. Validate the source range before allocation.
 *
 * @throws ArchiveFormatException for an invalid range or a premature end of source.
 */
@Throws(ArchiveException::class)
public fun RandomAccessSource.readFully(offset: Long, length: Int): ByteArray =
    readFullyChecked(offset, length.toLong())

internal fun RandomAccessSource.requireRange(offset: Long, length: Long) {
    val total = size
    if (total < 0 || offset < 0 || length < 0 || offset > total || length > total - offset) {
        throw ArchiveFormatException("source range at $offset of $length bytes exceeds source size $total", offset)
    }
}

internal fun RandomAccessSource.readFullyChecked(offset: Long, length: Long): ByteArray {
    requireRange(offset, length)
    if (length > Int.MAX_VALUE) {
        throw ArchiveFormatException("range of $length bytes cannot fit in a ByteArray", offset)
    }
    val count = length.toInt()
    val buf = ByteArray(count)
    var got = 0
    while (got < count) {
        val n = read(offset + got, buf, got, count - got)
        if (n <= 0 || n > count - got) {
            throw ArchiveFormatException("unexpected read at ${offset + got} (wanted ${count - got}, got $n)", offset + got)
        }
        got += n
    }
    return buf
}

/**
 * A [RandomAccessSource] backed by an in-memory [ByteArray], without copying it.
 * The caller must keep the backing bytes unchanged while this source is in use.
 * Concurrent reads are supported with separate destination buffers that do not
 * alias the backing array. [close] is a no-op and does not release the array.
 */
public class ByteArrayRandomAccessSource(private val data: ByteArray) : RandomAccessSource {
    override val size: Long get() = data.size.toLong()

    override fun toString(): String = "ByteArrayRandomAccessSource(size=${data.size})"

    @Throws(ArchiveException::class, IllegalArgumentException::class)
    override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
        if (offset < 0) throw ArchiveFormatException("negative source offset $offset", offset)
        require(intoOffset >= 0 && length >= 0 && intoOffset <= into.size && length <= into.size - intoOffset) {
            "invalid destination slice: offset $intoOffset, length $length, size ${into.size}"
        }
        if (length == 0) return 0
        if (offset >= data.size) return -1
        val start = offset.toInt()
        val avail = minOf(length, data.size - start)
        data.copyInto(into, intoOffset, start, start + avail)
        return avail
    }
}
