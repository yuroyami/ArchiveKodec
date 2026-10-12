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

import kotlin.jvm.JvmStatic

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveSizeException
import io.github.yuroyami.archivekodec.checksum.Checksum
import io.github.yuroyami.archivekodec.codec.ByteSink
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
    private const val MAXDCODES64 = 32    // Deflate64 also uses distance codes 30 and 31
    private const val MAXCODES = MAXLCODES + MAXDCODES64
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
    // Deflate64 (PKWARE "enhanced deflate", ZIP method 9) differs in three places: distance
    // codes 30 and 31 reach back 64 KiB, and length code 285 means 3 plus 16 extra bits.
    private val DISTS64 = IntArray(MAXDCODES64) { if (it < 30) DISTS[it].toInt() else if (it == 30) 32769 else 49153 }
    private val DEXT64 = IntArray(MAXDCODES64) { if (it < 30) DEXT[it].toInt() else 14 }

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
    @JvmStatic
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
    @JvmStatic
    public fun inflate(input: ByteArray, limits: ArchiveLimits): ByteArray = inflateChecked(input, limits, null)

    /**
     * Inflate one raw DEFLATE stream that was compressed with a preset [dictionary], such as
     * [Deflate.encode] with [DeflateOptions.dictionary]. The dictionary is history the stream
     * may refer back to; it is not part of the output and does not count against [limits].
     *
     * @throws InflateException on malformed or truncated DEFLATE input.
     * @throws ArchiveFormatException if bytes remain after the stream.
     * @throws ArchiveSizeException if the output cannot fit in a single ByteArray.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException
     *   if the output or whole-input compression ratio exceeds [limits].
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun inflate(input: ByteArray, limits: ArchiveLimits, dictionary: ByteArray): ByteArray {
        val output = Output(limits, input.size)
        output.preload(dictionary)
        val end = inflateInto(input, 0, input.size, output)
        if (end != input.size) throw ArchiveFormatException("deflate: trailing bytes after stream", end.toLong())
        return output.takeByteArray()
    }

    /** Stream one raw DEFLATE stream's output to [sink]; return the byte count. */
    internal fun inflateTo(input: ByteArray, limits: ArchiveLimits, sink: ByteSink, deflate64: Boolean = false): Long {
        val output = Output(limits, input.size, sink = sink)
        val end = inflateInto(input, 0, input.size, output, deflate64)
        if (end != input.size) throw ArchiveFormatException("deflate: trailing bytes after stream", end.toLong())
        return output.finish()
    }

    /** ZIP's authoritative size constrains writes as well as the final stream length. */
    internal fun inflateExact(input: ByteArray, limits: ArchiveLimits, expectedSize: Long, deflate64: Boolean = false): ByteArray =
        inflateChecked(input, limits, expectedSize, deflate64)

    /** Inflate one complete Deflate64 stream, the format of ZIP method 9. */
    internal fun inflate64(input: ByteArray, limits: ArchiveLimits): ByteArray = inflateChecked(input, limits, null, true)

    private fun inflateChecked(input: ByteArray, limits: ArchiveLimits, expectedSize: Long?, deflate64: Boolean = false): ByteArray {
        val output = Output(limits, input.size, expectedSize)
        val end = inflateInto(input, 0, input.size, output, deflate64)
        if (end != input.size) throw ArchiveFormatException("deflate: trailing bytes after stream", end.toLong())
        if (expectedSize != null && output.size.toLong() != expectedSize) {
            throw ArchiveFormatException("deflate: output size ${output.size} differs from expected size $expectedSize", end.toLong())
        }
        return output.takeByteArray()
    }

    /**
     * Return the first byte after the final block, including its partial final byte. With
     * [keepHistory], matches may reach everything already in [output], as MSZIP's blocks do.
     */
    internal fun inflateInto(
        input: ByteArray, offset: Int, end: Int, output: Output, deflate64: Boolean = false, keepHistory: Boolean = false,
    ): Int {
        require(offset >= 0 && end >= offset && end <= input.size) { "Invalid DEFLATE input slice" }
        val s = State(input, offset, end, output, deflate64, keepHistory)
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

    /**
     * Shared output across gzip members; each State still has a fresh dictionary. With a
     * [sink], output streams: all but the last [WINDOW] bytes are written to the sink as the
     * buffer fills, and [finish] writes the rest. The limits are checked before every write
     * to the sink, so it never receives more than they allow.
     */
    internal class Output(
        private val limits: ArchiveLimits, private val compressedBytes: Int,
        private val expectedSize: Long? = null,
        sizeHint: Long? = expectedSize,
        private val sink: ByteSink? = null,
    ) {
        private val maximum: Int
        private val hint: Int
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
            hint = (sizeHint ?: 0).coerceIn(0, maximum.toLong()).toInt()
            // File-controlled sizes guide growth, but never reserve a large array
            // before any output has been decoded. Unlike expectedSize, a hint is
            // not an output bound (gzip ISIZE describes only its final member).
            // The input's own size is safe to reserve, since the input is already in
            // memory, and DEFLATE rarely shrinks data: stored and incompressible
            // streams then decode without growth copies.
            bytes = if (sink != null) ByteArray(STREAM_BUFFER + WINDOW)
                else ByteArray(minOf(maxOf(if (hint > 0) minOf(4096, hint) else 256, compressedBytes), maximum))
        }

        /** Bytes of preset dictionary at the start; they are history, not output. */
        private var base = 0

        /** Streaming: output bytes already written to the sink, and where unwritten output starts. */
        private var flushed = 0L
        private var pending = 0

        /** Where the current stream's reachable history starts; a State sets it, shifts move it. */
        var streamOrigin = 0

        private var running: Checksum? = null
        private var runningFrom = 0

        /** Output bytes produced so far, written to the sink or not. */
        val produced: Long get() = flushed + (size - pending)

        /** Start a checksum over the output that follows. */
        fun startChecksum(checksum: Checksum) {
            running = checksum
            runningFrom = size
        }

        /** The value of the checksum started by [startChecksum], over the output since. */
        fun checksumValue(): Long {
            val checksum = running ?: error("no checksum started")
            checksum.update(bytes, runningFrom, size - runningFrom)
            runningFrom = size
            running = null
            return checksum.value()
        }

        private fun ensure(extra: Int) {
            // Every allocation is capped by all three immutable budgets. Writes
            // within that capacity need no Long arithmetic or ratio division.
            if (extra <= bytes.size - size) return
            if (sink != null) return makeRoom(extra)
            val required = size.toLong() - base + extra
            limits.checkOutputSize(required)
            limits.checkRatio(compressedBytes.toLong(), required)
            if (expectedSize != null && required > expectedSize) {
                throw ArchiveFormatException("deflate: output size would exceed expected size $expectedSize")
            }
            val growthMaximum = if (required <= hint) hint else maximum
            fun shifted(value: Int) = (value.toLong() + base).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val capacity = byteArrayCapacity(bytes.size, size, extra, shifted(growthMaximum), shifted(hint))
            if (capacity != bytes.size) bytes = bytes.copyOf(capacity)
        }

        /** Write output up to buffer index [upTo] to the sink, checking the limits first. */
        private fun flush(upTo: Int) {
            if (upTo <= pending) return
            val count = upTo - pending
            val total = flushed + count
            limits.checkOutputSize(total)
            limits.checkRatio(compressedBytes.toLong(), total)
            if (expectedSize != null && total > expectedSize) {
                throw ArchiveFormatException("deflate: output size would exceed expected size $expectedSize")
            }
            running?.let { checksum ->
                if (runningFrom < upTo) {
                    checksum.update(bytes, runningFrom, upTo - runningFrom)
                    runningFrom = upTo
                }
            }
            sink!!.write(bytes, pending, count)
            flushed = total
            pending = upTo
        }

        /** Streaming: write out all but the window, move the window to the front, then fit [extra]. */
        private fun makeRoom(extra: Int) {
            val keepFrom = maxOf(0, size - WINDOW)
            flush(keepFrom)
            if (keepFrom > 0) {
                bytes.copyInto(bytes, 0, keepFrom, size)
                size -= keepFrom
                pending = maxOf(0, pending - keepFrom)
                streamOrigin = maxOf(0, streamOrigin - keepFrom)
                runningFrom = maxOf(0, runningFrom - keepFrom)
                base = maxOf(0, base - keepFrom)
            }
            if (extra > bytes.size - size) bytes = bytes.copyOf(size + extra)
        }

        /** Streaming: write the remaining output to the sink and return the total produced. */
        fun finish(): Long {
            flush(size)
            return produced
        }

        /** Put a preset [dictionary] before any output, as history that matches may reach. */
        fun preload(dictionary: ByteArray) {
            check(size == 0)
            if (dictionary.size > bytes.size) bytes = dictionary.copyOf(dictionary.size + bytes.size)
            else dictionary.copyInto(bytes)
            size = dictionary.size
            base = dictionary.size
            pending = dictionary.size
        }

        /** Bytes of history before the output; the dictionary length. */
        val history: Int get() = base

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
            } else if (distance == 1) {
                output.fill(output[source], size, end)
            } else if (length >= 32) {
                // Copy one whole period, then double only bytes already written: each copy is disjoint.
                output.copyInto(output, size, source, source + distance)
                var copied = distance
                while (copied < length) {
                    val count = minOf(copied, length - copied)
                    output.copyInto(output, size + copied, size, size + count)
                    copied += count
                }
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

        /** Snapshot the output without giving up ownership of its storage. */
        fun toByteArray(): ByteArray = bytes.copyOfRange(base, size)

        /** Transfer a finished buffer without retaining a mutable alias. */
        fun takeByteArray(): ByteArray {
            val result = if (base == 0 && size == bytes.size) bytes else bytes.copyOfRange(base, size)
            bytes = ByteArray(0)
            size = 0
            return result
        }
    }

    /** Streaming keeps this much output as history: Deflate64's 64 KiB window. */
    private const val WINDOW = 65536

    /** Streaming writes to the sink in chunks of about this size. */
    private const val STREAM_BUFFER = 1 shl 20

    /** Non-local "out of input" signal, replacing puff's setjmp/longjmp. */
    private class OutOfInput : Throwable()

    /** Per-stream input and bit cursor, with a dictionary starting at [outputStart]. */
    private class State(
        private val inBuf: ByteArray, offset: Int, private val end: Int,
        private val output: Output, val deflate64: Boolean, keepHistory: Boolean,
    ) {
        var position = offset
            private set
        var bitbuf = 0
        var bitcnt = 0
        val consumedPosition: Int get() = position - (bitcnt ushr 3)
        init {
            // A preset dictionary counts as earlier output that matches may reach.
            output.streamOrigin = if (keepHistory) 0 else output.size - output.history
        }
        val outcnt: Int get() = output.size - output.streamOrigin
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
                    val len = if (sym == 28 && s.deflate64) 3 + s.bits(16) else LENS[sym].toInt() + s.bits(LEXT[sym].toInt())

                    sym = decode(s, distcode)
                    if (sym < 0) return sym
                    val dist = when {
                        s.deflate64 -> DISTS64[sym] + s.bits(DEXT64[sym])
                        sym >= MAXDCODES -> return InflateError.INVALID_LITERAL_OR_DISTANCE_CODE.code
                        else -> DISTS[sym].toInt() + s.bits(DEXT[sym].toInt())
                    }
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

    // Deflate64's fixed block assigns codes 30 and 31 instead of reserving them.
    private val fixedDistance64 = Huffman(MAXDCODES64).also { table ->
        construct(table, ShortArray(MAXDCODES64) { 5 }, MAXDCODES64)
    }

    private fun fixed(s: State): Int = codes(s, fixedLiteral, if (s.deflate64) fixedDistance64 else fixedDistance)

    private fun dynamic(s: State): Int {
        val lengths = ShortArray(MAXCODES)

        val nlen = s.bits(5) + 257
        val ndist = s.bits(5) + 1
        val ncode = s.bits(4) + 4
        if (nlen > MAXLCODES || ndist > (if (s.deflate64) MAXDCODES64 else MAXDCODES)) {
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

        val distLengths = ShortArray(MAXDCODES64)
        for (i in 0 until ndist) distLengths[i] = lengths[nlen + i]
        val distcode = Huffman(MAXDCODES64)
        err = construct(distcode, distLengths, ndist)
        if (err != 0 && (err < 0 || ndist != distcode.count[0].toInt() + distcode.count[1].toInt())) {
            return InflateError.INVALID_DISTANCE_CODE_LENGTHS.code
        }

        return codes(s, lencode, distcode)
    }
}
