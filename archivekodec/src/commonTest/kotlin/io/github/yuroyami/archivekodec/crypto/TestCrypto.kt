package io.github.yuroyami.archivekodec.crypto

import io.github.yuroyami.archivekodec.ExperimentalArchiveImplementation

/**
 * A plain-Kotlin [CryptoProvider] for tests only: table-driven AES, SHA-1, HMAC and PBKDF2
 * written straight from FIPS 197, FIPS 180-4 and RFC 2104/8018. It is slow and not constant
 * time, which is why the library itself ships none of it. The JVM tests check it against
 * javax.crypto.
 */
@OptIn(ExperimentalArchiveImplementation::class)
internal class TestCrypto(private var seed: Long = 1) : CryptoProvider {
    override fun pbkdf2HmacSha1(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val out = ByteArray(length)
        var block = 1
        var position = 0
        while (position < length) {
            var u = hmacSha1(password, salt + byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = hmacSha1(password, u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(20, length - position)
            t.copyInto(out, position, 0, n)
            position += n
            block++
        }
        return out
    }

    override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val k = (if (key.size > 64) sha1(key) else key).copyOf(64)
        val inner = ByteArray(64) { (k[it].toInt() xor 0x36).toByte() }
        val outer = ByteArray(64) { (k[it].toInt() xor 0x5C).toByte() }
        return sha1(outer + sha1(inner + data))
    }

    override fun aesEcbEncrypt(key: ByteArray, blocks: ByteArray): ByteArray {
        require(blocks.size % 16 == 0)
        val schedule = expand(key)
        val out = blocks.copyOf()
        for (b in 0 until out.size / 16) encryptBlock(schedule, out, 16 * b)
        return out
    }

    override fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(data.size % 16 == 0 && iv.size == 16)
        val schedule = expand(key)
        val out = data.copyOf()
        var previous = iv.copyOf()
        for (b in 0 until out.size / 16) {
            val cipher = data.copyOfRange(16 * b, 16 * b + 16)
            decryptBlock(schedule, out, 16 * b)
            for (i in 0 until 16) out[16 * b + i] = (out[16 * b + i].toInt() xor previous[i].toInt()).toByte()
            previous = cipher
        }
        return out
    }

    override fun randomBytes(count: Int): ByteArray = ByteArray(count) {
        seed = seed * 6364136223846793005L + 1442695040888963407L
        (seed ushr 56).toByte()
    }

    /** AES encryption under a CBC IV, for building test vectors; not part of the provider. */
    fun aesCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val schedule = expand(key)
        val out = data.copyOf()
        var previous = iv.copyOf()
        for (b in 0 until out.size / 16) {
            for (i in 0 until 16) out[16 * b + i] = (out[16 * b + i].toInt() xor previous[i].toInt()).toByte()
            encryptBlock(schedule, out, 16 * b)
            previous = out.copyOfRange(16 * b, 16 * b + 16)
        }
        return out
    }

    companion object {
        fun sha1(message: ByteArray): ByteArray {
            val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
            message.copyInto(padded)
            padded[message.size] = 0x80.toByte()
            val bits = message.size.toLong() * 8
            for (i in 0 until 8) padded[padded.size - 1 - i] = (bits ushr (8 * i)).toByte()
            var h0 = 0x67452301; var h1 = 0xEFCDAB89.toInt(); var h2 = 0x98BADCFE.toInt(); var h3 = 0x10325476; var h4 = 0xC3D2E1F0.toInt()
            val w = IntArray(80)
            for (chunk in 0 until padded.size / 64) {
                for (i in 0 until 16) {
                    val o = 64 * chunk + 4 * i
                    w[i] = (padded[o].toInt() shl 24) or ((padded[o + 1].toInt() and 0xFF) shl 16) or
                        ((padded[o + 2].toInt() and 0xFF) shl 8) or (padded[o + 3].toInt() and 0xFF)
                }
                for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
                var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
                for (i in 0 until 80) {
                    val (f, k) = when {
                        i < 20 -> ((b and c) or (b.inv() and d)) to 0x5A827999
                        i < 40 -> (b xor c xor d) to 0x6ED9EBA1
                        i < 60 -> ((b and c) or (b and d) or (c and d)) to 0x8F1BBCDC.toInt()
                        else -> (b xor c xor d) to 0xCA62C1D6.toInt()
                    }
                    val t = a.rotateLeft(5) + f + e + k + w[i]
                    e = d; d = c; c = b.rotateLeft(30); b = a; a = t
                }
                h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
            }
            return intArrayOf(h0, h1, h2, h3, h4).flatMap { v -> (0 until 4).map { (v ushr (24 - 8 * it)).toByte() } }.toByteArray()
        }

        private val SBOX = IntArray(256)
        private val INV_SBOX = IntArray(256)

        init {
            var p = 1
            var q = 1
            do {
                p = p xor ((p shl 1) and 0xFF) xor (if (p and 0x80 != 0) 0x1B else 0)
                q = q xor (q shl 1); q = q xor (q shl 2); q = q xor (q shl 4); q = q and 0xFF
                if (q and 0x80 != 0) q = q xor 0x09
                val x = q xor (q shl 1 or (q ushr 7)) xor (q shl 2 or (q ushr 6)) xor (q shl 3 or (q ushr 5)) xor (q shl 4 or (q ushr 4))
                SBOX[p] = (x xor 0x63) and 0xFF
            } while (p != 1)
            SBOX[0] = 0x63
            for (i in 0 until 256) INV_SBOX[SBOX[i]] = i
        }

        private fun xtime(b: Int) = ((b shl 1) xor (if (b and 0x80 != 0) 0x1B else 0)) and 0xFF
        private fun mul(a: Int, b: Int): Int {
            var x = a; var y = b; var r = 0
            while (y != 0) { if (y and 1 != 0) r = r xor x; x = xtime(x); y = y ushr 1 }
            return r
        }

        private fun expand(key: ByteArray): Array<IntArray> {
            val nk = key.size / 4
            val rounds = nk + 6
            val w = IntArray(4 * (rounds + 1) * 4)
            for (i in key.indices) w[i] = key[i].toInt() and 0xFF
            var rcon = 1
            for (i in nk until 4 * (rounds + 1)) {
                val t = IntArray(4) { w[4 * (i - 1) + it] }
                if (i % nk == 0) {
                    val first = t[0]
                    t[0] = SBOX[t[1]] xor rcon; t[1] = SBOX[t[2]]; t[2] = SBOX[t[3]]; t[3] = SBOX[first]
                    rcon = xtime(rcon)
                } else if (nk > 6 && i % nk == 4) {
                    for (j in 0 until 4) t[j] = SBOX[t[j]]
                }
                for (j in 0 until 4) w[4 * i + j] = w[4 * (i - nk) + j] xor t[j]
            }
            return Array(rounds + 1) { r -> IntArray(16) { w[16 * r + it] } }
        }

        private fun encryptBlock(schedule: Array<IntArray>, data: ByteArray, offset: Int) {
            val s = IntArray(16) { (data[offset + it].toInt() and 0xFF) xor schedule[0][it] }
            for (round in 1 until schedule.size) {
                for (i in 0 until 16) s[i] = SBOX[s[i]]
                val t = s.copyOf()
                for (c in 0 until 4) for (r in 0 until 4) s[4 * c + r] = t[4 * ((c + r) % 4) + r]
                if (round != schedule.size - 1) {
                    for (c in 0 until 4) {
                        val a = IntArray(4) { s[4 * c + it] }
                        s[4 * c] = mul(a[0], 2) xor mul(a[1], 3) xor a[2] xor a[3]
                        s[4 * c + 1] = a[0] xor mul(a[1], 2) xor mul(a[2], 3) xor a[3]
                        s[4 * c + 2] = a[0] xor a[1] xor mul(a[2], 2) xor mul(a[3], 3)
                        s[4 * c + 3] = mul(a[0], 3) xor a[1] xor a[2] xor mul(a[3], 2)
                    }
                }
                for (i in 0 until 16) s[i] = s[i] xor schedule[round][i]
            }
            for (i in 0 until 16) data[offset + i] = s[i].toByte()
        }

        private fun decryptBlock(schedule: Array<IntArray>, data: ByteArray, offset: Int) {
            val last = schedule.size - 1
            val s = IntArray(16) { (data[offset + it].toInt() and 0xFF) xor schedule[last][it] }
            for (round in last - 1 downTo 0) {
                val t = s.copyOf()
                for (c in 0 until 4) for (r in 0 until 4) s[4 * ((c + r) % 4) + r] = t[4 * c + r]
                for (i in 0 until 16) s[i] = INV_SBOX[s[i]]
                for (i in 0 until 16) s[i] = s[i] xor schedule[round][i]
                if (round != 0) {
                    for (c in 0 until 4) {
                        val a = IntArray(4) { s[4 * c + it] }
                        s[4 * c] = mul(a[0], 14) xor mul(a[1], 11) xor mul(a[2], 13) xor mul(a[3], 9)
                        s[4 * c + 1] = mul(a[0], 9) xor mul(a[1], 14) xor mul(a[2], 11) xor mul(a[3], 13)
                        s[4 * c + 2] = mul(a[0], 13) xor mul(a[1], 9) xor mul(a[2], 14) xor mul(a[3], 11)
                        s[4 * c + 3] = mul(a[0], 11) xor mul(a[1], 13) xor mul(a[2], 9) xor mul(a[3], 14)
                    }
                }
            }
            for (i in 0 until 16) data[offset + i] = s[i].toByte()
        }
    }
}
