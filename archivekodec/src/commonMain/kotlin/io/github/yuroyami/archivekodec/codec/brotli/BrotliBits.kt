package io.github.yuroyami.archivekodec.codec.brotli

import io.github.yuroyami.archivekodec.ArchiveFormatException

/** LSB-first bit reader over input[[start] until [end]); reading past the end fails. */
internal class BrotliBits(private val input: ByteArray, start: Int, private val end: Int) {
    private var position = start
    private var buffer = 0L
    private var count = 0

    /** Offset of the next unread byte, for error reports. */
    val offset: Long get() = (position - count / 8).toLong()

    fun fail(message: String): Nothing = throw ArchiveFormatException("brotli: $message", offset)

    private fun fill() {
        while (count <= 56 && position < end) {
            buffer = buffer or ((input[position++].toLong() and 0xFF) shl count)
            count += 8
        }
    }

    /** The next [n] bits (at most 32), zero-padded past the end of input. */
    fun peek(n: Int): Int {
        if (count < n) fill()
        return (buffer and ((1L shl n) - 1)).toInt()
    }

    fun drop(n: Int) {
        if (n > count) {
            fill()
            if (n > count) fail("truncated stream")
        }
        buffer = buffer ushr n
        count -= n
    }

    fun read(n: Int): Int {
        val value = peek(n)
        drop(n)
        return value
    }

    /** Skip to the next byte boundary; the skipped bits must be zero. */
    fun alignToByte() {
        val pad = count and 7
        if (read(pad) != 0) fail("nonzero padding bits")
    }

    /** After [alignToByte], hand back the buffered bytes and return the next byte offset. */
    fun releaseBytes(): Int {
        check(count and 7 == 0)
        position -= count / 8
        buffer = 0
        count = 0
        return position
    }

    /** Resume reading at byte [next], after bytes were consumed directly. */
    fun resumeAt(next: Int) {
        if (next > end) fail("truncated stream")
        position = next
    }

    val atEnd: Boolean get() = count == 0 && position == end
}

/**
 * A canonical prefix code as a two-level lookup table. Entries hold (length shl 16) or
 * symbol; a negative root entry points at a second-level table as -((offset shl 4) or bits).
 */
internal class BrotliPrefixCode private constructor(private val rootBits: Int, private val table: IntArray) {
    fun decode(bits: BrotliBits): Int {
        val window = bits.peek(15)
        var entry = table[window and ((1 shl rootBits) - 1)]
        if (entry < 0) {
            val pointer = -entry
            entry = table[(pointer ushr 4) + ((window ushr rootBits) and ((1 shl (pointer and 15)) - 1))]
        }
        bits.drop(entry ushr 16)
        return entry and 0xFFFF
    }

    companion object {
        private const val MAX_LENGTH = 15

        /** A code with one symbol, which takes no bits. */
        fun single(symbol: Int) = BrotliPrefixCode(0, intArrayOf(symbol))

        /** Build from code [lengths], which must form a complete code or name one symbol. */
        fun build(lengths: IntArray, size: Int = lengths.size): BrotliPrefixCode {
            val counts = IntArray(MAX_LENGTH + 1)
            var used = 0
            var only = 0
            for (symbol in 0 until size) {
                val length = lengths[symbol]
                if (length != 0) { counts[length]++; used++; only = symbol }
            }
            if (used == 1) return single(only)
            var longest = MAX_LENGTH
            while (longest > 0 && counts[longest] == 0) longest--
            val rootBits = minOf(8, longest)
            val rootSize = 1 shl rootBits
            val next = IntArray(MAX_LENGTH + 2)
            for (length in 2..MAX_LENGTH) next[length] = (next[length - 1] + counts[length - 1]) shl 1
            val reversed = IntArray(size)
            val widths = IntArray(rootSize)
            for (symbol in 0 until size) {
                val length = lengths[symbol]
                if (length == 0) continue
                var code = next[length]++
                var reverse = 0
                repeat(length) { reverse = (reverse shl 1) or (code and 1); code = code ushr 1 }
                reversed[symbol] = reverse
                if (length > rootBits) {
                    val prefix = reverse and (rootSize - 1)
                    widths[prefix] = maxOf(widths[prefix], length - rootBits)
                }
            }
            var total = rootSize
            for (width in widths) if (width > 0) total += 1 shl width
            val table = IntArray(total)
            var offset = rootSize
            for (prefix in widths.indices) {
                val width = widths[prefix]
                if (width > 0) {
                    table[prefix] = -((offset shl 4) or width)
                    offset += 1 shl width
                }
            }
            for (symbol in 0 until size) {
                val length = lengths[symbol]
                if (length == 0) continue
                val entry = (length shl 16) or symbol
                if (length <= rootBits) {
                    var index = reversed[symbol]
                    while (index < rootSize) { table[index] = entry; index += 1 shl length }
                } else {
                    val pointer = -table[reversed[symbol] and (rootSize - 1)]
                    val start = pointer ushr 4
                    val span = 1 shl (pointer and 15)
                    var index = reversed[symbol] ushr rootBits
                    while (index < span) { table[start + index] = entry; index += 1 shl (length - rootBits) }
                }
            }
            return BrotliPrefixCode(rootBits, table)
        }
    }
}
