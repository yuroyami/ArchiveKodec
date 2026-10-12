package io.github.yuroyami.archivekodec.codec.lzx

import io.github.yuroyami.archivekodec.ArchiveFormatException

/**
 * Bits as Microsoft's LZX and XPRESS Huffman formats store them: little-endian 16-bit words,
 * each read most significant bit first. Bytes stored whole sit at [position], after the words
 * already loaded. A few zero words stand in past the end, because decoders peek ahead.
 */
internal open class Msb16Reader(
    protected val input: ByteArray,
    var position: Int,
    protected val end: Int,
    private val format: String,
) {
    private var buffer = 0
    var bitsLeft = 0
        private set
    private var overrun = 0

    fun fail(message: String): Nothing = throw ArchiveFormatException("$format: $message", position.toLong())

    fun ensure(count: Int) {
        while (bitsLeft < count) {
            val word = when {
                position + 1 < end -> (input[position].toInt() and 0xFF) or ((input[position + 1].toInt() and 0xFF) shl 8)
                position < end -> input[position].toInt() and 0xFF
                ++overrun <= MAX_OVERRUN -> 0
                else -> fail("compressed data ends early")
            }
            position += 2
            buffer = buffer or (word shl (16 - bitsLeft))
            bitsLeft += 16
        }
    }

    fun peek(count: Int): Int = buffer ushr (32 - count)

    fun remove(count: Int) {
        buffer = if (count == 32) 0 else buffer shl count
        bitsLeft -= count
    }

    fun bits(count: Int): Int {
        if (count == 0) return 0
        ensure(count)
        val value = peek(count)
        remove(count)
        return value
    }

    /** Forget the loaded bits; the next read starts at [position]. */
    fun clearBits() {
        bitsLeft = 0
        buffer = 0
    }

    fun rawByte(): Int {
        if (position >= end) fail("data ends early")
        return input[position++].toInt() and 0xFF
    }

    private companion object {
        const val MAX_OVERRUN = 4
    }
}

/**
 * A canonical prefix code of codes up to [maxLength] bits, read from an [Msb16Reader]: a
 * direct table for short codes and a canonical walk for long ones. Each symbol first loads
 * [maxLength] bits, which decides where whole bytes after the bits are read. Only complete
 * codes and the empty code (all lengths zero, which fails when used) are accepted.
 */
internal class PrefixCode(private val size: Int, slack: Int = 0, private val maxLength: Int = MAX_CODE) {
    /** Code lengths; formats with delta-coded lengths keep them between blocks. */
    val lengths = IntArray(size + slack)
    private val table = IntArray(1 shl TABLE_BITS)
    private val counts = IntArray(MAX_CODE + 1)
    private val firstCode = IntArray(MAX_CODE + 2)
    private val firstIndex = IntArray(MAX_CODE + 2)
    private val sorted = IntArray(size)
    private var empty = true

    fun build(reader: Msb16Reader, name: String) {
        counts.fill(0)
        for (i in 0 until size) {
            val l = lengths[i]
            if (l > maxLength) reader.fail("$name code length $l")
            counts[l]++
        }
        counts[0] = 0
        empty = counts.all { it == 0 }
        if (empty) return
        var left = 1
        for (l in 1..MAX_CODE) {
            left = (left shl 1) - counts[l]
            if (left < 0) reader.fail("$name code is oversubscribed")
        }
        if (left != 0) reader.fail("$name code is incomplete")
        var code = 0
        var index = 0
        for (l in 1..MAX_CODE) {
            firstCode[l] = code
            firstIndex[l] = index
            index += counts[l]
            code = (code + counts[l]) shl 1
        }
        val next = firstIndex.copyOf()
        for (s in 0 until size) if (lengths[s] != 0) sorted[next[lengths[s]]++] = s
        table.fill(-1)
        for (l in 1..TABLE_BITS) {
            for (k in 0 until counts[l]) {
                val s = sorted[firstIndex[l] + k]
                val start = (firstCode[l] + k) shl (TABLE_BITS - l)
                table.fill((s shl 5) or l, start, start + (1 shl (TABLE_BITS - l)))
            }
        }
    }

    fun decode(reader: Msb16Reader, name: String): Int {
        if (empty) reader.fail("$name code is empty but used")
        reader.ensure(maxLength)
        val window = reader.peek(maxLength)
        val entry = table[window ushr (maxLength - TABLE_BITS)]
        if (entry >= 0) {
            reader.remove(entry and 31)
            return entry ushr 5
        }
        for (l in TABLE_BITS + 1..maxLength) {
            val offset = (window ushr (maxLength - l)) - firstCode[l]
            if (offset in 0 until counts[l]) {
                reader.remove(l)
                return sorted[firstIndex[l] + offset]
            }
        }
        reader.fail("invalid $name code")
    }

    private companion object {
        const val MAX_CODE = 16
        const val TABLE_BITS = 10
    }
}
