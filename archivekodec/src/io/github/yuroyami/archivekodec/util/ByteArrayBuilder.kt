package io.github.yuroyami.archivekodec.util

/**
 * A minimal growable byte buffer. It is the pure-Kotlin equivalent of a
 * `ByteArrayOutputStream`, with no `java.io` dependency, so it lives in common code.
 * Amortised O(1) append by doubling. Used to assemble encoded data, decoded LZ4
 * frames, wrapper headers/trailers and ZIP archives.
 * Appends throw [io.github.yuroyami.archivekodec.ArchiveSizeException] when the
 * result cannot fit in one ByteArray.
 * Instances are mutable and not thread-safe; confine each builder to one thread
 * or externally serialize all access, including snapshots.
 */
internal class ByteArrayBuilder(initialCapacity: Int = 64) {
    private var buf = ByteArray(if (initialCapacity < 16) 16 else initialCapacity)
    private var len = 0
    private var maximum = Int.MAX_VALUE
    private var growthMaximum = Int.MAX_VALUE
    private var growthHint = 0

    internal constructor(initialCapacity: Int, maximum: Int) : this(minOf(initialCapacity, maximum)) {
        require(maximum >= 0)
        this.maximum = maximum
        this.growthMaximum = maximum
        if (buf.size > maximum) buf = ByteArray(maximum)
    }

    /** Number of bytes written so far. */
    val size: Int get() = len

    /** Append a single byte. */
    @Throws(io.github.yuroyami.archivekodec.ArchiveException::class)
    fun append(b: Byte) {
        ensure(1)
        buf[len++] = b
    }

    /** Append a slice of [bytes]. */
    @Throws(io.github.yuroyami.archivekodec.ArchiveException::class, IllegalArgumentException::class)
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
        // Room already, within the growth bound: decided in Int arithmetic, which matters on
        // Kotlin/JS where this runs for every literal run and match.
        if (extra >= 0 && extra <= buf.size - len && extra <= growthMaximum - len) return
        val capacity = byteArrayCapacity(buf.size, len, extra, growthMaximum, growthHint)
        if (capacity != buf.size) buf = buf.copyOf(capacity)
    }

    /** Snapshot the written bytes into a right-sized array. */
    fun toByteArray(): ByteArray = buf.copyOf(len)

    /** Transfer the written bytes, clearing this builder so no mutable alias remains. */
    internal fun takeByteArray(): ByteArray {
        val result = if (len == buf.size) buf else buf.copyOf(len)
        buf = ByteArray(0)
        len = 0
        return result
    }

    /** Bound subsequent growth for one frame; an earlier frame may have reserved more. */
    internal fun limitGrowth(limit: Int, sizeHint: Int = 0) {
        require(limit in len..maximum && sizeHint in 0..limit)
        growthMaximum = limit
        growthHint = sizeHint
    }

    /** Append a validated LZ match, with forward-copy semantics for overlapping bytes. */
    internal fun copyBack(distance: Int, length: Int) {
        require(distance in 1..len && length >= 0)
        ensure(length)
        val output = buf
        val end = len + length
        var source = len - distance
        if (distance >= length) {
            output.copyInto(output, len, source, source + length)
        } else if (distance == 1) {
            output.fill(output[source], len, end)
        } else if (length >= 64) {
            // Seed one complete pattern, then double only already-written bytes.
            // Every copy is non-overlapping, including matches across old blocks.
            output.copyInto(output, len, source, source + distance)
            var copied = distance
            while (copied < length) {
                val count = minOf(copied, length - copied)
                output.copyInto(output, len + copied, len, len + count)
                copied += count
            }
        } else {
            var destination = len
            while (destination < end) output[destination++] = output[source++]
        }
        len = end
    }

    /** Inspect written bytes without copying; [calculate] must not retain or modify storage. */
    internal fun checksum(calculate: (ByteArray, Int) -> Long): Long = calculate(buf, len)
}
