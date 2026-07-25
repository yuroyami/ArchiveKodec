package io.github.yuroyami.kitearchive.checksum

/**
 * xxHash32 (Yann Collet) — the checksum used by the LZ4 frame format for its header
 * (HC byte) and optional block/content checks. Pure-Kotlin, one-shot. 32-bit wrapping
 * arithmetic done in `Int`; the result is exposed unsigned in the low 32 bits of a Long.
 *
 * Check value: `xxHash32("", seed=0) == 0x02CC5D05`.
 */
object XxHash32 {

    private const val P1 = -1640531535   // 2654435761
    private const val P2 = -2048144777   // 2246822519
    private const val P3 = -1028477379   // 3266489917
    private const val P4 = 668265263
    private const val P5 = 374761393

    private fun rotl(x: Int, r: Int): Int = (x shl r) or (x ushr (32 - r))

    /** Hash [length] bytes of [data] from [offset], with [seed] (LZ4 uses 0). */
    fun hash(data: ByteArray, offset: Int = 0, length: Int = data.size, seed: Int = 0): Long {
        var i = offset
        val end = offset + length
        var h: Int

        if (length >= 16) {
            var v1 = seed + P1 + P2
            var v2 = seed + P2
            var v3 = seed
            var v4 = seed - P1
            val limit = end - 16
            while (i <= limit) {
                v1 = rotl(v1 + le32(data, i) * P2, 13) * P1; i += 4
                v2 = rotl(v2 + le32(data, i) * P2, 13) * P1; i += 4
                v3 = rotl(v3 + le32(data, i) * P2, 13) * P1; i += 4
                v4 = rotl(v4 + le32(data, i) * P2, 13) * P1; i += 4
            }
            h = rotl(v1, 1) + rotl(v2, 7) + rotl(v3, 12) + rotl(v4, 18)
        } else {
            h = seed + P5
        }

        h += length
        while (i + 4 <= end) {
            h += le32(data, i) * P3
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

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)
}
