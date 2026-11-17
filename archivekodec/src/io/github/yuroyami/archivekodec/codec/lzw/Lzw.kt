package io.github.yuroyami.archivekodec.codec.lzw

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.DecodeResult
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * LZW (Lempel-Ziv-Welch) with 9- to 12-bit codes, in the forms that PDF, TIFF and GIF use.
 * [LzwOptions] picks the form: the bit order, the literal size and early change.
 *
 * ```kotlin
 * val bytes = Lzw.decode(stream)                                      // PDF LZWDecode, TIFF LZW
 * val indices = Lzw.decode(data, LzwOptions.gif(minimumCodeSize)).output
 * val packed = Lzw.encode(indices, LzwOptions.gif(minimumCodeSize))
 * ```
 *
 * The decoder accepts data with or without a leading clear code and with or without the end code.
 * A table that fills up stays at 12 bits until the next clear code, as GIF allows. The encoder
 * starts with a clear code, clears again when the table is full, and ends with the end code.
 * Both hold no state between calls, so threads can share them.
 */
public object Lzw {

    private const val MAX_BITS = 12
    private const val TABLE = 1 shl MAX_BITS

    /**
     * Decode [input] with [LzwOptions.DEFAULT], the LZW of PDF and TIFF.
     *
     * @throws ArchiveFormatException for a code past the table or a first code that is not a literal.
     * @throws DecompressionBombException if the output or the ratio exceeds the default limits.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun decode(input: ByteArray): ByteArray = decode(input, 0, input.size, LzwOptions.DEFAULT).output

    /**
     * Decode the whole of [input] under [options]. See the overload with a range.
     *
     * @throws ArchiveFormatException for damaged input, unless [LzwOptions.keepPartialOutput] is set.
     * @throws DecompressionBombException if the output or the ratio exceeds [LzwOptions.limits].
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun decode(input: ByteArray, options: LzwOptions): DecodeResult = decode(input, 0, input.size, options)

    /**
     * Decode the LZW data that starts at [offset] and lies within the next [length] bytes of
     * [input], under [options].
     *
     * Without [LzwOptions.expectedSize], the data ends at its end code or where the input ends,
     * since many PDF writers leave the end code out. With it, the decode stops once it has that
     * many bytes, and fewer bytes is damage. With [LzwOptions.keepPartialOutput], damage gives the
     * bytes decoded before it, and [DecodeResult.error] holds the failure. A limit always throws.
     *
     * @throws IllegalArgumentException if [offset] and [length] do not lie inside [input].
     * @throws ArchiveFormatException for damaged input, unless [LzwOptions.keepPartialOutput] is set.
     * @throws DecompressionBombException if the output or the ratio exceeds [LzwOptions.limits].
     */
    @Throws(ArchiveException::class, IllegalArgumentException::class)
    @JvmStatic
    public fun decode(input: ByteArray, offset: Int, length: Int, options: LzwOptions): DecodeResult {
        require(offset >= 0 && length >= 0 && offset <= input.size && length <= input.size - offset) {
            "invalid LZW slice: offset $offset, length $length, size ${input.size}"
        }
        val limits = options.limits
        val expected = options.expectedSize
        if (expected >= 0) limits.checkOutputSize(expected)
        if (expected > Int.MAX_VALUE - 8L) throw DecompressionBombException("lzw: expected size $expected exceeds the ByteArray maximum")

        val literals = 1 shl options.literalBits
        val clear = literals
        val endCode = literals + 1
        val firstFree = literals + 2
        val minWidth = options.literalBits + 1
        val early = if (options.earlyChange) 1 else 0
        val lsb = options.bitOrder == LzwBitOrder.LSB_FIRST

        val prefix = IntArray(TABLE)
        val suffix = ByteArray(TABLE)
        val first = ByteArray(TABLE)
        val size = IntArray(TABLE)
        for (i in 0 until literals) {
            suffix[i] = i.toByte()
            first[i] = i.toByte()
            size[i] = 1
        }

        val out = Output(expected, length, limits)
        var width = minWidth
        var next = firstFree
        var previous = -1
        var pos = offset
        val end = offset + length
        var bits = 0
        var count = 0
        var error: ArchiveFormatException? = null

        decode@ while (expected < 0 || out.size < expected) {
            // Read one code.
            while (count < width) {
                if (pos == end) {
                    if (expected >= 0) {
                        error = ArchiveFormatException("lzw: data ended after ${out.size} of $expected bytes", pos.toLong())
                    }
                    break@decode
                }
                val b = input[pos++].toInt() and 0xFF
                if (lsb) bits = bits or (b shl count) else bits = (bits shl 8) or b
                count += 8
            }
            val code: Int
            if (lsb) {
                code = bits and ((1 shl width) - 1)
                bits = bits ushr width
            } else {
                code = (bits ushr (count - width)) and ((1 shl width) - 1)
                bits = bits and ((1 shl (count - width)) - 1)
            }
            count -= width

            if (code == clear) {
                width = minWidth
                next = firstFree
                previous = -1
                continue
            }
            if (code == endCode) {
                if (expected >= 0 && out.size < expected) {
                    error = ArchiveFormatException("lzw: end code after ${out.size} of $expected bytes", (pos - count / 8).toLong())
                }
                break
            }
            if (previous < 0) {
                if (code >= literals) {
                    error = ArchiveFormatException("lzw: first code $code after a clear is not a literal", (pos - count / 8).toLong())
                    break
                }
                out.append(code.toByte())
                previous = code
                continue
            }
            val added: Byte
            if (code < next) {
                out.appendChain(code, prefix, suffix, size)
                added = first[code]
            } else if (code == next && next < TABLE) {
                // The code being defined right now: the previous string and its own first byte.
                out.appendChain(previous, prefix, suffix, size)
                out.append(first[previous])
                added = first[previous]
            } else {
                error = ArchiveFormatException("lzw: code $code is past the table ($next)", (pos - count / 8).toLong())
                break
            }
            if (next < TABLE) {
                prefix[next] = previous
                suffix[next] = added
                first[next] = first[previous]
                size[next] = size[previous] + 1
                next++
                if (next + early == (1 shl width) && width < MAX_BITS) width++
            }
            previous = code
        }

        val consumed = pos - offset - count / 8
        if (error != null && !options.keepPartialOutput) throw error
        return DecodeResult(out.toByteArray(), consumed, error)
    }

    /**
     * Encode [data] under [options]: a clear code first, a clear code whenever the table is full,
     * and the end code last. With [LzwOptions.earlyChange], the table clears at 4094 codes, as
     * libtiff does; without it, at 4096 codes.
     *
     * @throws IllegalArgumentException if a byte of [data] is not below `2^literalBits`.
     */
    @Throws(IllegalArgumentException::class)
    @JvmStatic
    @JvmOverloads
    public fun encode(data: ByteArray, options: LzwOptions = LzwOptions.DEFAULT): ByteArray {
        val literals = 1 shl options.literalBits
        if (literals < 256) {
            for (i in data.indices) {
                require((data[i].toInt() and 0xFF) < literals) {
                    "LZW byte ${data[i].toInt() and 0xFF} at $i is not below 2^${options.literalBits}"
                }
            }
        }
        val clear = literals
        val endCode = literals + 1
        val firstFree = literals + 2
        val minWidth = options.literalBits + 1
        val early = if (options.earlyChange) 1 else 0
        val full = if (options.earlyChange) TABLE - 2 else TABLE
        val out = BitWriter(options.bitOrder == LzwBitOrder.LSB_FIRST, data.size / 2 + 16)
        val table = CodeTable()

        var width = minWidth
        var next = firstFree
        out.write(clear, width)
        if (data.isEmpty()) {
            out.write(endCode, width)
            return out.finish()
        }
        var current = data[0].toInt() and 0xFF
        var written = 0
        for (i in 1 until data.size) {
            val k = data[i].toInt() and 0xFF
            val found = table.get(current, k)
            if (found >= 0) {
                current = found
                continue
            }
            out.write(current, width)
            written++
            if (next < full) {
                table.put(current, k, next++)
                // The decoder defines each code one code later than the encoder, so the encoder
                // widens one code later too: at `next > 2^width - early`, not at equality.
                if (next > (1 shl width) - early && width < MAX_BITS) width++
            } else {
                out.write(clear, width)
                table.clear()
                width = minWidth
                next = firstFree
                written = 0
            }
            current = k
        }
        out.write(current, width)
        // Reading that last code, the decoder defines one more code and may widen before the end
        // code, so the encoder widens with it.
        if (written > 0 && next < TABLE && next + early == (1 shl width) && width < MAX_BITS) width++
        out.write(endCode, width)
        return out.finish()
    }

    /** The decoder's output: a fixed array when the size is known, else a growing one under the limits. */
    private class Output(expected: Long, private val inputLength: Int, private val limits: ArchiveLimits) {
        private val cap: Int = if (expected >= 0) expected.toInt() else Int.MAX_VALUE
        private var buf = ByteArray(if (expected >= 0) expected.toInt() else minOf(maxOf(inputLength * 3, 256), 1 shl 20))
        var size = 0
            private set

        fun append(b: Byte) {
            if (size >= cap) return
            ensure(1)
            buf[size++] = b
        }

        /** Write [code]'s string, last byte first, clipped to the expected size. */
        fun appendChain(code: Int, prefix: IntArray, suffix: ByteArray, lengths: IntArray) {
            val n = lengths[code]
            ensure(n)
            val stop = size + n
            var at = stop - 1
            var c = code
            while (at >= size) {
                if (at < cap) buf[at] = suffix[c]
                c = prefix[c]
                at--
            }
            size = minOf(stop, cap)
        }

        private fun ensure(extra: Int) {
            val needed = size.toLong() + extra
            if (needed <= buf.size || cap != Int.MAX_VALUE) return
            limits.checkOutputSize(needed)
            limits.checkRatio(inputLength.toLong(), needed)
            if (needed > Int.MAX_VALUE - 8L) throw DecompressionBombException("lzw: output exceeds the ByteArray maximum")
            var grown = buf.size.toLong() * 2
            if (grown < needed) grown = needed
            buf = buf.copyOf(minOf(grown, Int.MAX_VALUE - 8L).toInt())
        }

        fun toByteArray(): ByteArray {
            limits.checkOutputSize(size.toLong())
            limits.checkRatio(inputLength.toLong(), size.toLong())
            return if (size == buf.size) buf else buf.copyOf(size)
        }
    }

    /** Open-addressing map from (prefix code, next byte) to code, small enough to clear per table. */
    private class CodeTable {
        private val keys = IntArray(SLOTS) { -1 }
        private val values = IntArray(SLOTS)

        fun get(prefix: Int, byte: Int): Int {
            val key = (prefix shl 8) or byte
            var slot = hash(key)
            while (true) {
                val k = keys[slot]
                if (k == -1) return -1
                if (k == key) return values[slot]
                slot = (slot + 1) and (SLOTS - 1)
            }
        }

        fun put(prefix: Int, byte: Int, code: Int) {
            val key = (prefix shl 8) or byte
            var slot = hash(key)
            while (keys[slot] != -1) slot = (slot + 1) and (SLOTS - 1)
            keys[slot] = key
            values[slot] = code
        }

        fun clear() = keys.fill(-1)

        private fun hash(key: Int): Int = (key * -0x61c88647 ushr 19) and (SLOTS - 1)

        private companion object {
            const val SLOTS = 1 shl 13
        }
    }

    /** Packs codes into bytes in either bit order. */
    private class BitWriter(private val lsb: Boolean, capacity: Int) {
        private val out = ByteArrayBuilder(capacity)
        private var acc = 0
        private var count = 0

        fun write(code: Int, width: Int) {
            if (lsb) {
                acc = acc or (code shl count)
                count += width
                while (count >= 8) {
                    out.append(acc.toByte())
                    acc = acc ushr 8
                    count -= 8
                }
            } else {
                acc = (acc shl width) or code
                count += width
                while (count >= 8) {
                    out.append((acc ushr (count - 8)).toByte())
                    count -= 8
                }
                acc = acc and ((1 shl count) - 1)
            }
        }

        fun finish(): ByteArray {
            if (count > 0) out.append((if (lsb) acc else acc shl (8 - count)).toByte())
            return out.toByteArray()
        }
    }
}
