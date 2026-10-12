package io.github.yuroyami.archivekodec.checksum

/** SHA-1 (FIPS 180-4), for WIM's resource hashes; used to find and check data, not for security. */
internal class Sha1 {
    private val h = intArrayOf(0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476, 0xC3D2E1F0.toInt())
    private val block = ByteArray(64)
    private var blockSize = 0
    private var total = 0L
    private val w = IntArray(80)

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        var i = offset
        val end = offset + length
        total += length
        while (i < end) {
            val n = minOf(64 - blockSize, end - i)
            data.copyInto(block, blockSize, i, i + n)
            blockSize += n
            i += n
            if (blockSize == 64) { compress(); blockSize = 0 }
        }
    }

    fun digest(): ByteArray {
        val bits = total * 8
        update(byteArrayOf(0x80.toByte()))
        while (blockSize != 56) update(byteArrayOf(0))
        update(ByteArray(8) { (bits ushr (56 - 8 * it)).toByte() })
        return ByteArray(20) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
    }

    private fun compress() {
        for (t in 0 until 16) {
            w[t] = (block[4 * t].toInt() and 0xFF shl 24) or (block[4 * t + 1].toInt() and 0xFF shl 16) or
                (block[4 * t + 2].toInt() and 0xFF shl 8) or (block[4 * t + 3].toInt() and 0xFF)
        }
        for (t in 16 until 80) w[t] = (w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16]).rotateLeft(1)
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]; var e = h[4]
        for (t in 0 until 80) {
            val f: Int
            val k: Int
            when {
                t < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                t < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                t < 60 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt() }
                else -> { f = b xor c xor d; k = 0xCA62C1D6.toInt() }
            }
            val temp = a.rotateLeft(5) + f + e + k + w[t]
            e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e
    }

    companion object {
        fun of(data: ByteArray): ByteArray = Sha1().apply { update(data) }.digest()
    }
}
