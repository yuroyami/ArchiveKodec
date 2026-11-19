package io.github.yuroyami.archivekodec.checksum

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

import io.github.yuroyami.archivekodec.util.readI32Le

/**
 * xxHash32 (Yann Collet): the checksum used by the LZ4 frame format for its header
 * (HC byte) and optional block/content checks. Pure-Kotlin, one-shot. 32-bit wrapping
 * arithmetic done in `Int`; the result is exposed unsigned in the low 32 bits of a Long.
 *
 * Check value: `xxHash32("", seed=0) == 0x02CC5D05`.
 */
public object XxHash32 {

    private const val P1 = -1640531535   // 2654435761
    private const val P2 = -2048144777   // 2246822519
    private const val P3 = -1028477379   // 3266489917
    private const val P4 = 668265263
    private const val P5 = 374761393

    private fun rotl(x: Int, r: Int): Int = (x shl r) or (x ushr (32 - r))

    /**
     * Hash [length] bytes of [data] from [offset], with [seed] (LZ4 uses 0).
     * Omitting [length] selects the suffix; an empty slice at the end is valid.
     *
     * @throws IllegalArgumentException if the slice is outside [data].
     */
    @Throws(IllegalArgumentException::class)
    @JvmStatic
    @JvmOverloads
    public fun hash(data: ByteArray, offset: Int = 0, length: Int = data.size - offset, seed: Int = 0): Long {
        val end = checkedChecksumEnd(data.size, offset, length)
        var i = offset
        var h: Int

        if (length >= 16) {
            var v1 = seed + P1 + P2
            var v2 = seed + P2
            var v3 = seed
            var v4 = seed - P1
            val limit = end - 16
            while (i <= limit) {
                v1 = rotl(v1 + readI32Le(data, i) * P2, 13) * P1; i += 4
                v2 = rotl(v2 + readI32Le(data, i) * P2, 13) * P1; i += 4
                v3 = rotl(v3 + readI32Le(data, i) * P2, 13) * P1; i += 4
                v4 = rotl(v4 + readI32Le(data, i) * P2, 13) * P1; i += 4
            }
            h = rotl(v1, 1) + rotl(v2, 7) + rotl(v3, 12) + rotl(v4, 18)
        } else {
            h = seed + P5
        }

        h += length
        while (end - i >= 4) {
            h += readI32Le(data, i) * P3
            h = rotl(h, 17) * P4
            i += 4
        }
        while (i < end) {
            h += (data[i].toInt() and 0xFF) * P5
            h = rotl(h, 11) * P1
            i++
        }

        h = h xor (h ushr 15)
        h *= P2
        h = h xor (h ushr 13)
        h *= P3
        h = h xor (h ushr 16)
        return h.toLong() and 0xFFFFFFFFL
    }
}

/** xxHash32 with seed 0 over data given in pieces: what [XxHash32.hash] gives for all of it. */
internal class XxHash32Streaming {
    private val P1 = -1640531535
    private val P2 = -2048144777
    private val P3 = -1028477379
    private val P4 = 668265263
    private val P5 = 374761393
    private var v1 = P1 + P2
    private var v2 = P2
    private var v3 = 0
    private var v4 = -P1
    private val tail = ByteArray(16)
    private var tailSize = 0
    private var total = 0L

    private fun rotl(x: Int, r: Int): Int = (x shl r) or (x ushr (32 - r))

    private fun stripe(d: ByteArray, i: Int) {
        v1 = rotl(v1 + readI32Le(d, i) * P2, 13) * P1
        v2 = rotl(v2 + readI32Le(d, i + 4) * P2, 13) * P1
        v3 = rotl(v3 + readI32Le(d, i + 8) * P2, 13) * P1
        v4 = rotl(v4 + readI32Le(d, i + 12) * P2, 13) * P1
    }

    fun update(data: ByteArray, offset: Int, length: Int) {
        var p = offset
        val end = offset + length
        total += length
        if (tailSize > 0) {
            val n = minOf(16 - tailSize, end - p)
            data.copyInto(tail, tailSize, p, p + n)
            tailSize += n
            p += n
            if (tailSize < 16) return
            stripe(tail, 0)
            tailSize = 0
        }
        while (p <= end - 16) { stripe(data, p); p += 16 }
        data.copyInto(tail, 0, p, end)
        tailSize = end - p
    }

    fun value(): Long {
        var h = if (total >= 16) rotl(v1, 1) + rotl(v2, 7) + rotl(v3, 12) + rotl(v4, 18) else P5
        h += total.toInt()
        var i = 0
        while (tailSize - i >= 4) { h += readI32Le(tail, i) * P3; h = rotl(h, 17) * P4; i += 4 }
        while (i < tailSize) { h += (tail[i].toInt() and 0xFF) * P5; h = rotl(h, 11) * P1; i++ }
        h = h xor (h ushr 15); h *= P2; h = h xor (h ushr 13); h *= P3; h = h xor (h ushr 16)
        return h.toLong() and 0xFFFFFFFFL
    }
}
