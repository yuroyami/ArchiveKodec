package io.github.yuroyami.archivekodec.codec.packbits

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.DecodeResult
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * PackBits run-length coding (TIFF 6.0, section 9) and PDF's `RunLengthDecode` (ISO 32000-1,
 * 7.4.5), which differ only in the length byte 128.
 *
 * A length byte n from 0 to 127 copies the next n + 1 bytes. A length byte from 129 to 255 repeats
 * the next byte 257 - n times. [PackBitsOptions.variant] says what 128 means.
 *
 * ```kotlin
 * val row = PackBits.decode(strip, PackBitsOptions { expectedSize = rowBytes.toLong() }).output
 * val stream = PackBits.encode(data, PackBitsOptions.PDF_RUN_LENGTH)
 * ```
 *
 * Both directions hold no state between calls, so threads can share them.
 */
public object PackBits {

    /**
     * Decode [input] with [PackBitsOptions.DEFAULT].
     *
     * @throws ArchiveFormatException if a literal run or a repeat runs past the end of the input.
     * @throws DecompressionBombException if the output or the ratio exceeds the default limits.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun decode(input: ByteArray): ByteArray = decode(input, 0, input.size, PackBitsOptions.DEFAULT).output

    /**
     * Decode the whole of [input] under [options]. See the overload with a range.
     *
     * @throws ArchiveFormatException for damaged input, unless [PackBitsOptions.keepPartialOutput] is set.
     * @throws DecompressionBombException if the output or the ratio exceeds [PackBitsOptions.limits].
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun decode(input: ByteArray, options: PackBitsOptions): DecodeResult = decode(input, 0, input.size, options)

    /**
     * Decode the data that starts at [offset] and lies within the next [length] bytes of [input].
     *
     * Without [PackBitsOptions.expectedSize], the data ends where the input ends, or at the length
     * byte 128 of [PackBitsVariant.PDF_RUN_LENGTH]. With it, the decode stops once it has that many
     * bytes, and fewer bytes is damage. With [PackBitsOptions.keepPartialOutput], damage gives the
     * bytes decoded before it, and [DecodeResult.error] holds the failure. A limit always throws.
     *
     * @throws IllegalArgumentException if [offset] and [length] do not lie inside [input].
     * @throws ArchiveFormatException for damaged input, unless [PackBitsOptions.keepPartialOutput] is set.
     * @throws DecompressionBombException if the output or the ratio exceeds [PackBitsOptions.limits].
     */
    @Throws(ArchiveException::class, IllegalArgumentException::class)
    @JvmStatic
    public fun decode(input: ByteArray, offset: Int, length: Int, options: PackBitsOptions): DecodeResult {
        require(offset >= 0 && length >= 0 && offset <= input.size && length <= input.size - offset) {
            "invalid PackBits slice: offset $offset, length $length, size ${input.size}"
        }
        val limits = options.limits
        val expected = options.expectedSize
        if (expected >= 0) limits.checkOutputSize(expected)
        if (expected > Int.MAX_VALUE - 8L) throw DecompressionBombException("packbits: expected size $expected exceeds the ByteArray maximum")
        val endsAt128 = options.variant == PackBitsVariant.PDF_RUN_LENGTH

        val cap = if (expected >= 0) expected.toInt() else Int.MAX_VALUE
        // The expected size is only a claim: grow toward it under the limits instead of reserving it.
        var out = ByteArray(minOf(maxOf(length * 2, 64), 1 shl 20, cap))
        var size = 0
        var i = offset
        val end = offset + length
        var error: ArchiveFormatException? = null

        fun ensure(extra: Int) {
            val needed = minOf(size.toLong() + extra, cap.toLong())
            if (needed <= out.size) return
            limits.checkOutputSize(needed)
            limits.checkRatio(length.toLong(), needed)
            if (needed > Int.MAX_VALUE - 8L) throw DecompressionBombException("packbits: output exceeds the ByteArray maximum")
            out = out.copyOf(minOf(maxOf(out.size.toLong() * 2, needed), Int.MAX_VALUE - 8L, cap.toLong()).toInt())
        }

        while (size < cap && i < end) {
            val n = input[i].toInt()
            when {
                n >= 0 -> {
                    val run = n + 1
                    if (run > end - i - 1) {
                        error = ArchiveFormatException("packbits: a literal run of $run bytes passes the end of the data", i.toLong())
                        break
                    }
                    val take = minOf(run, cap - size)
                    ensure(take)
                    input.copyInto(out, size, i + 1, i + 1 + take)
                    size += take
                    i += 1 + run
                }
                n == -128 -> {
                    i++
                    if (endsAt128) break
                }
                else -> {
                    if (i + 1 >= end) {
                        error = ArchiveFormatException("packbits: a repeat has no byte to repeat", i.toLong())
                        break
                    }
                    val take = minOf(1 - n, cap - size)
                    ensure(take)
                    out.fill(input[i + 1], size, size + take)
                    size += take
                    i += 2
                }
            }
        }
        if (error == null && expected >= 0 && size < expected) {
            error = ArchiveFormatException("packbits: data ended after $size of $expected bytes", i.toLong())
        }
        if (error != null && !options.keepPartialOutput) throw error
        limits.checkOutputSize(size.toLong())
        limits.checkRatio(length.toLong(), size.toLong())
        return DecodeResult(if (size == out.size) out else out.copyOf(size), i - offset, error)
    }

    /**
     * Encode [data]: a run of three or more equal bytes becomes a repeat, everything else literal
     * runs, each at most 128 bytes long. [PackBitsVariant.PDF_RUN_LENGTH] appends the length byte 128.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    @JvmOverloads
    public fun encode(data: ByteArray, options: PackBitsOptions = PackBitsOptions.DEFAULT): ByteArray {
        val out = ByteArrayBuilder(data.size + data.size / 128 + 2)
        var i = 0
        val n = data.size
        while (i < n) {
            var run = 1
            while (i + run < n && run < 128 && data[i + run] == data[i]) run++
            if (run >= 3) {
                out.append((1 - run).toByte())
                out.append(data[i])
                i += run
                continue
            }
            // A literal run ends before the next run of three equal bytes, or at 128 bytes.
            val start = i
            i += run
            while (i < n && i - start < 128) {
                if (i + 2 < n && data[i] == data[i + 1] && data[i] == data[i + 2]) break
                i++
            }
            if (i - start > 128) i = start + 128
            out.append((i - start - 1).toByte())
            out.append(data, start, i - start)
        }
        if (options.variant == PackBitsVariant.PDF_RUN_LENGTH) out.append((-128).toByte())
        return out.toByteArray()
    }
}
