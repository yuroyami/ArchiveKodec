package io.github.yuroyami.archivekodec.checksum

/** SHA-1 (FIPS 180-4), for WIM's resource hashes and for HMAC-SHA1 in WinZip AES. */
internal class Sha1 private constructor(private val h: IntArray, private var total: Long) {
    constructor() : this(INITIAL.copyOf(), 0L)

    private val block = ByteArray(64)
    private var blockSize = 0
    private val w = IntArray(80)

    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        var i = offset
        val end = offset + length
        total += length
        if (blockSize > 0) {
            val n = minOf(64 - blockSize, end - i)
            data.copyInto(block, blockSize, i, i + n)
            blockSize += n
            i += n
            if (blockSize < 64) return
            compressBlock(block, 0)
            blockSize = 0
        }
        // Whole blocks are hashed where they are, without a copy.
        while (end - i >= 64) {
            compressBlock(data, i)
            i += 64
        }
        data.copyInto(block, 0, i, end)
        blockSize = end - i
    }

    /** Pad in place and return the 20-byte hash. The object is spent afterwards. */
    fun digest(): ByteArray {
        val bits = total * 8
        block[blockSize++] = 0x80.toByte()
        if (blockSize > 56) {
            block.fill(0, blockSize, 64)
            compressBlock(block, 0)
            blockSize = 0
        }
        block.fill(0, blockSize, 56)
        for (i in 0 until 8) block[56 + i] = (bits ushr (56 - 8 * i)).toByte()
        compressBlock(block, 0)
        return ByteArray(20) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
    }

    private fun compressBlock(source: ByteArray, at: Int) {
        for (t in 0 until 16) w[t] = readIntBe(source, at + 4 * t)
        compress(h, w)
    }

    companion object {
        private val INITIAL = intArrayOf(0x67452301, 0xEFCDAB89.toInt(), 0x98BADCFE.toInt(), 0x10325476, 0xC3D2E1F0.toInt())

        fun of(data: ByteArray): ByteArray = Sha1().apply { update(data) }.digest()

        /** The chaining state after one 64-byte [block], such as an HMAC key block. */
        fun stateAfter(block: ByteArray): IntArray {
            val h = INITIAL.copyOf()
            val w = IntArray(80)
            for (t in 0 until 16) w[t] = readIntBe(block, 4 * t)
            compress(h, w)
            return h
        }

        /** Continue a hash whose first [length] bytes, a multiple of 64, left [state]. */
        fun resume(state: IntArray, length: Long): Sha1 = Sha1(state.copyOf(), length)

        /** Compress one block into [h]. [w] holds the block's 16 big-endian words; indexes 16 to 79 are overwritten. */
        fun compress(h: IntArray, w: IntArray) {
            for (t in 16 until 80) w[t] = (w[t - 3] xor w[t - 8] xor w[t - 14] xor w[t - 16]).rotateLeft(1)
            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            // Four groups of 20 rounds, each with its own function and constant.
            for (t in 0 until 20) {
                val temp = a.rotateLeft(5) + ((b and c) or (b.inv() and d)) + e + 0x5A827999 + w[t]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
            }
            for (t in 20 until 40) {
                val temp = a.rotateLeft(5) + (b xor c xor d) + e + 0x6ED9EBA1 + w[t]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
            }
            for (t in 40 until 60) {
                val temp = a.rotateLeft(5) + ((b and c) or (b and d) or (c and d)) + e + 0x8F1BBCDC.toInt() + w[t]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
            }
            for (t in 60 until 80) {
                val temp = a.rotateLeft(5) + (b xor c xor d) + e + 0xCA62C1D6.toInt() + w[t]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
            }
            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
        }

        fun readIntBe(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF shl 24) or (b[o + 1].toInt() and 0xFF shl 16) or
                (b[o + 2].toInt() and 0xFF shl 8) or (b[o + 3].toInt() and 0xFF)
    }
}
