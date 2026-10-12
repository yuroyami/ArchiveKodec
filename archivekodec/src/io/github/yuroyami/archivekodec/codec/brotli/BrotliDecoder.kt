package io.github.yuroyami.archivekodec.codec.brotli

import io.github.yuroyami.archivekodec.codec.lzma.LzOutput
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/**
 * Brotli decoder (RFC 7932): uncompressed, metadata and compressed meta-blocks with
 * context modelling, block switching and static dictionary references. The whole output
 * is kept, so the window is only a distance bound. Exactly one stream is accepted: its
 * padding must be zero and no bytes may follow it.
 */
internal object BrotliDecoder {
    private val CODE_LENGTH_ORDER = intArrayOf(1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15)
    private val CODE_LENGTH_PREFIX_LENGTH = intArrayOf(2, 2, 2, 3, 2, 2, 2, 4, 2, 2, 2, 3, 2, 2, 2, 4)
    private val CODE_LENGTH_PREFIX_VALUE = intArrayOf(0, 4, 3, 2, 0, 4, 3, 1, 0, 4, 3, 2, 0, 4, 3, 5)

    private val BLOCK_LENGTH_BASE = intArrayOf(
        1, 5, 9, 13, 17, 25, 33, 41, 49, 65, 81, 97, 113, 145, 177, 209, 241, 305, 369, 497,
        753, 1265, 2289, 4337, 8433, 16625,
    )
    private val BLOCK_LENGTH_BITS = intArrayOf(2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 7, 8, 9, 10, 11, 12, 13, 24)

    private val INSERT_BASE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 8, 10, 14, 18, 26, 34, 50, 66, 98, 130, 194, 322, 578, 1090, 2114, 6210, 22594,
    )
    private val INSERT_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 24)
    private val COPY_BASE = intArrayOf(
        2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 18, 22, 30, 38, 54, 70, 102, 134, 198, 326, 582, 1094, 2118,
    )
    private val COPY_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 24)
    private val CELL_INSERT = intArrayOf(0, 0, 0, 0, 8, 8, 0, 16, 8, 16, 16)
    private val CELL_COPY = intArrayOf(0, 8, 0, 8, 0, 8, 16, 0, 16, 8, 16)

    /** Ring-buffer slot and delta for the 16 short distance codes (RFC 7932 section 4). */
    private val SHORT_SLOT = intArrayOf(0, 1, 2, 3, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1)
    private val SHORT_DELTA = intArrayOf(0, 0, 0, 0, -1, 1, -2, 2, -3, 3, -1, 1, -2, 2, -3, 3)

    private const val MAX_DISTANCE = 0x7FFFFFFC

    fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        val bits = BrotliBits(input, 0, input.size)
        val out = LzOutput(limits, input.size)
        val windowBits = readWindowBits(bits)
        val maxBackward = (1 shl windowBits) - 16
        val distances = intArrayOf(16, 15, 11, 4)
        var distanceIndex = 3 // distances[distanceIndex] is the last distance
        while (true) {
            val last = bits.read(1) == 1
            if (last && bits.read(1) == 1) break
            val nibbles = bits.read(2)
            if (nibbles == 3) {
                if (bits.read(1) != 0) bits.fail("reserved metadata bit is set")
                val skipBytes = bits.read(2)
                var skip = 0
                for (i in 0 until skipBytes) {
                    val byte = bits.read(8)
                    if (i + 1 == skipBytes && skipBytes > 1 && byte == 0) bits.fail("metadata length has a zero high byte")
                    skip = skip or (byte shl (8 * i))
                }
                if (skipBytes > 0) skip++
                bits.alignToByte()
                bits.resumeAt(bits.releaseBytes() + skip)
                if (last) break
                continue
            }
            var length = 0
            val count = nibbles + 4
            for (i in 0 until count) {
                val nibble = bits.read(4)
                if (i + 1 == count && count > 4 && nibble == 0) bits.fail("meta-block length has a zero high nibble")
                length = length or (nibble shl (4 * i))
            }
            length++
            if (!last && bits.read(1) == 1) {
                bits.alignToByte()
                val from = bits.releaseBytes()
                if (length > input.size - from) bits.fail("truncated uncompressed meta-block")
                out.put(input, from, length)
                bits.resumeAt(from + length)
                continue
            }
            distanceIndex = MetaBlock(bits, out, length, maxBackward, distances, distanceIndex).decode()
            if (last) break
        }
        bits.alignToByte()
        if (!bits.atEnd) bits.fail("data follows the end of the stream")
        return out.take()
    }

    private fun readWindowBits(bits: BrotliBits): Int {
        if (bits.read(1) == 0) return 16
        val n = bits.read(3)
        if (n != 0) return 17 + n
        val m = bits.read(3)
        if (m == 1) bits.fail("large-window streams are not supported")
        return if (m == 0) 17 else 8 + m
    }

    private fun readVarLenUint8(bits: BrotliBits): Int {
        if (bits.read(1) == 0) return 0
        val n = bits.read(3)
        return if (n == 0) 1 else (1 shl n) + bits.read(n)
    }

    /** Read a prefix code over [alphabetSize] symbols (RFC 7932 section 3.4 and 3.5). */
    private fun readPrefixCode(bits: BrotliBits, alphabetSize: Int): BrotliPrefixCode {
        val hskip = bits.read(2)
        val lengths = IntArray(alphabetSize)
        if (hskip == 1) {
            val symbolBits = 32 - (alphabetSize - 1).countLeadingZeroBits()
            val nsym = bits.read(2) + 1
            val symbols = IntArray(nsym) {
                bits.read(symbolBits).also { if (it >= alphabetSize) bits.fail("simple prefix code symbol $it out of range") }
            }
            for (i in 0 until nsym) for (j in i + 1 until nsym) {
                if (symbols[i] == symbols[j]) bits.fail("simple prefix code repeats symbol ${symbols[i]}")
            }
            when (nsym) {
                1 -> return BrotliPrefixCode.single(symbols[0])
                2 -> { lengths[symbols[0]] = 1; lengths[symbols[1]] = 1 }
                3 -> { lengths[symbols[0]] = 1; lengths[symbols[1]] = 2; lengths[symbols[2]] = 2 }
                else -> if (bits.read(1) == 0) {
                    for (s in symbols) lengths[s] = 2
                } else {
                    lengths[symbols[0]] = 1; lengths[symbols[1]] = 2; lengths[symbols[2]] = 3; lengths[symbols[3]] = 3
                }
            }
            return BrotliPrefixCode.build(lengths)
        }

        val codeLengthLengths = IntArray(18)
        var space = 32
        var codes = 0
        for (i in hskip until 18) {
            val window = bits.peek(4)
            bits.drop(CODE_LENGTH_PREFIX_LENGTH[window])
            val value = CODE_LENGTH_PREFIX_VALUE[window]
            codeLengthLengths[CODE_LENGTH_ORDER[i]] = value
            if (value != 0) {
                space -= 32 shr value
                codes++
                if (space <= 0) break
            }
        }
        if (codes != 1 && space != 0) bits.fail("invalid code length code")
        val codeLengthCode = BrotliPrefixCode.build(codeLengthLengths)

        var symbol = 0
        var previous = 8
        var repeat = 0
        var repeatLength = 0
        var remaining = 32768
        while (symbol < alphabetSize && remaining > 0) {
            val code = codeLengthCode.decode(bits)
            if (code < 16) {
                repeat = 0
                lengths[symbol++] = code
                if (code != 0) {
                    previous = code
                    remaining -= 32768 shr code
                }
                continue
            }
            val extraBits = if (code == 16) 2 else 3
            val newLength = if (code == 16) previous else 0
            if (repeatLength != newLength) { repeat = 0; repeatLength = newLength }
            val oldRepeat = repeat
            if (repeat > 0) repeat = (repeat - 2) shl extraBits
            repeat += bits.read(extraBits) + 3
            val delta = repeat - oldRepeat
            if (delta > alphabetSize - symbol) bits.fail("code lengths overflow the alphabet")
            repeat(delta) { lengths[symbol++] = repeatLength }
            if (repeatLength != 0) remaining -= delta shl (15 - repeatLength)
        }
        if (remaining != 0) bits.fail("incomplete prefix code")
        return BrotliPrefixCode.build(lengths)
    }

    private fun readBlockLength(bits: BrotliBits, code: BrotliPrefixCode): Int {
        val symbol = code.decode(bits)
        return BLOCK_LENGTH_BASE[symbol] + bits.read(BLOCK_LENGTH_BITS[symbol])
    }

    /** Block types and lengths of one category, with the switch codes when there are several types. */
    private class BlockSwitch(bits: BrotliBits) {
        val types = readVarLenUint8(bits) + 1
        private val typeCode = if (types >= 2) readPrefixCode(bits, types + 2) else null
        private val lengthCode = if (types >= 2) readPrefixCode(bits, 26) else null
        var type = 0
            private set
        private var previousType = 1
        var remaining = if (lengthCode != null) readBlockLength(bits, lengthCode) else Int.MAX_VALUE

        fun next(bits: BrotliBits) {
            if (remaining == 0) {
                val symbol = typeCode!!.decode(bits)
                var next = when (symbol) {
                    0 -> previousType
                    1 -> type + 1
                    else -> symbol - 2
                }
                if (next >= types) next -= types
                previousType = type
                type = next
                remaining = readBlockLength(bits, lengthCode!!)
            }
            remaining--
        }
    }

    private fun readContextMap(bits: BrotliBits, size: Int, trees: Int): IntArray {
        val map = IntArray(size)
        if (trees < 2) return map
        val rleMax = if (bits.read(1) == 1) bits.read(4) + 1 else 0
        val code = readPrefixCode(bits, trees + rleMax)
        var i = 0
        while (i < size) {
            val symbol = code.decode(bits)
            when {
                symbol == 0 -> map[i++] = 0
                symbol <= rleMax -> {
                    val run = (1 shl symbol) + bits.read(symbol)
                    if (run > size - i) bits.fail("context map run passes its end")
                    i += run
                }
                else -> map[i++] = symbol - rleMax
            }
        }
        if (bits.read(1) == 1) {
            val mtf = IntArray(256) { it }
            for (k in map.indices) {
                val index = map[k]
                val value = mtf[index]
                map[k] = value
                mtf.copyInto(mtf, 1, 0, index)
                mtf[0] = value
            }
        }
        return map
    }

    private class MetaBlock(
        private val bits: BrotliBits,
        private val out: LzOutput,
        private var remaining: Int,
        private val maxBackward: Int,
        private val distances: IntArray,
        private var distanceIndex: Int,
    ) {
        fun decode(): Int {
            val literalBlocks = BlockSwitch(bits)
            val commandBlocks = BlockSwitch(bits)
            val distanceBlocks = BlockSwitch(bits)
            val postfix = bits.read(2)
            val direct = bits.read(4) shl postfix
            val postfixMask = (1 shl postfix) - 1
            val modes = IntArray(literalBlocks.types) { bits.read(2) }
            val literalTrees = readVarLenUint8(bits) + 1
            val literalMap = readContextMap(bits, 64 * literalBlocks.types, literalTrees)
            val distanceTrees = readVarLenUint8(bits) + 1
            val distanceMap = readContextMap(bits, 4 * distanceBlocks.types, distanceTrees)
            val literalCodes = Array(literalTrees) { readPrefixCode(bits, 256) }
            val commandCodes = Array(commandBlocks.types) { readPrefixCode(bits, 704) }
            val distanceCodes = Array(distanceTrees) { readPrefixCode(bits, 16 + direct + (48 shl postfix)) }

            while (remaining > 0) {
                commandBlocks.next(bits)
                val command = commandCodes[commandBlocks.type].decode(bits)
                val cell = command ushr 6
                val insertCode = CELL_INSERT[cell] + ((command ushr 3) and 7)
                val copyCode = CELL_COPY[cell] + (command and 7)
                val insert = INSERT_BASE[insertCode] + bits.read(INSERT_BITS[insertCode])
                val copy = COPY_BASE[copyCode] + bits.read(COPY_BITS[copyCode])
                if (insert > remaining) bits.fail("insert length passes the meta-block end")
                repeat(insert) {
                    literalBlocks.next(bits)
                    val size = out.size
                    val p1 = if (size > 0) out.back(0) else 0
                    val p2 = if (size > 1) out.back(1) else 0
                    val type = literalBlocks.type
                    val context = when (modes[type]) {
                        0 -> p1 and 0x3F
                        1 -> p1 ushr 2
                        2 -> BROTLI_UTF8_CONTEXT[p1] or BROTLI_UTF8_CONTEXT[256 + p2]
                        else -> (signedContext(p1) shl 3) or signedContext(p2)
                    }
                    out.put(literalCodes[literalMap[64 * type + context]].decode(bits))
                }
                remaining -= insert
                if (remaining == 0) break

                val distanceCode: Int
                if (cell < 2) {
                    distanceCode = 0
                } else {
                    distanceBlocks.next(bits)
                    val context = if (copy > 4) 3 else copy - 2
                    distanceCode = distanceCodes[distanceMap[4 * distanceBlocks.type + context]].decode(bits)
                }
                val distance = distance(distanceCode, direct, postfix, postfixMask)
                val maxDistance = minOf(out.size, maxBackward)
                if (distance > maxDistance) {
                    if (distance > MAX_DISTANCE) bits.fail("invalid distance")
                    val written = dictionaryWord(copy, distance - maxDistance - 1)
                    if (written > remaining) bits.fail("dictionary word passes the meta-block end")
                    remaining -= written
                } else {
                    if (copy > remaining) bits.fail("copy length passes the meta-block end")
                    out.copy(distance - 1, copy)
                    remaining -= copy
                    if (distanceCode != 0) {
                        distanceIndex = (distanceIndex + 1) and 3
                        distances[distanceIndex] = distance
                    }
                }
            }
            return distanceIndex
        }

        private fun signedContext(byte: Int): Int = when {
            byte == 0 -> 0
            byte < 16 -> 1
            byte < 64 -> 2
            byte < 128 -> 3
            byte < 192 -> 4
            byte < 240 -> 5
            byte < 255 -> 6
            else -> 7
        }

        private fun distance(code: Int, direct: Int, postfix: Int, postfixMask: Int): Int {
            if (code < 16) {
                val value = distances[(distanceIndex - SHORT_SLOT[code]) and 3] + SHORT_DELTA[code]
                if (value <= 0) bits.fail("distance code $code gives distance $value")
                return value
            }
            if (code < 16 + direct) return code - 15
            val adjusted = code - direct - 16
            val extraBits = 1 + (adjusted ushr (postfix + 1))
            val high = (adjusted ushr postfix) and 1
            val offset = ((2 + high) shl extraBits) - 4
            return ((offset + bits.read(extraBits)) shl postfix) + (adjusted and postfixMask) + direct + 1
        }

        /** Write a transformed static dictionary word and return its length. */
        private fun dictionaryWord(length: Int, address: Int): Int {
            if (length < 4 || length > 24) bits.fail("dictionary reference with length $length")
            val shift = BrotliDictionary.SIZE_BITS[length]
            val transform = address ushr shift
            if (transform >= BrotliTransforms.COUNT) bits.fail("dictionary transform $transform out of range")
            val start = BrotliDictionary.OFFSETS[length] + (address and ((1 shl shift) - 1)) * length
            val word = BrotliDictionary.data.copyOfRange(start, start + length)
            val prefix = BrotliTransforms.PREFIXES[transform]
            val suffix = BrotliTransforms.SUFFIXES[transform]
            val type = BrotliTransforms.TYPES[transform]
            var from = 0
            var to = length
            if (type in 1..9) to -= type
            if (type >= BrotliTransforms.OMIT_FIRST_1) from = minOf(length, type - BrotliTransforms.OMIT_FIRST_1 + 1)
            if (to < from) to = from
            if (type == BrotliTransforms.UPPERCASE_FIRST) {
                uppercase(word, 0, to)
            } else if (type == BrotliTransforms.UPPERCASE_ALL) {
                var i = 0
                while (i < to) i += uppercase(word, i, to)
            }
            out.put(prefix, 0, prefix.size)
            out.put(word, from, to - from)
            out.put(suffix, 0, suffix.size)
            return prefix.size + (to - from) + suffix.size
        }

        /** RFC 7932's simplified UTF-8 uppercasing of the character at [i]; changes past [end] are dropped. */
        private fun uppercase(word: ByteArray, i: Int, end: Int): Int {
            val c = word[i].toInt() and 0xFF
            if (c < 0xC0) {
                if (c in 'a'.code..'z'.code) word[i] = (c xor 32).toByte()
                return 1
            }
            if (c < 0xE0) {
                if (i + 1 < end) word[i + 1] = (word[i + 1].toInt() xor 32).toByte()
                return 2
            }
            if (i + 2 < end) word[i + 2] = (word[i + 2].toInt() xor 5).toByte()
            return 3
        }
    }
}
