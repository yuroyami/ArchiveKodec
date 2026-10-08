/* Altered Kotlin translation of Mark Adler's puff, via KiteTorrent/libtorrent. */

/* puff.h
  Copyright (C) 2002-2013 Mark Adler, all rights reserved
  version 2.3, 21 Jan 2013

  This software is provided 'as-is', without any express or implied
  warranty.  In no event will the author be held liable for any damages
  arising from the use of this software.

  Permission is granted to anyone to use this software for any purpose,
  including commercial applications, and to alter it and redistribute it
  freely, subject to the following restrictions:

  1. The origin of this software must not be misrepresented; you must not
     claim that you wrote the original software. If you use this software
     in a product, an acknowledgment in the product documentation would be
     appreciated but is not required.
  2. Altered source versions must be plainly marked as such, and must not be
     misrepresented as being the original software.
  3. This notice may not be removed or altered from any source distribution.

  Mark Adler    madler@alumni.caltech.edu
 */

package io.github.yuroyami.archivekodec.codec.deflate

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveSizeException
import io.github.yuroyami.archivekodec.checksum.Checksum
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.byteArrayCapacity
import io.github.yuroyami.archivekodec.util.readI32Le
import io.github.yuroyami.archivekodec.util.readU16Le

/**
 * Raw DEFLATE (RFC 1951) decompressor. It is a pure-Kotlin translation of Mark
 * Adler's `puff()`, the deliberately simple reference inflate that ships with zlib
 * at `contrib/puff/puff.c`.
 *
 * Differences from the C:
 *
 *  - puff writes into a caller-supplied fixed-size buffer and returns `1`
 *    ("output space exhausted") when it's too small; we grow the output array on
 *    demand within the caller’s output and ratio budgets. [ArchiveSizeException]
 *    reports a result that cannot fit in a single ByteArray.
 *
 *  - puff signals "ran past the end of input" via `setjmp`/`longjmp`; we model
 *    that non-local exit with a private [OutOfInput] throwable caught at the top
 *    of [inflate], mapping to [InflateError.DATA_DID_NOT_TERMINATE].
 *
 *  - puff's "scan only" mode (`dest == NULL`) is omitted; we always materialise
 *    the output.
 *
 * Bits are buffered in 32-bit words. Canonical codes index a primary table of up
 * to nine bits and secondary tables for longer codes. Fixed tables are shared;
 * bounded canonical decoding handles short tails and invalid/incomplete prefixes.
 * Lookahead retains the logical byte cursor for following blocks and trailers.
 *
 * This object is stateless and thread-safe; all mutable inflate state lives in the
 * private [State] created per call.
 *
 * This is a supported low-level public API for callers handling raw RFC 1951
 * streams. The registered [io.github.yuroyami.archivekodec.codec.Codecs.DEFLATE]
 * codec provides the common [io.github.yuroyami.archivekodec.codec.Codec] interface.
 */
public object Inflate {

    // Maximums fixed by the deflate format (puff.c #defines).
    private const val MAXBITS = 15        // maximum bits in a code
    private const val MAXLCODES = 286     // maximum number of literal/length codes
    private const val MAXDCODES = 30      // maximum number of distance codes
    private const val MAXCODES = MAXLCODES + MAXDCODES
    private const val FIXLCODES = 288     // number of fixed literal/length codes

    // Size base for length codes 257..285 (puff `lens[]`).
    private val LENS = shortArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
        35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    // Extra bits for length codes 257..285 (puff `lext[]`).
    private val LEXT = shortArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
        3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    // Offset base for distance codes 0..29 (puff `dists[]`).
    private val DISTS = shortArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
        257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
        8193, 12289, 16385, 24577,
    )
    // Extra bits for distance codes 0..29 (puff `dext[]`).
    private val DEXT = shortArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
        7, 7, 8, 8, 9, 9, 10, 10, 11, 11,
        12, 12, 13, 13,
    )
    // Permutation of the code length codes in a dynamic block (puff `order[]`).
    private val ORDER = intArrayOf(
        16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
    )

    /**
     * Inflate exactly one complete raw DEFLATE stream with [ArchiveLimits.DEFAULT].
     *
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed or truncated DEFLATE data.
     * @throws ArchiveFormatException if bytes remain after the DEFLATE stream.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if an output or compression-ratio limit is exceeded.
     */
    @Throws(ArchiveException::class)
    public fun inflate(input: ByteArray): ByteArray = inflate(input, ArchiveLimits.DEFAULT)

    /**
     * Inflate exactly one raw DEFLATE stream. Every write checks [limits] before
     * allocating or copying. Trailing bytes are a format error; gzip/zlib framing
     * uses an internal cursor to read the following trailer separately.
     *
     * @throws InflateException on malformed or truncated DEFLATE input.
     * @throws ArchiveFormatException if bytes remain after the stream.
     * @throws ArchiveSizeException if the output cannot fit in a single ByteArray.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException
     *   if the cumulative output or whole-input compression ratio exceeds [limits].
     */
    @Throws(ArchiveException::class)
    public fun inflate(input: ByteArray, limits: ArchiveLimits): ByteArray = inflateChecked(input, limits, null)

    /** ZIP's authoritative size constrains writes as well as the final stream length. */
    internal fun inflateExact(input: ByteArray, limits: ArchiveLimits, expectedSize: Long): ByteArray =
        inflateChecked(input, limits, expectedSize)

    private fun inflateChecked(input: ByteArray, limits: ArchiveLimits, expectedSize: Long?): ByteArray {
        val output = Output(limits, input.size, expectedSize)
        val end = inflateInto(input, 0, input.size, output)
        if (end != input.size) throw ArchiveFormatException("deflate: trailing bytes after stream", end.toLong())
        if (expectedSize != null && output.size.toLong() != expectedSize) {
            throw ArchiveFormatException("deflate: output size ${output.size} differs from expected size $expectedSize", end.toLong())
        }
        return output.toByteArray()
    }

    /** Return the first byte after the final block, including its partial final byte. */
    internal fun inflateInto(input: ByteArray, offset: Int, end: Int, output: Output): Int {
        require(offset >= 0 && end >= offset && end <= input.size) { "Invalid DEFLATE input slice" }
        val s = State(input, offset, end, output)
        try {
            var last: Int
            do {
                last = s.bits(1)
                val type = s.bits(2)
                val err = when (type) {
                    0 -> stored(s)
                    1 -> fixed(s)
                    2 -> dynamic(s)
                    else -> InflateError.INVALID_BLOCK_TYPE.code
                }
                if (err != 0) throw InflateException(InflateError.fromPuff(err), s.consumedPosition.toLong(), null, null)
            } while (last == 0)
        } catch (_: OutOfInput) {
            throw InflateException(InflateError.DATA_DID_NOT_TERMINATE, s.position.toLong(), null, null)
        } catch (error: InflateException) {
            if (error.offset != null) throw error
            throw InflateException(error.error, s.consumedPosition.toLong(), null, error)
        }
        return s.consumedPosition
    }

    /** Shared output across gzip members; each State still has a fresh dictionary. */
    internal class Output(
        private val limits: ArchiveLimits, private val compressedBytes: Int,
        private val expectedSize: Long? = null,
    ) {
        private val maximum: Int
        private var bytes: ByteArray
        var size: Int = 0
            private set

        init {
            if (expectedSize != null && expectedSize < 0) throw ArchiveFormatException("deflate: negative expected size $expectedSize")
            limits.checkOutputSize(0)
            limits.checkRatio(compressedBytes.toLong(), 0)
            maximum = minOf(
                Int.MAX_VALUE.toLong(), limits.maxUncompressedBytes, expectedSize ?: Long.MAX_VALUE,
                if (compressedBytes > 0) compressedBytes.toLong() * limits.maxCompressionRatio else Long.MAX_VALUE,
            ).toInt()
            bytes = ByteArray(minOf(256, maximum))
        }

        private fun ensure(extra: Int) {
            // Every allocation is capped by all three immutable budgets. Writes
            // within that capacity need no Long arithmetic or ratio division.
            if (extra <= bytes.size - size) return
            val required = size.toLong() + extra
            limits.checkOutputSize(required)
            limits.checkRatio(compressedBytes.toLong(), required)
            if (expectedSize != null && required > expectedSize) {
                throw ArchiveFormatException("deflate: output size would exceed expected size $expectedSize")
            }
            val capacity = byteArrayCapacity(bytes.size, size, extra, maximum)
            if (capacity != bytes.size) bytes = bytes.copyOf(capacity)
        }

        fun writeByte(value: Int) {
            ensure(1)
            bytes[size++] = value.toByte()
        }

        fun copyBack(distance: Int, length: Int) {
            ensure(length)
            val output = bytes
            val end = size + length
            var source = size - distance
            if (distance >= length) {
                output.copyInto(output, size, source, source + length)
            } else {
                var destination = size
                while (destination < end) output[destination++] = output[source++]
            }
            size = end
        }

        fun copyStored(input: ByteArray, offset: Int, length: Int) {
            ensure(length)
            input.copyInto(bytes, size, offset, offset + length)
            size += length
        }

        fun checksum(checksum: Checksum, offset: Int = 0): Long {
            checksum.update(bytes, offset, size - offset)
            return checksum.value()
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }

    /** Non-local "out of input" signal, replacing puff's setjmp/longjmp. */
    private class OutOfInput : Throwable()

    /** Per-stream input and bit cursor, with a dictionary starting at [outputStart]. */
    private class State(
        private val inBuf: ByteArray, offset: Int, private val end: Int,
        private val output: Output,
    ) {
        var position = offset
            private set
        var bitbuf = 0
        var bitcnt = 0
        val consumedPosition: Int get() = position - (bitcnt ushr 3)
        private val outputStart = output.size
        val outcnt: Int get() = output.size - outputStart
        val inAvailable: Int get() = end - position

        fun nextByte(): Int {
            if (position == end) throw OutOfInput()
            return inBuf[position++].toInt() and 0xff
        }

        fun rawU16(): Int {
            if (inAvailable < 2) throw OutOfInput()
            val value = readU16Le(inBuf, position)
            position += 2
            return value
        }

        /** Fill without crossing the slice; a short final code may need fewer bits. */
        fun fill(need: Int) {
            while (bitcnt < need && position < end) {
                if (bitcnt <= 8 && inAvailable >= 4) {
                    val word = readI32Le(inBuf, position)
                    if (bitcnt == 0) {
                        bitbuf = word
                        bitcnt = 32
                        position += 4
                    } else {
                        bitbuf = bitbuf or ((word and 0xFFFFFF) shl bitcnt)
                        bitcnt += 24
                        position += 3
                    }
                } else {
                    bitbuf = bitbuf or (nextByte() shl bitcnt)
                    bitcnt += 8
                }
            }
        }

        fun drop(need: Int) {
            bitbuf = bitbuf ushr need
            bitcnt -= need
        }

        fun bits(need: Int): Int {
            fill(need)
            if (bitcnt < need) throw OutOfInput()
            val value = bitbuf and ((1 shl need) - 1)
            drop(need)
            return value
        }

        fun alignToByte() {
            // Discard padding in the current byte, retaining prefetched whole bytes.
            position = consumedPosition
            bitbuf = 0
            bitcnt = 0
        }

        fun writeByte(value: Int) = output.writeByte(value)
        fun copyBack(distance: Int, length: Int) = output.copyBack(distance, length)
        fun copyStored(length: Int) {
            output.copyStored(inBuf, position, length)
            position += length
        }
    }

    private class Huffman(maxSymbols: Int) {
        val count = ShortArray(MAXBITS + 1)
        val symbol = ShortArray(maxSymbols)
        var rootBits = 0
        var table = IntArray(1)
    }

    private fun stored(s: State): Int {
        s.alignToByte()

        if (s.inAvailable < 4) return InflateError.DATA_DID_NOT_TERMINATE.code
        val len = s.rawU16()
        val nlen = s.rawU16()
        if (nlen != (len xor 0xffff)) {
            return InflateError.INVALID_STORED_BLOCK_LENGTH.code
        }

        if (s.inAvailable < len) return InflateError.DATA_DID_NOT_TERMINATE.code
        s.copyStored(len)
        return 0
    }

    private fun decode(s: State, h: Huffman): Int {
        s.fill(h.rootBits)
        val rootMask = (1 shl h.rootBits) - 1
        var entry = h.table[s.bitbuf and rootMask]
        if (entry < 0) {
            val pointer = -entry
            val tailBits = pointer and 15
            s.fill(h.rootBits + tailBits)
            entry = h.table[(pointer ushr 4) + ((s.bitbuf ushr h.rootBits) and ((1 shl tailBits) - 1))]
        }
        if (entry > 0) {
            val length = entry ushr 16
            if (length <= s.bitcnt) {
                s.drop(length)
                return entry and 0xFFFF
            }
        }
        return decodeSlow(s, h)
    }

    /** Preserve puff's bounded invalid-code and truncated-input behavior. */
    private fun decodeSlow(s: State, h: Huffman): Int {
        var code = 0
        var first = 0
        var index = 0
        for (length in 1..MAXBITS) {
            code = code or s.bits(1)
            val count = h.count[length].toInt()
            if (code - count < first) return h.symbol[index + code - first].toInt()
            index += count
            first = (first + count) shl 1
            code = code shl 1
        }
        return InflateError.INVALID_LITERAL_OR_DISTANCE_CODE.code
    }

    private fun construct(h: Huffman, length: ShortArray, n: Int): Int {
        for (len in 0..MAXBITS) h.count[len] = 0
        for (symbol in 0 until n) h.count[length[symbol].toInt()]++
        if (h.count[0].toInt() == n) return 0

        var left = 1
        for (len in 1..MAXBITS) {
            left = left shl 1
            left -= h.count[len].toInt()
            if (left < 0) return left
        }

        val offs = ShortArray(MAXBITS + 1)
        offs[1] = 0
        for (len in 1 until MAXBITS) {
            offs[len + 1] = (offs[len] + h.count[len]).toShort()
        }

        for (symbol in 0 until n) {
            val l = length[symbol].toInt()
            if (l != 0) {
                h.symbol[offs[l].toInt()] = symbol.toShort()
                offs[l]++
            }
        }
        buildLookup(h, length, n)
        return left
    }

    private fun buildLookup(h: Huffman, lengths: ShortArray, n: Int) {
        var longest = MAXBITS
        while (h.count[longest].toInt() == 0) longest--
        val rootBits = minOf(9, longest)
        val rootSize = 1 shl rootBits
        val next = IntArray(MAXBITS + 1)
        for (length in 2..MAXBITS) next[length] = (next[length - 1] + h.count[length - 1]) shl 1
        val reversed = IntArray(n)
        val widths = IntArray(if (longest > rootBits) rootSize else 0)
        for (symbol in 0 until n) {
            val length = lengths[symbol].toInt()
            if (length == 0) continue
            var code = next[length]++
            var reverse = 0
            repeat(length) {
                reverse = (reverse shl 1) or (code and 1)
                code = code ushr 1
            }
            reversed[symbol] = reverse
            if (length > rootBits) {
                val prefix = reverse and (rootSize - 1)
                widths[prefix] = maxOf(widths[prefix], length - rootBits)
            }
        }
        var size = rootSize
        for (width in widths) if (width > 0) size += 1 shl width
        val table = IntArray(size)
        var offset = rootSize
        for (prefix in widths.indices) {
            val width = widths[prefix]
            if (width > 0) {
                table[prefix] = -((offset shl 4) or width)
                offset += 1 shl width
            }
        }
        for (symbol in 0 until n) {
            val length = lengths[symbol].toInt()
            if (length == 0) continue
            val entry = (length shl 16) or symbol
            if (length <= rootBits) {
                var index = reversed[symbol]
                while (index < rootSize) {
                    table[index] = entry
                    index += 1 shl length
                }
            } else {
                val pointer = -table[reversed[symbol] and (rootSize - 1)]
                val start = pointer ushr 4
                val count = 1 shl (pointer and 15)
                var index = reversed[symbol] ushr rootBits
                while (index < count) {
                    table[start + index] = entry
                    index += 1 shl (length - rootBits)
                }
            }
        }
        h.rootBits = rootBits
        h.table = table
    }

    private fun codes(s: State, lencode: Huffman, distcode: Huffman): Int {
        var symbol: Int
        do {
            symbol = decode(s, lencode)
            if (symbol < 0) return symbol
            when {
                symbol < 256 -> {
                    s.writeByte(symbol)
                }
                symbol > 256 -> {
                    var sym = symbol - 257
                    if (sym >= 29) return InflateError.INVALID_LITERAL_OR_DISTANCE_CODE.code
                    val len = LENS[sym].toInt() + s.bits(LEXT[sym].toInt())

                    sym = decode(s, distcode)
                    if (sym < 0) return sym
                    val dist = DISTS[sym].toInt() + s.bits(DEXT[sym].toInt())
                    if (dist > s.outcnt) return InflateError.DISTANCE_TOO_FAR_BACK.code
                    s.copyBack(dist, len)
                }
                // symbol == 256 -> end of block, loop terminates
            }
        } while (symbol != 256)
        return 0
    }

    private val fixedLiteral = Huffman(FIXLCODES).also { table ->
        val lengths = ShortArray(FIXLCODES) {
            when {
                it < 144 -> 8
                it < 256 -> 9
                it < 280 -> 7
                else -> 8
            }
        }
        construct(table, lengths, lengths.size)
    }

    private val fixedDistance = Huffman(MAXDCODES).also { table ->
        construct(table, ShortArray(MAXDCODES) { 5 }, MAXDCODES)
    }

    private fun fixed(s: State): Int = codes(s, fixedLiteral, fixedDistance)

    private fun dynamic(s: State): Int {
        val lengths = ShortArray(MAXCODES)

        val nlen = s.bits(5) + 257
        val ndist = s.bits(5) + 1
        val ncode = s.bits(4) + 4
        if (nlen > MAXLCODES || ndist > MAXDCODES) {
            return InflateError.TOO_MANY_LENGTH_OR_DISTANCE_CODES.code
        }

        var index = 0
        while (index < ncode) {
            lengths[ORDER[index]] = s.bits(3).toShort()
            index++
        }
        while (index < 19) {
            lengths[ORDER[index]] = 0
            index++
        }

        val lencode = Huffman(MAXLCODES)
        var err = construct(lencode, lengths, 19)
        if (err != 0) return InflateError.CODE_LENGTHS_CODES_INCOMPLETE.code

        index = 0
        while (index < nlen + ndist) {
            var symbol = decode(s, lencode)
            if (symbol < 0) return symbol
            if (symbol < 16) {
                lengths[index++] = symbol.toShort()
            } else {
                var repeatLen = 0
                when (symbol) {
                    16 -> {
                        if (index == 0) return InflateError.REPEAT_LENGTHS_WITH_NO_FIRST_LENGTH.code
                        repeatLen = lengths[index - 1].toInt()
                        symbol = 3 + s.bits(2)
                    }
                    17 -> symbol = 3 + s.bits(3)
                    else -> symbol = 11 + s.bits(7)
                }
                if (index + symbol > nlen + ndist) {
                    return InflateError.REPEAT_MORE_THAN_SPECIFIED_LENGTHS.code
                }
                while (symbol-- > 0) lengths[index++] = repeatLen.toShort()
            }
        }

        if (lengths[256].toInt() == 0) return InflateError.MISSING_END_OF_BLOCK_CODE.code

        err = construct(lencode, lengths, nlen)
        if (err != 0 && (err < 0 || nlen != lencode.count[0].toInt() + lencode.count[1].toInt())) {
            return InflateError.INVALID_LITERAL_LENGTH_CODE_LENGTHS.code
        }

        val distLengths = ShortArray(MAXDCODES)
        for (i in 0 until ndist) distLengths[i] = lengths[nlen + i]
        val distcode = Huffman(MAXDCODES)
        err = construct(distcode, distLengths, ndist)
        if (err != 0 && (err < 0 || ndist != distcode.count[0].toInt() + distcode.count[1].toInt())) {
            return InflateError.INVALID_DISTANCE_CODE_LENGTHS.code
        }

        return codes(s, lencode, distcode)
    }
}
