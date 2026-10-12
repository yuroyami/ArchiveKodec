package io.github.yuroyami.archivekodec.codec.zstd

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.checksum.XxHash64
import io.github.yuroyami.archivekodec.codec.lzma.LzOutput
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.readI32Le

internal const val ZSTD_MAGIC = 0xFD2FB528.toInt()
internal const val MAX_BLOCK_SIZE = 128 * 1024

internal val LL_BASE = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 40,
    48, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536)
internal val LL_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3,
    4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
internal val ML_BASE = IntArray(53) { if (it < 32) it + 3 else 0 }.also {
    val tail = intArrayOf(35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771, 65539)
    tail.copyInto(it, 32)
}
internal val ML_BITS = IntArray(53).also {
    intArrayOf(1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16).copyInto(it, 32)
}
internal val LL_DEFAULT = intArrayOf(4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2,
    2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1)
internal val ML_DEFAULT = intArrayOf(1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1)
internal val OF_DEFAULT = intArrayOf(1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    -1, -1, -1, -1, -1)

/**
 * Zstandard decoder (RFC 8878): frames of raw, RLE and compressed blocks, with Huff0
 * literals, FSE-coded sequences, table reuse across blocks, repeat offsets and the
 * xxHash64 content checksum. Skippable frames are ignored and concatenated frames decode
 * as one output. Frames that name a dictionary are refused.
 */
internal object ZstdDecoder {
    private val LL_PREDEFINED = FseTable.build(LL_DEFAULT, 6)
    private val ML_PREDEFINED = FseTable.build(ML_DEFAULT, 6)
    private val OF_PREDEFINED = FseTable.build(OF_DEFAULT, 5)

    fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        val out = LzOutput(limits, input.size)
        var pos = 0
        var frames = 0
        while (pos < input.size || frames == 0) {
            if (input.size - pos < 4) throw ArchiveFormatException("zstd: truncated frame", pos.toLong())
            val magic = readI32Le(input, pos)
            if (magic and -0x10 == 0x184D2A50) {
                if (input.size - pos < 8) throw ArchiveFormatException("zstd: truncated skippable frame", pos.toLong())
                val size = readI32Le(input, pos + 4).toLong() and 0xFFFFFFFFL
                if (size > input.size - pos - 8L) throw ArchiveFormatException("zstd: truncated skippable frame", pos.toLong())
                pos += 8 + size.toInt()
            } else {
                if (magic != ZSTD_MAGIC) throw ArchiveFormatException("zstd: invalid frame magic", pos.toLong())
                pos = Frame(input, pos + 4, out, limits).decode()
            }
            frames++
        }
        return out.take()
    }

    private class Frame(private val input: ByteArray, private var pos: Int, private val out: LzOutput, private val limits: ArchiveLimits) {
        private val frameStart = out.size
        private var windowSize = 0L
        private val reps = intArrayOf(1, 4, 8)
        private var huffman: HuffmanTable? = null
        private var llTable: FseTable? = null
        private var ofTable: FseTable? = null
        private var mlTable: FseTable? = null

        private fun fail(message: String, at: Int = pos): Nothing = throw ArchiveFormatException("zstd: $message", at.toLong())
        private fun need(n: Int) { if (input.size - pos < n) fail("truncated frame") }
        private fun byte(): Int { need(1); return input[pos++].toInt() and 0xFF }
        private fun le(n: Int): Long { need(n); var v = 0L; for (i in n - 1 downTo 0) v = v shl 8 or (input[pos + i].toLong() and 0xFF); pos += n; return v }

        fun decode(): Int {
            val descriptor = byte()
            val fcsFlag = descriptor ushr 6
            val singleSegment = descriptor and 0x20 != 0
            if (descriptor and 0x08 != 0) fail("reserved frame header bit is set", pos - 1)
            val hasChecksum = descriptor and 0x04 != 0
            val dictionaryBytes = intArrayOf(0, 1, 2, 4)[descriptor and 3]
            if (!singleSegment) {
                val w = byte()
                val exponent = w ushr 3
                val base = 1L shl (10 + exponent)
                windowSize = base + (base / 8) * (w and 7)
            }
            val dictionaryId = le(dictionaryBytes)
            if (dictionaryId != 0L) throw ArchiveUnsupportedException("zstd: dictionary $dictionaryId is not supported", pos.toLong())
            val contentSize = when (fcsFlag) {
                0 -> if (singleSegment) le(1) else -1L
                1 -> le(2) + 256
                2 -> le(4)
                else -> le(8)
            }
            if (singleSegment) windowSize = contentSize
            if (contentSize >= 0) {
                limits.checkOutputSize(out.size - 0L + contentSize)
            } else if (contentSize < -1) fail("frame content size exceeds Long")
            val blockMaximum = minOf(windowSize, MAX_BLOCK_SIZE.toLong()).toInt()
            while (true) {
                val header = le(3).toInt()
                val last = header and 1 != 0
                val type = (header ushr 1) and 3
                val size = header ushr 3
                when (type) {
                    0 -> {
                        if (size > blockMaximum) fail("raw block exceeds the block maximum")
                        need(size)
                        out.put(input, pos, size)
                        pos += size
                    }
                    1 -> {
                        if (size > blockMaximum) fail("RLE block exceeds the block maximum")
                        val value = byte()
                        repeat(size) { out.put(value) }
                    }
                    2 -> {
                        if (size > blockMaximum) fail("compressed block exceeds the block maximum")
                        need(size)
                        compressedBlock(pos, pos + size, blockMaximum)
                        pos += size
                    }
                    else -> fail("reserved block type", pos - 3)
                }
                if (last) break
            }
            val produced = out.size - frameStart
            if (contentSize >= 0 && produced.toLong() != contentSize) fail("frame decoded $produced bytes, header says $contentSize")
            if (hasChecksum) {
                val stored = le(4)
                val actual = XxHash64.hash(out.bytes, frameStart, produced) and 0xFFFFFFFFL
                if (stored != actual) fail("content checksum mismatch", pos - 4)
            }
            return pos
        }

        private fun compressedBlock(start: Int, end: Int, blockMaximum: Int) {
            val blockOutputStart = out.size
            var p = start
            fun failAt(message: String): Nothing = fail(message, p)
            if (p >= end) failAt("empty compressed block")
            // Literals section.
            val b0 = input[p].toInt() and 0xFF
            val literalsType = b0 and 3
            val sizeFormat = (b0 ushr 2) and 3
            val literals: ByteArray
            if (literalsType < 2) {
                val (headerSize, regenerated) = when (sizeFormat) {
                    0, 2 -> 1 to (b0 ushr 3)
                    1 -> { if (end - p < 2) failAt("truncated literals header"); 2 to ((b0 ushr 4) + ((input[p + 1].toInt() and 0xFF) shl 4)) }
                    else -> { if (end - p < 3) failAt("truncated literals header"); 3 to ((b0 ushr 4) + ((input[p + 1].toInt() and 0xFF) shl 4) + ((input[p + 2].toInt() and 0xFF) shl 12)) }
                }
                p += headerSize
                if (regenerated > blockMaximum) failAt("literals exceed the block maximum")
                if (literalsType == 0) {
                    if (end - p < regenerated) failAt("truncated raw literals")
                    literals = input.copyOfRange(p, p + regenerated)
                    p += regenerated
                } else {
                    if (end - p < 1) failAt("truncated RLE literals")
                    literals = ByteArray(regenerated) { input[p] }
                    p++
                }
            } else {
                val headerSize = when (sizeFormat) { 0, 1 -> 3; 2 -> 4; else -> 5 }
                if (end - p < headerSize) failAt("truncated literals header")
                var value = 0L
                for (i in headerSize - 1 downTo 0) value = value shl 8 or (input[p + i].toLong() and 0xFF)
                val sizeBits = when (headerSize) { 3 -> 10; 4 -> 14; else -> 18 }
                val regenerated = ((value ushr 4) and ((1L shl sizeBits) - 1)).toInt()
                val compressed = ((value ushr (4 + sizeBits)) and ((1L shl sizeBits) - 1)).toInt()
                val streams = if (sizeFormat == 0) 1 else 4
                p += headerSize
                if (regenerated > blockMaximum) failAt("literals exceed the block maximum")
                if (end - p < compressed) failAt("truncated compressed literals")
                var q = p
                val literalsEnd = p + compressed
                if (literalsType == 2) {
                    val (table, used) = HuffmanTable.read(input, q, literalsEnd)
                    huffman = table
                    q += used
                }
                val table = huffman ?: failAt("treeless literals before any Huffman table")
                literals = ByteArray(regenerated)
                if (streams == 1) {
                    decodeStream(table, q, literalsEnd, literals, 0, regenerated)
                } else {
                    if (literalsEnd - q < 6) failAt("truncated literals jump table")
                    val s1 = (input[q].toInt() and 0xFF) or (input[q + 1].toInt() and 0xFF shl 8)
                    val s2 = (input[q + 2].toInt() and 0xFF) or (input[q + 3].toInt() and 0xFF shl 8)
                    val s3 = (input[q + 4].toInt() and 0xFF) or (input[q + 5].toInt() and 0xFF shl 8)
                    q += 6
                    if (s1 + s2 + s3 > literalsEnd - q) failAt("literals jump table exceeds the section")
                    val segment = (regenerated + 3) / 4
                    if (segment * 3 > regenerated) failAt("too few literals for four streams")
                    decodeStream(table, q, q + s1, literals, 0, segment)
                    decodeStream(table, q + s1, q + s1 + s2, literals, segment, segment)
                    decodeStream(table, q + s1 + s2, q + s1 + s2 + s3, literals, 2 * segment, segment)
                    decodeStream(table, q + s1 + s2 + s3, literalsEnd, literals, 3 * segment, regenerated - 3 * segment)
                }
                p = literalsEnd
            }

            // Sequences section.
            if (p >= end) failAt("missing sequences section")
            var count = input[p++].toInt() and 0xFF
            if (count >= 128) {
                if (count < 255) {
                    if (p >= end) failAt("truncated sequence count")
                    count = ((count - 128) shl 8) + (input[p++].toInt() and 0xFF)
                } else {
                    if (end - p < 2) failAt("truncated sequence count")
                    count = (input[p].toInt() and 0xFF) + ((input[p + 1].toInt() and 0xFF) shl 8) + 0x7F00
                    p += 2
                }
            }
            if (count == 0) {
                if (p != end) failAt("data after a block with no sequences")
                out.put(literals, 0, literals.size)
                return
            }
            if (p >= end) failAt("missing sequence compression modes")
            val modes = input[p++].toInt() and 0xFF
            if (modes and 3 != 0) failAt("reserved sequence mode bits are set")
            fun table(mode: Int, predefined: FseTable, previous: FseTable?, maxSymbol: Int, maxLog: Int): FseTable = when (mode) {
                0 -> predefined
                1 -> {
                    if (p >= end) failAt("truncated RLE sequence table")
                    val symbol = input[p++].toInt() and 0xFF
                    if (symbol > maxSymbol) failAt("RLE symbol $symbol exceeds $maxSymbol")
                    FseTable.rle(symbol)
                }
                2 -> FseTable.read(input, p, end, maxSymbol, maxLog).let { (t, used) -> p += used; t }
                else -> previous ?: failAt("repeat sequence table before any table")
            }
            val ll = table(modes ushr 6, LL_PREDEFINED, llTable, 35, 9).also { llTable = it }
            val of = table((modes ushr 4) and 3, OF_PREDEFINED, ofTable, 31, 8).also { ofTable = it }
            val ml = table((modes ushr 2) and 3, ML_PREDEFINED, mlTable, 52, 9).also { mlTable = it }

            val bits = BackwardBits(input, p, end)
            var llState = bits.read(ll.accuracyLog)
            var ofState = bits.read(of.accuracyLog)
            var mlState = bits.read(ml.accuracyLog)
            var literal = 0
            for (n in 0 until count) {
                val ofCode = of.symbols[ofState]
                val mlCode = ml.symbols[mlState]
                val llCode = ll.symbols[llState]
                if (ofCode > 31) failAt("offset code $ofCode is too large")
                if (llCode > 35 || mlCode > 52) failAt("invalid sequence code")
                val offsetValue = (1L shl ofCode) + bits.read(ofCode).toLong()
                val matchLength = ML_BASE[mlCode] + bits.read(ML_BITS[mlCode])
                val literalLength = LL_BASE[llCode] + bits.read(LL_BITS[llCode])
                val offset: Int
                if (offsetValue > 3) {
                    if (offsetValue - 3 > Int.MAX_VALUE) failAt("offset exceeds the output")
                    offset = (offsetValue - 3).toInt()
                    reps[2] = reps[1]; reps[1] = reps[0]; reps[0] = offset
                } else {
                    val index = (offsetValue - 1).toInt() + if (literalLength == 0) 1 else 0
                    if (index == 0) {
                        offset = reps[0]
                    } else {
                        offset = if (index == 3) reps[0] - 1 else reps[index]
                        if (offset <= 0) failAt("repeat offset reached zero")
                        if (index > 1) reps[2] = reps[1]
                        reps[1] = reps[0]
                        reps[0] = offset
                    }
                }
                if (n != count - 1) {
                    llState = ll.baselines[llState] + bits.read(ll.bits[llState])
                    mlState = ml.baselines[mlState] + bits.read(ml.bits[mlState])
                    ofState = of.baselines[ofState] + bits.read(of.bits[ofState])
                }
                if (bits.overflowed) failAt("sequence bitstream is too short")
                if (literalLength > literals.size - literal) failAt("sequence uses more literals than exist")
                out.put(literals, literal, literalLength)
                literal += literalLength
                val available = out.size - frameStart
                if (offset > available || offset > windowSize) failAt("match offset $offset is too far back")
                if (out.size - blockOutputStart + matchLength > blockMaximum) failAt("block output exceeds the block maximum")
                out.copy(offset - 1, matchLength)
            }
            if (!bits.finished) failAt("sequence bitstream has unused bits")
            out.put(literals, literal, literals.size - literal)
            if (out.size - blockOutputStart > blockMaximum) failAt("block output exceeds the block maximum")
        }

        private fun decodeStream(table: HuffmanTable, start: Int, end: Int, target: ByteArray, offset: Int, count: Int) {
            val bits = BackwardBits(input, start, end)
            for (i in offset until offset + count) {
                target[i] = table.decode(bits).toByte()
                if (bits.overflowed) fail("Huffman stream is too short", start)
            }
            if (!bits.finished) fail("Huffman stream has unused bits", start)
        }
    }
}
