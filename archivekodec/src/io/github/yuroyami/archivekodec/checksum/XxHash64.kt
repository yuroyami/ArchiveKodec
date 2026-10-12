package io.github.yuroyami.archivekodec.checksum

/** xxHash64 with seed 0, for the zstd content checksum (its low 32 bits). */
internal object XxHash64 {
    private const val P1 = -7046029288634856825L // 11400714785074694791
    private const val P2 = -4417276706812531889L // 14029467366897019727
    private const val P3 = 1609587929392839161L
    private const val P4 = -8796714831421723037L // 9650029242287828579
    private const val P5 = 2870177450012600261L

    private fun long(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = v shl 8 or (b[i + k].toLong() and 0xFF)
        return v
    }

    private fun int(b: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 3 downTo 0) v = v shl 8 or (b[i + k].toLong() and 0xFF)
        return v
    }

    private fun round(acc: Long, input: Long): Long = (acc + input * P2).rotateLeft(31) * P1

    private fun merge(acc: Long, value: Long): Long = (acc xor round(0, value)) * P1 + P4

    fun hash(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Long {
        val end = offset + length
        var p = offset
        var h: Long
        if (length >= 32) {
            var v1 = P1 + P2
            var v2 = P2
            var v3 = 0L
            var v4 = -P1
            while (p <= end - 32) {
                v1 = round(v1, long(data, p)); v2 = round(v2, long(data, p + 8))
                v3 = round(v3, long(data, p + 16)); v4 = round(v4, long(data, p + 24))
                p += 32
            }
            h = v1.rotateLeft(1) + v2.rotateLeft(7) + v3.rotateLeft(12) + v4.rotateLeft(18)
            h = merge(h, v1); h = merge(h, v2); h = merge(h, v3); h = merge(h, v4)
        } else {
            h = P5
        }
        h += length.toLong()
        while (p <= end - 8) {
            h = (h xor round(0, long(data, p))).rotateLeft(27) * P1 + P4
            p += 8
        }
        if (p <= end - 4) {
            h = (h xor (int(data, p) * P1)).rotateLeft(23) * P2 + P3
            p += 4
        }
        while (p < end) {
            h = (h xor ((data[p].toLong() and 0xFF) * P5)).rotateLeft(11) * P1
            p++
        }
        h = h xor (h ushr 33)
        h *= P2
        h = h xor (h ushr 29)
        h *= P3
        return h xor (h ushr 32)
    }
}
