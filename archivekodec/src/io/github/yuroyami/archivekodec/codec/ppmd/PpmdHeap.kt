package io.github.yuroyami.archivekodec.codec.ppmd

import io.github.yuroyami.archivekodec.ArchiveFormatException

/**
 * The memory both PPMd models live in. Contexts, symbol states and free-list nodes sit at
 * byte offsets in one array, laid out exactly as the reference allocator lays them out:
 * when memory runs out the model restarts or is pruned, so the allocator has to run out
 * at the same symbol as the encoder's did. Offset 0 is the null reference.
 *
 * A state is 6 bytes: symbol, frequency and a 32-bit successor. Every multi-byte field is
 * little-endian. Ported from Igor Pavlov's public-domain Ppmd7.c and Ppmd8.c (LZMA SDK),
 * which follow Dmitry Shkarin's PPMd var.H and var.I.
 */
internal abstract class PpmdHeap(val size: Int, extraBytes: Int, seeContexts: Int) {
    val alignOffset = 4 - (size and 3)
    val mem = ByteArray(alignOffset + size + extraBytes)

    val freeList = IntArray(NUM_INDEXES)
    var loUnit = 0
    var hiUnit = 0
    var text = 0
    var unitsStart = 0
    var glueCount = 0

    /** How often the model restarted and, for var.I, was cut off; tests check these paths ran. */
    var restarts = 0
    var cutOffs = 0

    // See contexts: Summ (16 bits), Shift and Count; the last one is the dummy.
    val seeSumm = IntArray(seeContexts + 1)
    val seeShift = IntArray(seeContexts + 1)
    val seeCountdown = IntArray(seeContexts + 1)
    val dummySee = seeContexts

    fun u8(o: Int): Int = mem[o].toInt() and 0xFF
    fun setU8(o: Int, v: Int) { mem[o] = v.toByte() }
    fun u16(o: Int): Int = (mem[o].toInt() and 0xFF) or ((mem[o + 1].toInt() and 0xFF) shl 8)
    fun setU16(o: Int, v: Int) { mem[o] = v.toByte(); mem[o + 1] = (v ushr 8).toByte() }
    fun u32(o: Int): Int = (mem[o].toInt() and 0xFF) or ((mem[o + 1].toInt() and 0xFF) shl 8) or
        ((mem[o + 2].toInt() and 0xFF) shl 16) or (mem[o + 3].toInt() shl 24)
    fun setU32(o: Int, v: Int) {
        mem[o] = v.toByte(); mem[o + 1] = (v ushr 8).toByte(); mem[o + 2] = (v ushr 16).toByte(); mem[o + 3] = (v ushr 24).toByte()
    }

    fun symbol(s: Int) = u8(s)
    fun freq(s: Int) = u8(s + 1)
    fun setFreq(s: Int, v: Int) = setU8(s + 1, v)
    fun successor(s: Int) = u32(s + 2)
    fun setSuccessor(s: Int, v: Int) = setU32(s + 2, v)
    fun copyState(to: Int, from: Int) { mem.copyInto(mem, to, from, from + 6) }

    private val swap = ByteArray(6)
    fun swapStates(a: Int, b: Int) {
        mem.copyInto(swap, 0, a, a + 6)
        mem.copyInto(mem, a, b, b + 6)
        swap.copyInto(mem, b)
    }

    fun copyUnits(to: Int, from: Int, units: Int) { mem.copyInto(mem, to, from, from + units * UNIT_SIZE) }

    fun seeUpdate(see: Int) {
        if (seeShift[see] < PERIOD_BITS && --seeCountdown[see] == 0) {
            seeSumm[see] = (seeSumm[see] shl 1) and 0xFFFF
            seeCountdown[see] = (3 shl seeShift[see]++) and 0xFF
        }
    }

    /** Escape frequency of [see], shrinking its sum as the reference does. */
    fun seeEscape(see: Int): Int {
        val r = seeSumm[see] ushr seeShift[see]
        seeSumm[see] = (seeSumm[see] - r) and 0xFFFF
        return r + (if (r == 0) 1 else 0)
    }

    fun resetDummySee() {
        seeShift[dummySee] = PERIOD_BITS
        seeSumm[dummySee] = 0
        seeCountdown[dummySee] = 64
    }

    companion object {
        const val UNIT_SIZE = 12
        const val NUM_INDEXES = 38
        const val MAX_FREQ = 124
        const val INT_BITS = 7
        const val PERIOD_BITS = 7
        const val BIN_SCALE = 1 shl (INT_BITS + PERIOD_BITS)
        val EXP_ESCAPE = intArrayOf(25, 14, 9, 7, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 2, 2)
        val INIT_BIN_ESC = intArrayOf(0x3CDD, 0x1F3F, 0x59BF, 0x48F3, 0x64A1, 0x5ABC, 0x6632, 0x6051)

        val INDEX_TO_UNITS = IntArray(NUM_INDEXES)
        val UNITS_TO_INDEX = IntArray(128)

        init {
            var k = 0
            for (i in 0 until NUM_INDEXES) {
                var step = if (i >= 12) 4 else (i shr 2) + 1
                do { UNITS_TO_INDEX[k++] = i } while (--step != 0)
                INDEX_TO_UNITS[i] = k
            }
        }

        fun i2u(index: Int) = INDEX_TO_UNITS[index]
        fun u2i(units: Int) = UNITS_TO_INDEX[units - 1]
        fun u2b(units: Int) = units * UNIT_SIZE

        fun probUpdate0(prob: Int) = prob + (1 shl INT_BITS) - ((prob + (1 shl (PERIOD_BITS - 2))) shr PERIOD_BITS)
        fun probUpdate1(prob: Int) = prob - ((prob + (1 shl (PERIOD_BITS - 2))) shr PERIOD_BITS)

        /** Unsigned 32-bit comparison a > b. */
        fun ugt(a: Int, b: Int) = (a xor Int.MIN_VALUE) > (b xor Int.MIN_VALUE)

        fun corrupt(): Nothing = throw ArchiveFormatException("PPMd: corrupt data")
    }
}

/**
 * Reads a compressed slice. The range coders read input in lockstep with what the encoder
 * wrote, flush included, so a read past the end means the stream is truncated.
 */
internal class PpmdInput(private val data: ByteArray, private var position: Int, private val end: Int) {
    fun next(): Int {
        if (position >= end) throw ArchiveFormatException("PPMd: truncated stream", end.toLong())
        return data[position++].toInt() and 0xFF
    }
}
