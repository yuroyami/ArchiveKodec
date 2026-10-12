package io.github.yuroyami.archivekodec.codec.lzx

import io.github.yuroyami.archivekodec.ArchiveFormatException

/**
 * LZX decoder, as used by Microsoft cabinets and WIM images. An LZX stream is a series of
 * verbatim, aligned-offset and uncompressed blocks over a sliding window of 2^15 to 2^21
 * bytes; the bits are read most significant first from little-endian 16-bit words. x86
 * `E8` call operands are translated back from absolute to relative after decoding.
 *
 * Cabinets carry one stream per folder: it starts with an E8 header, and the output is cut
 * into 32 KiB frames at which the input realigns to 16 bits and which no match may cross.
 * WIM compresses each chunk separately, with E8 translation always on and a shorter block
 * size field.
 */
internal class Lzx private constructor(
    input: ByteArray,
    position: Int,
    end: Int,
    windowBits: Int,
    private val wim: Boolean,
) : Msb16Reader(input, position, end, "LZX") {
    private val windowSize = 1 shl windowBits
    private val windowBits = windowBits
    private val mainSymbols = 256 + 8 * positionSlots(windowBits)

    private val pretree = PrefixCode(PRETREE_SYMBOLS)
    // Length runs may overshoot a table's end; the slack absorbs them, as libmspack's does.
    private val main = PrefixCode(mainSymbols, LENGTH_SLACK)
    private val length = PrefixCode(LENGTH_SYMBOLS, LENGTH_SLACK)
    private val aligned = PrefixCode(ALIGNED_SYMBOLS)

    private var r0 = 1
    private var r1 = 1
    private var r2 = 1
    private var blockType = 0
    private var blockLength = 0
    private var blockRemaining = 0

    /** Drop the rest of the current 16-bit word, as at the end of a cabinet frame. */
    private fun realign() {
        if (bitsLeft > 0) ensure(16)
        val partial = bitsLeft and 15
        if (partial != 0) remove(partial)
    }

    /** Align for the bytes of an uncompressed block: 1 to 16 bits of padding. */
    private fun alignForBytes() {
        ensure(16)
        if (bitsLeft > 16) position -= 2
        clearBits()
    }

    private fun rawInt(): Int = rawByte() or (rawByte() shl 8) or (rawByte() shl 16) or (rawByte() shl 24)

    /** Read code lengths [first, last) of [tree] as deltas from its previous lengths, through a pretree. */
    private fun readLengths(tree: PrefixCode, first: Int, last: Int) {
        for (i in 0 until PRETREE_SYMBOLS) pretree.lengths[i] = bits(4)
        pretree.build(this, "pretree")
        val lengths = tree.lengths
        var x = first
        while (x < last) {
            when (val z = pretree.decode(this, "pretree")) {
                17 -> repeat(bits(4) + 4) { set(lengths, x++, 0) }
                18 -> repeat(bits(5) + 20) { set(lengths, x++, 0) }
                19 -> {
                    val run = bits(1) + 4
                    val delta = pretree.decode(this, "pretree")
                    if (delta > 16) fail("invalid pretree run")
                    val value = (lengths[x] - delta + 17) % 17
                    repeat(run) { set(lengths, x++, value) }
                }
                else -> { lengths[x] = (lengths[x] - z + 17) % 17; x++ }
            }
        }
    }

    private fun set(lengths: IntArray, at: Int, value: Int) {
        if (at >= lengths.size) fail("code length run overruns its table")
        lengths[at] = value
    }

    // ---- Blocks ----

    private fun readBlockHeader() {
        if (blockType == UNCOMPRESSED && blockLength and 1 != 0) rawByte() // pad to 16 bits
        blockType = bits(3)
        blockLength = if (wim) {
            if (bits(1) == 1) DEFAULT_BLOCK else if (windowBits >= 16) (bits(16) shl 8) or bits(8) else bits(16)
        } else {
            (bits(16) shl 8) or bits(8)
        }
        if (blockLength == 0) fail("empty block")
        when (blockType) {
            VERBATIM, ALIGNED -> {
                if (blockType == ALIGNED) {
                    for (i in 0 until ALIGNED_SYMBOLS) aligned.lengths[i] = bits(3)
                    aligned.build(this, "aligned offset")
                }
                readLengths(main, 0, 256)
                readLengths(main, 256, mainSymbols)
                main.build(this, "main")
                readLengths(length, 0, LENGTH_SYMBOLS)
                length.build(this, "length")
            }
            UNCOMPRESSED -> {
                alignForBytes()
                r0 = rawInt(); r1 = rawInt(); r2 = rawInt()
                if (r0 == 0 || r1 == 0 || r2 == 0) fail("repeated offset of zero")
            }
            else -> fail("invalid block type $blockType")
        }
        blockRemaining = blockLength
    }

    /** Decode [out] from [start] until [stop], which must end a block run or frame. */
    private fun decodeRun(out: ByteArray, start: Int, stop: Int): Int {
        var at = start
        if (blockType == UNCOMPRESSED) {
            val n = stop - start
            if (n > end - position) fail("uncompressed block ends early")
            input.copyInto(out, at, position, position + n)
            position += n
            return stop
        }
        while (at < stop) {
            val symbol = main.decode(this, "main")
            if (symbol < 256) {
                out[at++] = symbol.toByte()
                continue
            }
            val header = symbol - 256
            var matchLength = header and 7
            if (matchLength == 7) matchLength += length.decode(this, "length")
            matchLength += 2
            val slot = header ushr 3
            val offset: Int
            when (slot) {
                0 -> offset = r0
                1 -> { offset = r1; r1 = r0; r0 = offset }
                2 -> { offset = r2; r2 = r0; r0 = offset }
                else -> {
                    val extra = EXTRA_BITS[slot]
                    var value = POSITION_BASE[slot] - 2
                    if (blockType == ALIGNED && extra >= 3) {
                        value += (bits(extra - 3) shl 3) + aligned.decode(this, "aligned offset")
                    } else {
                        value += bits(extra)
                    }
                    offset = if (slot == 3) 1 else value
                    r2 = r1; r1 = r0; r0 = offset
                }
            }
            if (offset > at || offset > windowSize) fail("match offset $offset reaches before the stream")
            if (matchLength > out.size - at) fail("match runs past the end of the output")
            var from = at - offset
            repeat(matchLength) { out[at++] = out[from++] }
        }
        return at
    }

    /** Decode [out] from [start] to [frameEnd], crossing block boundaries as needed. */
    private fun decodeFrame(out: ByteArray, start: Int, frameEnd: Int) {
        var at = start
        while (at < frameEnd) {
            if (blockRemaining == 0) readBlockHeader()
            val stop = at + minOf(blockRemaining, frameEnd - at)
            val reached = decodeRun(out, at, stop)
            if (reached > frameEnd) fail("match crosses the end of a frame")
            if (reached - at > blockRemaining) fail("match crosses the end of a block")
            blockRemaining -= reached - at
            at = reached
        }
    }

    companion object {
        private const val VERBATIM = 1
        private const val ALIGNED = 2
        private const val UNCOMPRESSED = 3
        private const val FRAME = 32768
        private const val DEFAULT_BLOCK = 32768
        private const val PRETREE_SYMBOLS = 20
        private const val LENGTH_SYMBOLS = 249
        private const val ALIGNED_SYMBOLS = 8
        private const val LENGTH_SLACK = 64
        private const val WIM_E8_SIZE = 12_000_000

        private val EXTRA_BITS = IntArray(52).also {
            var j = 0
            for (i in 0 until 52 step 2) {
                it[i] = j; it[i + 1] = j
                if (i != 0 && j < 17) j++
            }
        }
        private val POSITION_BASE = IntArray(52).also {
            var j = 0
            for (i in 0 until 52) { it[i] = j; j += 1 shl EXTRA_BITS[i] }
        }

        /** Position slots for a 2^[windowBits] window, which set the main code's size. */
        fun positionSlots(windowBits: Int): Int = when (windowBits) {
            15 -> 30; 16 -> 32; 17 -> 34; 18 -> 36; 19 -> 38; 20 -> 42; 21 -> 50
            else -> throw ArchiveFormatException("LZX: window of 2^$windowBits bytes is not 2^15 to 2^21")
        }

        /**
         * Decode a cabinet folder's stream of [total] bytes, stopping after the frame that
         * reaches [wanted] bytes; the result holds the decoded frames.
         */
        fun decodeCabinet(input: ByteArray, windowBits: Int, total: Long, wanted: Long): ByteArray {
            val lzx = Lzx(input, 0, input.size, windowBits, wim = false)
            val e8Size = if (lzx.bits(1) == 1) (lzx.bits(16) shl 16) or lzx.bits(16) else 0
            val length = minOf(total, (wanted + FRAME - 1) / FRAME * FRAME).toInt()
            val out = ByteArray(length)
            var at = 0
            while (at < length) {
                val frameEnd = minOf(length, at + FRAME)
                lzx.decodeFrame(out, at, frameEnd)
                lzx.realign()
                at = frameEnd
            }
            if (e8Size != 0) {
                var frame = 0
                while (frame < length && frame / FRAME < 32768) {
                    val frameEnd = minOf(length, frame + FRAME)
                    undoE8(out, frame, frameEnd, e8Size)
                    frame = frameEnd
                }
            }
            return out
        }

        /** Decode one WIM chunk of [size] bytes, compressed alone in a 2^[windowBits] window. */
        fun decodeWimChunk(input: ByteArray, from: Int, to: Int, size: Int, windowBits: Int): ByteArray {
            val lzx = Lzx(input, from, to, windowBits, wim = true)
            val out = ByteArray(size)
            lzx.decodeFrame(out, 0, size)
            undoE8(out, 0, size, WIM_E8_SIZE)
            return out
        }

        /** Turn absolute `E8` call targets in [start, end) back into relative ones. */
        private fun undoE8(data: ByteArray, start: Int, end: Int, fileSize: Int) {
            if (end - start <= 10) return
            var i = start
            val tail = end - 10
            while (i < tail) {
                if (data[i] != 0xE8.toByte()) { i++; continue }
                val current = i
                val absolute = (data[i + 1].toInt() and 0xFF) or ((data[i + 2].toInt() and 0xFF) shl 8) or
                    ((data[i + 3].toInt() and 0xFF) shl 16) or (data[i + 4].toInt() shl 24)
                if (absolute >= -current && absolute < fileSize) {
                    val relative = if (absolute >= 0) absolute - current else absolute + fileSize
                    data[i + 1] = relative.toByte(); data[i + 2] = (relative ushr 8).toByte()
                    data[i + 3] = (relative ushr 16).toByte(); data[i + 4] = (relative ushr 24).toByte()
                }
                i += 5
            }
        }
    }
}
