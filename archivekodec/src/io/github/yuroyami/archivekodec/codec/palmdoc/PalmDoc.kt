package io.github.yuroyami.archivekodec.codec.palmdoc

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.DecodeResult
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * PalmDOC compression, the LZ77 variant of PalmDOC e-books and of MOBI text records
 * (compression type 2).
 *
 * Each byte of the data is one of these:
 * - 0x00 or 0x09 to 0x7F: the byte itself.
 * - 0x01 to 0x08: that many raw bytes follow.
 * - 0x80 to 0xBF: with the next byte, a copy of 3 to 10 bytes from up to 2047 bytes back.
 * - 0xC0 to 0xFF: a space, then the byte with its top bit cleared.
 *
 * ```kotlin
 * val text = PalmDoc.decode(record)                 // one record, without its trailing entries
 * val record = PalmDoc.encode(text.copyOfRange(0, 4096))
 * ```
 *
 * A MOBI writer compresses each text record of at most 4096 bytes on its own, because a copy
 * cannot reach into the record before. Both directions hold no state, so threads can share them.
 */
public object PalmDoc {

    private const val WINDOW = 2047
    private const val MIN_MATCH = 3
    private const val MAX_MATCH = 10
    private const val HASH_BITS = 12
    private const val MAX_CHAIN = 128

    /**
     * Decode [input] with [ArchiveLimits.DEFAULT].
     *
     * @throws ArchiveFormatException if a raw run or a copy passes the end of the data, or a copy
     *   reaches before the start of the output.
     * @throws DecompressionBombException if the output exceeds the default limits.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun decode(input: ByteArray): ByteArray = decode(input, 0, input.size).output

    /**
     * Decode the [length] bytes of [input] that start at [offset].
     *
     * With [keepPartialOutput], damage gives the bytes decoded before it, and [DecodeResult.error]
     * holds the failure. A raw run cut short by the end of the data keeps the bytes that are there,
     * as Mobipocket readers do. A limit always throws.
     *
     * @throws IllegalArgumentException if [offset] and [length] do not lie inside [input].
     * @throws ArchiveFormatException for damaged input, unless [keepPartialOutput] is set.
     * @throws DecompressionBombException if the output exceeds [limits].
     */
    @Throws(ArchiveException::class, IllegalArgumentException::class)
    @JvmStatic
    @JvmOverloads
    public fun decode(
        input: ByteArray,
        offset: Int,
        length: Int,
        limits: ArchiveLimits = ArchiveLimits.DEFAULT,
        keepPartialOutput: Boolean = false,
    ): DecodeResult {
        require(offset >= 0 && length >= 0 && offset <= input.size && length <= input.size - offset) {
            "invalid PalmDOC slice: offset $offset, length $length, size ${input.size}"
        }
        val max = limits.maxUncompressedBytes
        val out = ByteArrayBuilder(minOf(length.toLong() * 2 + 16, max, 1L shl 20).toInt().coerceAtLeast(16))
        val end = offset + length
        var i = offset
        var error: ArchiveFormatException? = null

        fun grow(extra: Int) {
            val needed = out.size.toLong() + extra
            if (needed > max) limits.checkOutputSize(needed)
            if (needed > Int.MAX_VALUE - 8L) throw DecompressionBombException("palmdoc: output exceeds the ByteArray maximum")
        }

        while (i < end) {
            val at = i
            val c = input[i++].toInt() and 0xFF
            when {
                c in 1..8 -> {
                    val n = minOf(c, end - i)
                    grow(n)
                    out.append(input, i, n)
                    i += n
                    if (n < c) {
                        error = ArchiveFormatException("palmdoc: a run of $c raw bytes passes the end of the data", at.toLong())
                        break
                    }
                }
                c < 0x80 -> { grow(1); out.append(c.toByte()) }
                c >= 0xC0 -> {
                    grow(2)
                    out.append(' '.code.toByte())
                    out.append((c xor 0x80).toByte())
                }
                else -> {
                    if (i >= end) {
                        error = ArchiveFormatException("palmdoc: a copy has no second byte", at.toLong())
                        break
                    }
                    val v = (c shl 8) or (input[i++].toInt() and 0xFF)
                    val distance = (v ushr 3) and 0x7FF
                    val count = (v and 7) + MIN_MATCH
                    if (distance == 0 || distance > out.size) {
                        error = ArchiveFormatException("palmdoc: a copy from $distance bytes back reaches before the output", at.toLong())
                        break
                    }
                    grow(count)
                    out.copyBack(distance, count)
                }
            }
        }
        val consumed = (if (error != null) i else end) - offset
        limits.checkRatio(length.toLong(), out.size.toLong())
        if (error != null && !keepPartialOutput) throw error
        return DecodeResult(out.toByteArray(), consumed, error)
    }

    /**
     * Encode [data]. Copies are found with hash chains over the last 2047 bytes, a space before a
     * byte from 0x40 to 0x7F becomes one byte, and bytes that need it go into raw runs.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun encode(data: ByteArray): ByteArray {
        val n = data.size
        val out = ByteArrayBuilder(n + n / 8 + 16)
        val head = IntArray(1 shl HASH_BITS) { -1 }
        val prev = IntArray(n.coerceAtLeast(1))
        var inserted = 0

        fun hash(p: Int): Int {
            val h = ((data[p].toInt() and 0xFF) shl 16) or ((data[p + 1].toInt() and 0xFF) shl 8) or (data[p + 2].toInt() and 0xFF)
            return (h * -0x61c88647) ushr (32 - HASH_BITS)
        }

        fun insertUpTo(p: Int) {
            while (inserted < p && inserted + MIN_MATCH <= n) {
                val h = hash(inserted)
                prev[inserted] = head[h]
                head[h] = inserted
                inserted++
            }
            if (inserted < p) inserted = p
        }

        var i = 0
        while (i < n) {
            insertUpTo(i)
            // A copy: the longest match of 3 to 10 bytes within the window.
            if (i + MIN_MATCH <= n) {
                val limit = minOf(MAX_MATCH, n - i)
                var bestLength = 0
                var bestDistance = 0
                var candidate = head[hash(i)]
                var steps = 0
                while (candidate >= 0 && i - candidate <= WINDOW && steps++ < MAX_CHAIN) {
                    var k = 0
                    while (k < limit && data[candidate + k] == data[i + k]) k++
                    if (k > bestLength) {
                        bestLength = k
                        bestDistance = i - candidate
                        if (k == limit) break
                    }
                    candidate = prev[candidate]
                }
                if (bestLength >= MIN_MATCH) {
                    val v = 0x8000 or (bestDistance shl 3) or (bestLength - MIN_MATCH)
                    out.append((v ushr 8).toByte())
                    out.append(v.toByte())
                    i += bestLength
                    continue
                }
            }
            val c = data[i].toInt() and 0xFF
            if (c == 0x20 && i + 1 < n && (data[i + 1].toInt() and 0xFF) in 0x40..0x7F) {
                out.append((data[i + 1].toInt() xor 0x80).toByte())
                i += 2
                continue
            }
            if (c == 0 || c in 0x09..0x7F) {
                out.append(c.toByte())
                i++
                continue
            }
            // A raw run: up to 8 bytes in a row that cannot stand for themselves.
            var j = i + 1
            while (j < n && j - i < 8) {
                val d = data[j].toInt() and 0xFF
                if (d == 0 || d in 0x09..0x7F) break
                j++
            }
            out.append((j - i).toByte())
            out.append(data, i, j - i)
            i = j
        }
        return out.toByteArray()
    }
}
