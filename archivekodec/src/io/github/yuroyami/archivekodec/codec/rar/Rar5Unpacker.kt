package io.github.yuroyami.archivekodec.codec.rar

import io.github.yuroyami.archivekodec.ArchiveFormatException

/**
 * RAR 5.0 decompressor (all methods; they differ only in how hard the encoder searched).
 * Data is a series of blocks, each with a small header, optional new prefix codes and an
 * LZ stream of literals, matches, repeated distances and filter definitions, read most
 * significant bit first. Delta, x86 (E8, E8/E9) and ARM filters run on the decoded file.
 *
 * One unpacker holds a solid stream: the window, codes, distance cache and last length carry
 * from one file to the next. [windowSize] must be a power of two at least the dictionary
 * size the stream may use, or the whole stream's size if that is smaller; [dictionary] is
 * the declared dictionary size, which bounds filter lengths.
 */
internal class Rar5Unpacker(private val windowSize: Int, private val dictionary: Long) {
    private val window = ByteArray(windowSize)
    private val mask = windowSize - 1
    private var written = 0L

    private val main = Code(MAIN_SYMBOLS)
    private val distance = Code(DISTANCE_SYMBOLS)
    private val lowDistance = Code(LOW_DISTANCE_SYMBOLS)
    private val repeat = Code(REPEAT_SYMBOLS)
    private var tablesRead = false
    private val distances = LongArray(4)
    private var lastLength = 0

    // ---- Bits: most significant first ----

    private lateinit var input: ByteArray
    private var end = 0
    private var bit = 0L

    private fun fail(message: String): Nothing = throw ArchiveFormatException("RAR5: $message", bit ushr 3)

    private fun byteAt(i: Long): Int = if (i < end) input[i.toInt()].toInt() and 0xFF else 0

    /** The next 32 bits, without consuming them; zeros past the end. */
    private fun peek32(): Long {
        val at = bit ushr 3
        val shift = (bit and 7).toInt()
        var v = 0L
        for (k in 0 until 5) v = (v shl 8) or byteAt(at + k).toLong()
        return (v ushr (8 - shift)) and 0xFFFFFFFFL
    }

    private fun bits(count: Int): Int {
        if (count == 0) return 0
        if (bit + count > end.toLong() * 8 + 64) fail("compressed data ends early")
        val value = (peek32() ushr (32 - count)).toInt()
        bit += count
        return value
    }

    // ---- Prefix codes, decoded the way RAR does, which accepts incomplete codes ----

    private inner class Code(private val size: Int) {
        val lengths = IntArray(size)
        private val decodeLen = IntArray(16)
        private val decodePos = IntArray(16)
        private val decodeNum = IntArray(size)
        private val quickBits = if (size == MAIN_SYMBOLS) 10 else 7
        private val quickLen = IntArray(1 shl quickBits)
        private val quickNum = IntArray(1 shl quickBits)

        fun build(name: String) {
            val counts = IntArray(16)
            for (i in 0 until size) counts[lengths[i] and 15]++
            counts[0] = 0
            var upper = 0
            for (i in 1 until 16) {
                upper += counts[i]
                decodeLen[i] = upper shl (16 - i)
                decodePos[i] = decodePos[i - 1] + counts[i - 1]
                upper = upper shl 1
            }
            if (upper > 65536) fail("$name code is oversubscribed")
            val next = decodePos.copyOf()
            decodeNum.fill(0)
            for (i in 0 until size) {
                val l = lengths[i] and 15
                if (l > 0) decodeNum[next[l]++] = i
            }
            var length = 1
            for (code in 0 until (1 shl quickBits)) {
                val field = code shl (16 - quickBits)
                while (length < 16 && field >= decodeLen[length]) length++
                quickLen[code] = length
                val pos = if (length < 16) decodePos[length] + ((field - decodeLen[length - 1]) ushr (16 - length)) else size
                quickNum[code] = if (pos < size) decodeNum[pos] else 0
            }
        }

        fun decode(): Int {
            if (bit >= end.toLong() * 8 + 64) fail("compressed data ends early")
            val field = (peek32() ushr 16).toInt() and 0xFFFE
            if (field < decodeLen[quickBits]) {
                val code = field ushr (16 - quickBits)
                bit += quickLen[code]
                return quickNum[code]
            }
            var length = 15
            for (i in quickBits + 1 until 15) {
                if (field < decodeLen[i]) { length = i; break }
            }
            bit += length
            val pos = decodePos[length] + ((field - decodeLen[length - 1]) ushr (16 - length))
            return if (pos < size) decodeNum[pos] else 0
        }
    }

    private fun readTables(blockEnd: Long) {
        val levels = Code(LEVEL_SYMBOLS)
        var w = 0
        while (w < LEVEL_SYMBOLS) {
            if (bit >= blockEnd) fail("prefix code lengths run past their block")
            val value = bits(4)
            if (value == 15) {
                val zeros = bits(4)
                if (zeros == 0) levels.lengths[w++] = 15
                else repeat(zeros + 2) { if (w < LEVEL_SYMBOLS) levels.lengths[w++] = 0 }
            } else {
                levels.lengths[w++] = value
            }
        }
        levels.build("level")
        val table = IntArray(TABLE_SIZE)
        var i = 0
        while (i < TABLE_SIZE) {
            if (bit >= blockEnd) fail("prefix code lengths run past their block")
            val number = levels.decode()
            when {
                number < 16 -> table[i++] = number
                number < 18 -> {
                    val count = if (number == 16) bits(3) + 3 else bits(7) + 11
                    if (i == 0) fail("prefix code lengths repeat before any length")
                    repeat(count) { if (i < TABLE_SIZE) { table[i] = table[i - 1]; i++ } }
                }
                else -> {
                    val count = if (number == 18) bits(3) + 3 else bits(7) + 11
                    repeat(count) { if (i < TABLE_SIZE) table[i++] = 0 }
                }
            }
        }
        var at = 0
        for (code in listOf(main, distance, lowDistance, repeat)) {
            table.copyInto(code.lengths, 0, at, at + code.lengths.size)
            at += code.lengths.size
        }
        main.build("main"); distance.build("distance"); lowDistance.build("low distance"); repeat.build("repeat")
        tablesRead = true
    }

    private fun length(code: Int): Int {
        if (code < 8) return code + 2
        val extra = code / 4 - 1
        return 2 + ((4 or (code and 3)) shl extra) + bits(extra)
    }

    // ---- Files ----

    private class Filter(val start: Int, val length: Int, val type: Int, val channels: Int)

    /**
     * Decode one file's packed data, `input[from, to)`, into [size] bytes. With [keep] false
     * the output is only run through the window, for an earlier file of a solid stream.
     */
    fun unpack(input: ByteArray, from: Int, to: Int, size: Int, keep: Boolean): ByteArray? {
        this.input = input
        end = to
        bit = from.toLong() * 8
        val out = if (keep) ByteArray(size) else null
        var position = 0
        val filters = ArrayList<Filter>()
        var filterEnd = 0L

        fun put(value: Int) {
            if (position >= size) fail("data exceeds the declared size $size")
            window[(written and mask.toLong()).toInt()] = value.toByte()
            written++
            if (out != null) out[position] = value.toByte()
            position++
        }

        fun copy(length: Int, distance: Long) {
            if (distance <= 0 || distance > written || distance > windowSize) fail("match distance $distance reaches before the window")
            if (length > size - position) fail("data exceeds the declared size $size")
            var source = written - distance
            repeat(length) {
                val value = window[(source and mask.toLong()).toInt()]
                source++
                window[(written and mask.toLong()).toInt()] = value
                written++
                if (out != null) out[position] = value
                position++
            }
        }

        fun filterValue(): Int {
            val count = bits(2) + 1
            var value = 0L
            for (k in 0 until count) value = value or (bits(8).toLong() shl (8 * k))
            if (value > Int.MAX_VALUE) fail("filter value $value is too large")
            return value.toInt()
        }

        while (true) {
            var at = (bit + 7) ushr 3
            if (at >= to) break
            val flags = input[at.toInt()].toInt() and 0xFF
            val sizeBytes = ((flags ushr 3) and 7) + 1
            if (sizeBytes > 3) fail("block size takes $sizeBytes bytes")
            if (at + 2 + sizeBytes > to) fail("block header runs past the data")
            var blockSize = 0
            for (k in 0 until sizeBytes) blockSize = blockSize or ((input[(at + 2 + k).toInt()].toInt() and 0xFF) shl (8 * k))
            var check = 0x5A xor flags
            for (k in 0 until 3) check = check xor ((blockSize ushr (8 * k)) and 0xFF)
            if ((check and 0xFF) != (input[(at + 1).toInt()].toInt() and 0xFF)) fail("block header checksum mismatch")
            at += 2 + sizeBytes
            if (blockSize > to - at) fail("block runs past the data")
            val dataEnd = at + blockSize
            val blockEnd = if (blockSize == 0) at * 8 else (dataEnd - 1) * 8 + (flags and 7) + 1
            bit = at * 8
            if (flags and 0x80 != 0) readTables(blockEnd)
            if (!tablesRead) fail("block has no prefix codes")
            while (bit < blockEnd) {
                val number = main.decode()
                when {
                    number < 256 -> put(number)
                    number >= 262 -> {
                        var length = length(number - 262)
                        val slot = distance.decode()
                        var dist: Long
                        if (slot < 4) {
                            dist = 1L + slot
                        } else {
                            val extra = slot / 2 - 1
                            dist = 1L + ((2L or (slot and 1).toLong()) shl extra)
                            if (extra >= 4) {
                                if (extra > 4) dist += bits(extra - 4).toLong() shl 4
                                dist += lowDistance.decode()
                            } else {
                                dist += bits(extra)
                            }
                        }
                        if (dist > 0x100) { length++; if (dist > 0x2000) { length++; if (dist > 0x40000) length++ } }
                        distances.copyInto(distances, 1, 0, 3)
                        distances[0] = dist
                        lastLength = length
                        copy(length, dist)
                    }
                    number == 256 -> {
                        val start = position.toLong() + filterValue()
                        val length = filterValue()
                        val type = bits(3)
                        val channels = if (type == FILTER_DELTA) bits(5) + 1 else 0
                        if (length < 4 || length > MAX_FILTER || length > dictionary / 2 || start < filterEnd) fail("invalid filter")
                        if (type > FILTER_ARM) fail("unknown filter type $type")
                        if (start + length > size) fail("filter runs past the end of the file")
                        filters.add(Filter(start.toInt(), length, type, channels))
                        filterEnd = start + length
                    }
                    number == 257 -> if (lastLength != 0) copy(lastLength, distances[0])
                    else -> {
                        val index = number - 258
                        val dist = distances[index]
                        distances.copyInto(distances, 1, 0, index)
                        distances[0] = dist
                        val length = length(repeat.decode())
                        lastLength = length
                        copy(length, dist)
                    }
                }
            }
            bit = dataEnd * 8
            if (flags and 0x40 != 0) break
        }
        if (position != size) fail("data ends after $position of $size bytes")
        if (out == null || filters.isEmpty()) return out
        val result = out.copyOf()
        for (filter in filters) applyFilter(filter, out, result)
        return result
    }

    private fun applyFilter(filter: Filter, raw: ByteArray, result: ByteArray) {
        val start = filter.start
        val length = filter.length
        when (filter.type) {
            FILTER_DELTA -> {
                var source = start
                for (channel in 0 until filter.channels) {
                    var previous = 0
                    var target = channel
                    while (target < length) {
                        previous = (previous - raw[source++]) and 0xFF
                        result[start + target] = previous.toByte()
                        target += filter.channels
                    }
                }
            }
            FILTER_E8, FILTER_E8E9 -> {
                var i = 0
                while (i < length - 4) {
                    val b = raw[start + i++].toInt() and 0xFF
                    if (b == 0xE8 || (filter.type == FILTER_E8E9 && b == 0xE9)) {
                        val offset = ((i + start).toLong() % E8_FILE_SIZE).toInt()
                        val address = readLe32(raw, start + i)
                        if (address < 0) {
                            if (address + offset >= 0) writeLe32(result, start + i, address + E8_FILE_SIZE)
                        } else if (address - E8_FILE_SIZE < 0) {
                            writeLe32(result, start + i, address - offset)
                        }
                        i += 4
                    }
                }
            }
            FILTER_ARM -> {
                var i = 0
                while (i < length - 3) {
                    if (raw[start + i + 3].toInt() and 0xFF == 0xEB) {
                        var offset = readLe32(raw, start + i) and 0x00FFFFFF
                        offset -= (i + start) / 4
                        writeLe32(result, start + i, (offset and 0x00FFFFFF) or 0xEB000000.toInt())
                    }
                    i += 4
                }
            }
        }
    }

    private companion object {
        const val LEVEL_SYMBOLS = 20
        const val MAIN_SYMBOLS = 306
        const val DISTANCE_SYMBOLS = 64
        const val LOW_DISTANCE_SYMBOLS = 16
        const val REPEAT_SYMBOLS = 44
        const val TABLE_SIZE = MAIN_SYMBOLS + DISTANCE_SYMBOLS + LOW_DISTANCE_SYMBOLS + REPEAT_SYMBOLS
        const val MAX_FILTER = 0x400000
        const val FILTER_DELTA = 0
        const val FILTER_E8 = 1
        const val FILTER_E8E9 = 2
        const val FILTER_ARM = 3
        const val E8_FILE_SIZE = 0x1000000

        fun readLe32(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or (b[at + 3].toInt() shl 24)

        fun writeLe32(b: ByteArray, at: Int, v: Int) {
            b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte(); b[at + 2] = (v ushr 16).toByte(); b[at + 3] = (v ushr 24).toByte()
        }
    }
}
