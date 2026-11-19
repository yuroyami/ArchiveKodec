package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException

/**
 * Where streamed input comes from: the pull-side partner of [ByteSink]. Streaming decoders
 * read compressed bytes from a source as they need them, so the input never has to sit in
 * memory whole, and the source can be a file, a socket or another decoder.
 * `archivekodec-io` adapts a kotlinx-io `RawSource` to it.
 */
public fun interface ByteSource {
    /**
     * Read up to [length] bytes into `into[offset, offset + length)` and return how many were
     * read, or -1 at the end of the input. Return at least one byte unless the input has
     * ended or [length] is zero; a source may return fewer bytes than asked for.
     *
     * @throws ArchiveException if the bytes cannot be read; other exceptions propagate unchanged.
     */
    @Throws(ArchiveException::class)
    public fun read(into: ByteArray, offset: Int, length: Int): Int

    /** Sources over common inputs. */
    public companion object {
        /** A source over `bytes[offset, offset + length)`, which it reads without copying. */
        @kotlin.jvm.JvmStatic
        @kotlin.jvm.JvmOverloads
        public fun of(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ByteSource {
            require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset) {
                "invalid source slice: offset $offset, length $length, size ${bytes.size}"
            }
            var position = offset
            val end = offset + length
            return ByteSource { into, at, count ->
                if (position == end) -1 else {
                    val n = minOf(count, end - position)
                    bytes.copyInto(into, at, position, position + n)
                    position += n
                    n
                }
            }
        }
    }
}

/**
 * A buffered reader over a [ByteSource]: a window of read-ahead bytes that decoders consume
 * from, refilled from the source as they run out. [unread] gives back what was read ahead and
 * not used, so the bytes after a compressed stream (a gzip trailer, the next member) are not
 * lost.
 */
internal class SourceBuffer(private val source: ByteSource, capacity: Int = 64 * 1024) {
    var buffer = ByteArray(capacity)
        private set
    /** Next unread byte in [buffer]. */
    var position = 0
    /** End of the valid bytes in [buffer]. */
    var end = 0
        private set
    /** Stream offset of buffer[0], for error positions. */
    var base = 0L
        private set
    private var exhausted = false

    val available: Int get() = end - position

    /**
     * Make at least [need] bytes available if the input holds them, keeping the unread ones;
     * returns false when the input ended first.
     */
    fun ensure(need: Int): Boolean {
        if (end - position >= need) return true
        if (exhausted) return false
        if (position > 0) {
            buffer.copyInto(buffer, 0, position, end)
            base += position
            end -= position
            position = 0
        }
        if (need > buffer.size) buffer = buffer.copyOf(maxOf(need, buffer.size * 2))
        while (end < need) {
            val n = source.read(buffer, end, buffer.size - end)
            if (n < 0) { exhausted = true; return false }
            if (n == 0 || n > buffer.size - end) throw ArchiveFormatException("source returned $n bytes for ${buffer.size - end} asked", base + end)
            end += n
        }
        return true
    }

    /** Read one byte, or -1 at the end of the input. */
    fun read(): Int = if (ensure(1)) buffer[position++].toInt() and 0xFF else -1

    /** Read exactly [length] bytes, failing with [ArchiveFormatException] at the end of the input. */
    fun readFully(into: ByteArray, offset: Int, length: Int, what: String) {
        var done = 0
        while (done < length) {
            if (!ensure(1)) throw ArchiveFormatException("$what is truncated", base + position)
            val n = minOf(length - done, end - position)
            buffer.copyInto(into, offset + done, position, position + n)
            position += n
            done += n
        }
    }

    /** True if no byte remains in the buffer or the source. */
    fun atEnd(): Boolean = !ensure(1)

    /** Stream offset of the next unread byte. */
    val streamPosition: Long get() = base + position
}
