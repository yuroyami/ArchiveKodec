package io.github.yuroyami.archivekodec.crypto

import io.github.yuroyami.archivekodec.checksum.Sha1

/** HMAC-SHA1 (RFC 2104) and PBKDF2-HMAC-SHA1 (RFC 8018) on the core's SHA-1. */
internal object HmacSha1 {
    /** The SHA-1 states after the inner and the outer key block of one key. */
    class Key(key: ByteArray) {
        val inner: IntArray
        val outer: IntArray

        init {
            val k = (if (key.size > 64) Sha1.of(key) else key).copyOf(64)
            inner = Sha1.stateAfter(ByteArray(64) { (k[it].toInt() xor 0x36).toByte() })
            outer = Sha1.stateAfter(ByteArray(64) { (k[it].toInt() xor 0x5C).toByte() })
            k.fill(0)
        }

        fun mac(data: ByteArray): ByteArray {
            val innerHash = Sha1.resume(inner, 64).apply { update(data) }.digest()
            return Sha1.resume(outer, 64).apply { update(innerHash) }.digest()
        }

        /** Wipe both states. They stand in for the key, so they are as secret as the key. */
        fun clear() {
            inner.fill(0)
            outer.fill(0)
        }
    }

    fun mac(key: ByteArray, data: ByteArray): ByteArray {
        val state = Key(key)
        try {
            return state.mac(data)
        } finally {
            state.clear()
        }
    }

    /**
     * PBKDF2 with HMAC-SHA1. After the first round of each block, a round hashes two fixed
     * blocks in place, so the loop allocates nothing.
     */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        require(iterations >= 1) { "PBKDF2 needs at least 1 iteration, not $iterations" }
        require(length >= 0) { "PBKDF2 output length must not be negative: $length" }
        val key = Key(password)
        val out = ByteArray(length)
        val w = IntArray(80)
        val u = IntArray(5)
        val t = IntArray(5)
        val inner = IntArray(5)
        val first = salt.copyOf(salt.size + 4)
        var index = 1
        var offset = 0
        try {
            while (offset < length) {
                for (i in 0 until 4) first[salt.size + i] = (index ushr (24 - 8 * i)).toByte()
                val u1 = key.mac(first)
                for (i in 0 until 5) {
                    u[i] = Sha1.readIntBe(u1, 4 * i)
                    t[i] = u[i]
                }
                u1.fill(0)
                for (round in 2..iterations) {
                    key.inner.copyInto(inner)
                    loadDigestBlock(w, u)
                    Sha1.compress(inner, w)
                    key.outer.copyInto(u)
                    loadDigestBlock(w, inner)
                    Sha1.compress(u, w)
                    for (i in 0 until 5) t[i] = t[i] xor u[i]
                }
                val n = minOf(20, length - offset)
                for (i in 0 until n) out[offset + i] = (t[i / 4] ushr (24 - 8 * (i % 4))).toByte()
                offset += n
                index++
            }
            return out
        } finally {
            key.clear()
            for (state in arrayOf(w, u, t, inner)) state.fill(0)
        }
    }

    /** A 20-byte message after one 64-byte key block, padded: 84 bytes, or 672 bits, in all. */
    private fun loadDigestBlock(w: IntArray, digest: IntArray) {
        digest.copyInto(w, 0, 0, 5)
        w[5] = Int.MIN_VALUE
        w.fill(0, 6, 15)
        w[15] = (64 + 20) * 8
    }
}
