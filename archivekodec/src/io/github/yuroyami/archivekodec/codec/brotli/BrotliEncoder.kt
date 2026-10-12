package io.github.yuroyami.archivekodec.codec.brotli

import io.github.yuroyami.archivekodec.codec.deflate.LengthLimitedHuffman
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * Brotli encoder: LZ77 meta-blocks with one prefix code per category, no context
 * modelling, block switching or dictionary references, and raw meta-blocks where that
 * is shorter. The streams are valid RFC 7932 but larger than the reference `brotli`'s.
 */
internal object BrotliEncoder {
    private const val WINDOW_BITS = 22
    private const val MAX_BACKWARD = (1 shl WINDOW_BITS) - 16
    private const val BLOCK = 1 shl 20
    private const val HASH_BITS = 16
    private const val CHAIN_DEPTH = 16
    private const val MIN_MATCH = 4
    private const val MAX_MATCH = 2118 + (1 shl 24) - 1
    private const val DISTANCE_ALPHABET = 16 + 48

    private val INSERT_BASE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 8, 10, 14, 18, 26, 34, 50, 66, 98, 130, 194, 322, 578, 1090, 2114, 6210, 22594,
    )
    private val INSERT_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 24)
    private val COPY_BASE = intArrayOf(
        2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 18, 22, 30, 38, 54, 70, 102, 134, 198, 326, 582, 1094, 2118,
    )
    private val COPY_BITS = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 24)

    /** Command cell for (insert code / 8, copy code / 8), using explicit distances. */
    private val CELL = arrayOf(intArrayOf(2, 3, 6), intArrayOf(4, 5, 8), intArrayOf(7, 9, 10))

    private val CODE_LENGTH_ORDER = intArrayOf(1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15)
    /** The fixed code for code-length code lengths 0..5: value and bit count. */
    private val CODE_LENGTH_PREFIX_CODE = intArrayOf(0, 7, 3, 2, 1, 15)
    private val CODE_LENGTH_PREFIX_BITS = intArrayOf(2, 4, 3, 2, 2, 4)

    private class Writer(val out: ByteArrayBuilder) {
        private var buffer = 0L
        private var count = 0

        fun write(value: Int, n: Int) {
            if (n == 0) return
            buffer = buffer or ((value.toLong() and ((1L shl n) - 1)) shl count)
            count += n
            while (count >= 8) { out.append(buffer.toByte()); buffer = buffer ushr 8; count -= 8 }
        }

        fun align() { if (count > 0) write(0, 8 - count) }
    }

    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(maxOf(64, data.size / 3))
        val w = Writer(out)
        w.write(1, 1); w.write(WINDOW_BITS - 17, 3)
        val matcher = Matcher(data)
        var start = 0
        while (start < data.size) {
            val end = minOf(data.size, start + BLOCK)
            metaBlock(w, data, start, end, matcher)
            start = end
        }
        w.write(1, 1); w.write(1, 1) // ISLAST, ISLASTEMPTY
        w.align()
        return out.takeByteArray()
    }

    private fun lengthHeader(w: Writer, length: Int) {
        val bits = 32 - (length - 1).countLeadingZeroBits()
        val nibbles = maxOf(4, (bits + 3) / 4)
        w.write(0, 1) // ISLAST
        w.write(nibbles - 4, 2)
        w.write(length - 1, nibbles * 4)
    }

    private fun code(base: IntArray, value: Int): Int {
        var i = base.size - 1
        while (base[i] > value) i--
        return i
    }

    private fun metaBlock(w: Writer, data: ByteArray, start: Int, end: Int, matcher: Matcher) {
        val commands = ArrayList<Int>() // insert, copy, distance triples
        var anchor = start
        var p = start
        while (p < end) {
            var length = matcher.find(p, end)
            if (length == 0) { p++; continue }
            var distance = matcher.distance
            if (p + 1 < end) {
                val next = matcher.find(p + 1, end)
                if (next > length + 1) { p++; length = next; distance = matcher.distance }
            }
            commands.add(p - anchor); commands.add(length); commands.add(distance)
            p += length
            anchor = p
        }
        if (anchor < end) { commands.add(end - anchor); commands.add(2); commands.add(0) }

        val literalFrequencies = IntArray(256)
        val commandFrequencies = IntArray(704)
        val distanceFrequencies = IntArray(DISTANCE_ALPHABET)
        val symbols = IntArray(commands.size)
        var literal = start
        var extraBits = 0L
        for (c in 0 until commands.size / 3) {
            val insert = commands[3 * c]
            val copy = commands[3 * c + 1]
            repeat(insert) { literalFrequencies[data[literal++].toInt() and 0xFF]++ }
            literal += copy
            val insertCode = code(INSERT_BASE, insert)
            val copyCode = code(COPY_BASE, copy)
            val command = 64 * CELL[insertCode ushr 3][copyCode ushr 3] + ((insertCode and 7) shl 3) + (copyCode and 7)
            commandFrequencies[command]++
            symbols[3 * c] = command
            extraBits += INSERT_BITS[insertCode] + COPY_BITS[copyCode]
            val distance = commands[3 * c + 2]
            if (distance > 0) {
                val (distanceCode, bits, _) = distanceCode(distance)
                distanceFrequencies[distanceCode]++
                symbols[3 * c + 1] = distanceCode
                extraBits += bits
            }
        }
        val literalLengths = lengths(literalFrequencies)
        val commandLengths = lengths(commandFrequencies)
        val distanceLengths = lengths(distanceFrequencies)
        var cost = extraBits
        for (s in 0 until 256) cost += literalFrequencies[s].toLong() * literalLengths[s]
        for (s in 0 until 704) cost += commandFrequencies[s].toLong() * commandLengths[s]
        for (s in 0 until DISTANCE_ALPHABET) cost += distanceFrequencies[s].toLong() * distanceLengths[s]
        // About four bits per code length up to each code's last symbol, plus the header.
        for (lengths in arrayOf(literalLengths, commandLengths, distanceLengths)) cost += 4L * (lengths.indexOfLast { it > 0 } + 1) + 72
        if (cost / 8 >= end - start) {
            lengthHeader(w, end - start)
            w.write(1, 1) // ISUNCOMPRESSED
            w.align()
            w.out.append(data, start, end - start)
            return
        }

        lengthHeader(w, end - start)
        w.write(0, 1) // compressed
        repeat(3) { w.write(0, 1) } // one block type per category
        w.write(0, 2); w.write(0, 4) // NPOSTFIX, NDIRECT
        w.write(0, 2) // context mode of the one literal block type
        w.write(0, 1); w.write(0, 1) // one literal tree, one distance tree
        val literalCodes = writeCode(w, literalLengths, 256)
        val commandCodes = writeCode(w, commandLengths, 704)
        val distanceCodes = writeCode(w, distanceLengths, DISTANCE_ALPHABET)

        literal = start
        for (c in 0 until commands.size / 3) {
            val insert = commands[3 * c]
            val copy = commands[3 * c + 1]
            val command = symbols[3 * c]
            w.write(commandCodes[command], commandLengths[command])
            val insertCode = code(INSERT_BASE, insert)
            val copyCode = code(COPY_BASE, copy)
            w.write(insert - INSERT_BASE[insertCode], INSERT_BITS[insertCode])
            w.write(copy - COPY_BASE[copyCode], COPY_BITS[copyCode])
            repeat(insert) {
                val byte = data[literal++].toInt() and 0xFF
                w.write(literalCodes[byte], literalLengths[byte])
            }
            literal += copy
            val distance = commands[3 * c + 2]
            if (distance > 0) {
                val (distanceCode, bits, extra) = distanceCode(distance)
                w.write(distanceCodes[distanceCode], distanceLengths[distanceCode])
                w.write(extra, bits)
            }
        }
    }

    /** Distance code, extra bit count and extra value for [distance] with NPOSTFIX = NDIRECT = 0. */
    private fun distanceCode(distance: Int): Triple<Int, Int, Int> {
        val x = distance + 3
        val bits = 31 - x.countLeadingZeroBits() - 1
        val prefix = (x ushr bits) and 1
        return Triple(16 + 2 * (bits - 1) + prefix, bits, x - ((2 + prefix) shl bits))
    }

    private fun lengths(frequencies: IntArray): IntArray = LengthLimitedHuffman.codeLengths(frequencies, 15)

    /** Canonical codes for [lengths], bit-reversed for LSB-first writing. */
    private fun canonical(lengths: IntArray): IntArray {
        val counts = IntArray(16)
        for (l in lengths) if (l > 0) counts[l]++
        val next = IntArray(17)
        for (l in 2..15) next[l] = (next[l - 1] + counts[l - 1]) shl 1
        return IntArray(lengths.size) { s ->
            val l = lengths[s]
            if (l == 0) 0 else {
                var code = next[l]++
                var reverse = 0
                repeat(l) { reverse = (reverse shl 1) or (code and 1); code = code ushr 1 }
                reverse
            }
        }
    }

    /** Write a prefix code for [lengths] and return the symbol codes it defines. */
    private fun writeCode(w: Writer, lengths: IntArray, alphabetSize: Int): IntArray {
        var used = 0
        var last = 0
        for (s in lengths.indices) if (lengths[s] > 0) { used++; last = s }
        if (used <= 1) {
            if (used == 1) lengths[last] = 0 // a one-symbol code takes no bits
            w.write(1, 2) // simple code
            w.write(0, 2) // one symbol
            w.write(last, 32 - (alphabetSize - 1).countLeadingZeroBits())
            return IntArray(lengths.size)
        }
        w.write(0, 2) // complex code, no skipped code-length codes
        val frequencies = IntArray(18)
        for (s in 0..last) frequencies[lengths[s]]++
        val codeLengthLengths = LengthLimitedHuffman.codeLengths(frequencies, 5)
        val distinct = frequencies.count { it > 0 }
        if (distinct == 1) codeLengthLengths[frequencies.indexOfFirst { it > 0 }] = 1
        var space = 32
        for (i in 0 until 18) {
            val value = codeLengthLengths[CODE_LENGTH_ORDER[i]]
            w.write(CODE_LENGTH_PREFIX_CODE[value], CODE_LENGTH_PREFIX_BITS[value])
            if (value != 0) {
                space -= 32 shr value
                if (space <= 0) break
            }
        }
        val codeLengthCodes = canonical(codeLengthLengths)
        for (s in 0..last) {
            val l = lengths[s]
            if (distinct > 1) w.write(codeLengthCodes[l], codeLengthLengths[l])
        }
        return canonical(lengths)
    }

    /** Hash-chain match finder over the whole input, bounded by the window. */
    private class Matcher(private val data: ByteArray) {
        private val head = IntArray(1 shl HASH_BITS) { -1 }
        private val chain = IntArray(data.size)
        private var inserted = 0
        var distance = 0
            private set

        private fun hash(p: Int): Int {
            val v = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8) or
                ((data[p + 2].toInt() and 0xFF) shl 16) or ((data[p + 3].toInt() and 0xFF) shl 24)
            return (v * -1640531535) ushr (32 - HASH_BITS)
        }

        fun find(p: Int, end: Int): Int {
            while (inserted < p) {
                if (inserted + 3 < data.size) {
                    val h = hash(inserted)
                    chain[inserted] = head[h]
                    head[h] = inserted
                }
                inserted++
            }
            val limit = minOf(MAX_MATCH, end - p)
            if (limit < MIN_MATCH) return 0
            var best = 0
            var candidate = head[hash(p)]
            var depth = CHAIN_DEPTH
            val oldest = p - MAX_BACKWARD
            while (candidate >= 0 && candidate >= oldest && depth-- > 0) {
                if (data[candidate + best] == data[p + best]) {
                    var n = 0
                    while (n < limit && data[candidate + n] == data[p + n]) n++
                    if (n > best) { best = n; distance = p - candidate; if (n == limit) break }
                }
                candidate = chain[candidate]
            }
            return if (best >= MIN_MATCH) best else 0
        }
    }
}
