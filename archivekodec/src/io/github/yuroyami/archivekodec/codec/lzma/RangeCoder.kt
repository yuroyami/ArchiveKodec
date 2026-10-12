package io.github.yuroyami.archivekodec.codec.lzma

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/** Probabilities are 11-bit, start at one half and adapt by a shift of 5, as LZMA defines. */
internal const val PROB_BITS = 11
internal const val PROB_INIT: Short = (1 shl (PROB_BITS - 1)).toShort()
private const val MOVE_BITS = 5
private const val TOP = 1 shl 24

/** Fresh probability models, each at one half. */
internal fun probabilities(size: Int): ShortArray = ShortArray(size) { PROB_INIT }

/**
 * LZMA's binary range decoder over `input[start until end]`. Range and code are unsigned
 * 32-bit values held in Ints. The model drives it through [bit], [bitTree],
 * [reverseBitTree] and [directBits], so LZMA, LZMA2 and PPMd can share it.
 */
internal class RangeDecoder(private val input: ByteArray, start: Int, private val end: Int) {
    var position = start
        private set
    private var range = -1
    private var code = 0

    init {
        // The first byte is always zero; the next four seed the code.
        if (end - position < 5) throw ArchiveFormatException("LZMA: truncated range coder header", position.toLong())
        if (input[position++].toInt() != 0) throw ArchiveFormatException("LZMA: nonzero range coder first byte", position - 1L)
        repeat(4) { code = code shl 8 or (input[position++].toInt() and 0xFF) }
        if (code == range) throw ArchiveFormatException("LZMA: invalid range coder start", position.toLong())
    }

    /** True when the stream ended cleanly: no pending code. */
    val isFinishedOk: Boolean get() = code == 0

    private fun normalize() {
        if (Integer.compareUnsigned(range, TOP) < 0) {
            if (position >= end) throw ArchiveFormatException("LZMA: truncated compressed data", position.toLong())
            range = range shl 8
            code = code shl 8 or (input[position++].toInt() and 0xFF)
        }
    }

    fun bit(probs: ShortArray, index: Int): Int {
        val p = probs[index].toInt()
        val bound = (range ushr PROB_BITS) * p
        return if (Integer.compareUnsigned(code, bound) < 0) {
            range = bound
            probs[index] = (p + (((1 shl PROB_BITS) - p) ushr MOVE_BITS)).toShort()
            normalize()
            0
        } else {
            range -= bound
            code -= bound
            probs[index] = (p - (p ushr MOVE_BITS)).toShort()
            normalize()
            1
        }
    }

    fun directBits(count: Int): Int {
        var result = 0
        repeat(count) {
            range = range ushr 1
            code -= range
            val t = -(code ushr 31) // all ones when code went negative
            code += range and t
            if (code == range) throw ArchiveFormatException("LZMA: corrupted direct bits", position.toLong())
            normalize()
            result = result shl 1 or (t + 1)
        }
        return result
    }

    fun bitTree(probs: ShortArray, offset: Int, bits: Int): Int {
        var m = 1
        repeat(bits) { m = m shl 1 or bit(probs, offset + m) }
        return m - (1 shl bits)
    }

    fun reverseBitTree(probs: ShortArray, offset: Int, bits: Int): Int {
        var m = 1
        var symbol = 0
        for (i in 0 until bits) {
            val b = bit(probs, offset + m)
            m = m shl 1 or b
            symbol = symbol or (b shl i)
        }
        return symbol
    }
}

/** LZMA's binary range encoder, the inverse of [RangeDecoder]. */
internal class RangeEncoder(private val out: ByteArrayBuilder) {
    private var low = 0L
    private var range = -1
    private var cache = 0
    private var cacheSize = 1L

    /** Bytes this encoder will have written after [flush]. */
    val pendingSize: Long get() = out.size + cacheSize + 5

    private fun shiftLow() {
        if (low < 0xFF000000L || low > 0xFFFFFFFFL) {
            val carry = (low ushr 32).toInt()
            var temp = cache
            do {
                out.append((temp + carry).toByte())
                temp = 0xFF
            } while (--cacheSize != 0L)
            cache = (low ushr 24).toInt() and 0xFF
        }
        cacheSize++
        low = (low and 0x00FFFFFFL) shl 8
    }

    fun bit(probs: ShortArray, index: Int, bit: Int) {
        val p = probs[index].toInt()
        val bound = (range ushr PROB_BITS) * p
        if (bit == 0) {
            range = bound
            probs[index] = (p + (((1 shl PROB_BITS) - p) ushr MOVE_BITS)).toShort()
        } else {
            low += bound.toLong() and 0xFFFFFFFFL
            range -= bound
            probs[index] = (p - (p ushr MOVE_BITS)).toShort()
        }
        while (Integer.compareUnsigned(range, TOP) < 0) {
            range = range shl 8
            shiftLow()
        }
    }

    fun directBits(value: Int, count: Int) {
        for (i in count - 1 downTo 0) {
            range = range ushr 1
            if ((value ushr i) and 1 == 1) low += range.toLong() and 0xFFFFFFFFL
            while (Integer.compareUnsigned(range, TOP) < 0) {
                range = range shl 8
                shiftLow()
            }
        }
    }

    fun bitTree(probs: ShortArray, offset: Int, bits: Int, symbol: Int) {
        var m = 1
        for (i in bits - 1 downTo 0) {
            val b = (symbol ushr i) and 1
            bit(probs, offset + m, b)
            m = m shl 1 or b
        }
    }

    fun reverseBitTree(probs: ShortArray, offset: Int, bits: Int, symbol: Int) {
        var m = 1
        for (i in 0 until bits) {
            val b = (symbol ushr i) and 1
            bit(probs, offset + m, b)
            m = m shl 1 or b
        }
    }

    fun flush() {
        repeat(5) { shiftLow() }
    }
}

/** Portable unsigned compare, since common Kotlin has no `Integer.compareUnsigned`. */
private object Integer {
    @Suppress("NOTHING_TO_INLINE")
    inline fun compareUnsigned(a: Int, b: Int): Int = (a xor Int.MIN_VALUE).compareTo(b xor Int.MIN_VALUE)
}
