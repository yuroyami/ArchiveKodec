package io.github.yuroyami.archivekodec.codec.zstd

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/** Highest set bit of a positive value. */
internal fun highBit(value: Int): Int = 31 - value.countLeadingZeroBits()

/**
 * A zstd backward bitstream over input[[start] until [end]): the last byte's highest set
 * bit marks the end, and fields are read from there towards the start (RFC 8878 4.1).
 * Reading past the start yields zeros and is reported by [overflowed].
 */
internal class BackwardBits(private val input: ByteArray, private val start: Int, end: Int) {
    var position: Long
        private set

    init {
        if (end <= start) throw ArchiveFormatException("zstd: empty bitstream", start.toLong())
        val last = input[end - 1].toInt() and 0xFF
        if (last == 0) throw ArchiveFormatException("zstd: bitstream has no end marker", (end - 1).toLong())
        position = (end - start - 1) * 8L + highBit(last)
    }

    val overflowed: Boolean get() = position < 0
    val finished: Boolean get() = position == 0L

    /** The [n] bits below the current position, as a little-endian field; missing low bits are zero. */
    fun peek(n: Int): Int {
        if (n == 0) return 0
        val low = position - n
        var value = 0L
        val from = maxOf(low, 0)
        if (from < position) {
            val firstByte = (from ushr 3).toInt()
            val lastByte = ((position - 1) ushr 3).toInt()
            for (b in lastByte downTo firstByte) value = value shl 8 or (input[start + b].toLong() and 0xFF)
            value = value ushr (from and 7).toInt()
            value = value and ((1L shl (position - from).toInt()) - 1)
        }
        return (if (low < 0) value shl (-low).toInt() else value).toInt()
    }

    fun read(n: Int): Int {
        val value = peek(n)
        position -= n
        return value
    }

    fun skip(n: Int) { position -= n }
}

/** A forward LSB-first bit writer whose [close] adds the end marker a [BackwardBits] reader expects. */
internal class ForwardBits(private val out: ByteArrayBuilder) {
    private var buffer = 0L
    private var count = 0

    fun write(value: Long, n: Int) {
        if (n == 0) return
        buffer = buffer or ((value and ((1L shl n) - 1)) shl count)
        count += n
        while (count >= 8) {
            out.append(buffer.toByte())
            buffer = buffer ushr 8
            count -= 8
        }
    }

    fun close() {
        write(1, 1)
        if (count > 0) { out.append(buffer.toByte()); buffer = 0; count = 0 }
    }
}
