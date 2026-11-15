/*
 * Altered Kotlin translation of the constant-time AES of BearSSL:
 * src/symcipher/aes_ct.c, aes_ct_enc.c, aes_ct_dec.c and aes_ct_cbcdec.c.
 */

/*
 * Copyright (c) 2016 Thomas Pornin <pornin@bolet.org>
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.yuroyami.archivekodec.crypto

/**
 * AES (FIPS 197) in BearSSL's bitsliced form: no lookup tables and no branch on the key or the
 * data. Two blocks go through the rounds together, and the S-box is the circuit of Boyar and
 * Peralta.
 */
internal class ConstantTimeAes(key: ByteArray) {
    private val rounds: Int
    private val roundKeys: IntArray

    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "an AES key is 16, 24 or 32 bytes, not ${key.size}" }
        val nk = key.size / 4
        rounds = nk + 6
        val words = IntArray(4 * (rounds + 1))
        for (i in 0 until nk) words[i] = readLe(key, 4 * i)
        for (i in nk until words.size) {
            var tmp = words[i - 1]
            if (i % nk == 0) {
                tmp = subWord((tmp ushr 8) or (tmp shl 24)) xor RCON[i / nk - 1]
            } else if (nk > 6 && i % nk == 4) {
                tmp = subWord(tmp)
            }
            words[i] = words[i - nk] xor tmp
        }
        // Each round key in the same bitsliced layout as a pair of blocks, with both lanes equal.
        roundKeys = IntArray(8 * (rounds + 1))
        val q = IntArray(8)
        for (r in 0..rounds) {
            for (j in 0 until 4) {
                q[2 * j] = words[4 * r + j]
                q[2 * j + 1] = words[4 * r + j]
            }
            ortho(q)
            q.copyInto(roundKeys, 8 * r)
        }
    }

    /** Encrypt [input], a multiple of 16 bytes, block by block (ECB). */
    fun encryptEcb(input: ByteArray): ByteArray {
        require(input.size % 16 == 0) { "AES input must be a multiple of 16 bytes, not ${input.size}" }
        val out = ByteArray(input.size)
        val q = IntArray(8)
        var offset = 0
        while (offset < input.size) {
            val pair = offset + 16 < input.size
            load(q, input, offset, pair)
            ortho(q)
            encryptRounds(q)
            ortho(q)
            store(q, out, offset, pair)
            offset += 32
        }
        return out
    }

    /**
     * XOR [data] in place with the keystream of WinZip AES: the encrypted counter blocks [first],
     * [first] + 1 and so on, each a little-endian number in a 16-byte block.
     */
    fun xorCounterKeystream(data: ByteArray, first: Int) {
        val q = IntArray(8)
        var counter = first
        var offset = 0
        while (offset < data.size) {
            // Word 0 of each block holds the counter; the other words are zero.
            q[0] = counter
            q[1] = counter + 1
            q.fill(0, 2, 8)
            ortho(q)
            encryptRounds(q)
            ortho(q)
            if (offset + 32 <= data.size) {
                for (j in 0 until 4) {
                    xorLe(q[2 * j], data, offset + 4 * j)
                    xorLe(q[2 * j + 1], data, offset + 16 + 4 * j)
                }
            } else {
                for (i in offset until data.size) {
                    val k = i - offset
                    val word = q[2 * ((k and 15) ushr 2) + (k ushr 4)]
                    data[i] = (data[i].toInt() xor (word ushr (8 * (k and 3)))).toByte()
                }
            }
            counter += 2
            offset += 32
        }
    }

    /** Decrypt [data], a multiple of 16 bytes, in CBC mode with no padding. */
    fun decryptCbc(iv: ByteArray, data: ByteArray): ByteArray {
        require(iv.size == 16) { "an AES-CBC IV is 16 bytes, not ${iv.size}" }
        require(data.size % 16 == 0) { "AES-CBC input must be a multiple of 16 bytes, not ${data.size}" }
        val out = ByteArray(data.size)
        val q = IntArray(8)
        // The ciphertext block before the current pair, starting with the IV.
        var p0 = readLe(iv, 0)
        var p1 = readLe(iv, 4)
        var p2 = readLe(iv, 8)
        var p3 = readLe(iv, 12)
        var offset = 0
        while (offset < data.size) {
            val pair = offset + 16 < data.size
            load(q, data, offset, pair)
            ortho(q)
            decryptRounds(q)
            ortho(q)
            q[0] = q[0] xor p0
            q[2] = q[2] xor p1
            q[4] = q[4] xor p2
            q[6] = q[6] xor p3
            p0 = readLe(data, offset)
            p1 = readLe(data, offset + 4)
            p2 = readLe(data, offset + 8)
            p3 = readLe(data, offset + 12)
            if (pair) {
                q[1] = q[1] xor p0
                q[3] = q[3] xor p1
                q[5] = q[5] xor p2
                q[7] = q[7] xor p3
                p0 = readLe(data, offset + 16)
                p1 = readLe(data, offset + 20)
                p2 = readLe(data, offset + 24)
                p3 = readLe(data, offset + 28)
            }
            store(q, out, offset, pair)
            offset += 32
        }
        return out
    }

    private fun encryptRounds(q: IntArray) {
        addRoundKey(q, 0)
        for (r in 1 until rounds) {
            sbox(q)
            shiftRows(q)
            mixColumns(q)
            addRoundKey(q, r)
        }
        sbox(q)
        shiftRows(q)
        addRoundKey(q, rounds)
    }

    private fun decryptRounds(q: IntArray) {
        addRoundKey(q, rounds)
        for (r in rounds - 1 downTo 1) {
            invShiftRows(q)
            invSbox(q)
            addRoundKey(q, r)
            invMixColumns(q)
        }
        invShiftRows(q)
        invSbox(q)
        addRoundKey(q, 0)
    }

    private fun addRoundKey(q: IntArray, round: Int) {
        val base = 8 * round
        for (i in 0 until 8) q[i] = q[i] xor roundKeys[base + i]
    }

    internal companion object {
        private val RCON = intArrayOf(0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36)

        private const val M55 = 0x55555555
        private const val MAA = -0x55555556 // 0xAAAAAAAA
        private const val M33 = 0x33333333
        private const val MCC = -0x33333334 // 0xCCCCCCCC
        private const val M0F = 0x0F0F0F0F
        private const val MF0 = -0x0F0F0F10 // 0xF0F0F0F0

        private fun readLe(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or (b[o + 1].toInt() and 0xFF shl 8) or
                (b[o + 2].toInt() and 0xFF shl 16) or (b[o + 3].toInt() shl 24)

        private fun writeLe(v: Int, b: ByteArray, o: Int) {
            b[o] = v.toByte()
            b[o + 1] = (v ushr 8).toByte()
            b[o + 2] = (v ushr 16).toByte()
            b[o + 3] = (v ushr 24).toByte()
        }

        private fun xorLe(v: Int, b: ByteArray, o: Int) {
            b[o] = (b[o].toInt() xor v).toByte()
            b[o + 1] = (b[o + 1].toInt() xor (v ushr 8)).toByte()
            b[o + 2] = (b[o + 2].toInt() xor (v ushr 16)).toByte()
            b[o + 3] = (b[o + 3].toInt() xor (v ushr 24)).toByte()
        }

        /** The first block goes to the even words, the second (or zeros) to the odd words. */
        private fun load(q: IntArray, src: ByteArray, offset: Int, pair: Boolean) {
            for (j in 0 until 4) {
                q[2 * j] = readLe(src, offset + 4 * j)
                q[2 * j + 1] = if (pair) readLe(src, offset + 16 + 4 * j) else 0
            }
        }

        private fun store(q: IntArray, dst: ByteArray, offset: Int, pair: Boolean) {
            for (j in 0 until 4) {
                writeLe(q[2 * j], dst, offset + 4 * j)
                if (pair) writeLe(q[2 * j + 1], dst, offset + 16 + 4 * j)
            }
        }

        private fun swap(q: IntArray, i: Int, j: Int, low: Int, high: Int, shift: Int) {
            val a = q[i]
            val b = q[j]
            q[i] = (a and low) or ((b and low) shl shift)
            q[j] = ((a and high) ushr shift) or (b and high)
        }

        /** Move between the byte layout and the bitsliced layout; the transform is its own inverse. */
        internal fun ortho(q: IntArray) {
            swap(q, 0, 1, M55, MAA, 1); swap(q, 2, 3, M55, MAA, 1)
            swap(q, 4, 5, M55, MAA, 1); swap(q, 6, 7, M55, MAA, 1)
            swap(q, 0, 2, M33, MCC, 2); swap(q, 1, 3, M33, MCC, 2)
            swap(q, 4, 6, M33, MCC, 2); swap(q, 5, 7, M33, MCC, 2)
            swap(q, 0, 4, M0F, MF0, 4); swap(q, 1, 5, M0F, MF0, 4)
            swap(q, 2, 6, M0F, MF0, 4); swap(q, 3, 7, M0F, MF0, 4)
        }

        /** The S-box on each byte of a little-endian word, through the bitsliced circuit. */
        internal fun subWord(x: Int): Int {
            val q = IntArray(8) { x }
            ortho(q)
            sbox(q)
            ortho(q)
            return q[0]
        }

        @Suppress("LocalVariableName")
        internal fun sbox(q: IntArray) {
            val x0 = q[7]; val x1 = q[6]; val x2 = q[5]; val x3 = q[4]
            val x4 = q[3]; val x5 = q[2]; val x6 = q[1]; val x7 = q[0]

            // Top linear transformation.
            val y14 = x3 xor x5
            val y13 = x0 xor x6
            val y9 = x0 xor x3
            val y8 = x0 xor x5
            val t0 = x1 xor x2
            val y1 = t0 xor x7
            val y4 = y1 xor x3
            val y12 = y13 xor y14
            val y2 = y1 xor x0
            val y5 = y1 xor x6
            val y3 = y5 xor y8
            val t1 = x4 xor y12
            val y15 = t1 xor x5
            val y20 = t1 xor x1
            val y6 = y15 xor x7
            val y10 = y15 xor t0
            val y11 = y20 xor y9
            val y7 = x7 xor y11
            val y17 = y10 xor y11
            val y19 = y10 xor y8
            val y16 = t0 xor y11
            val y21 = y13 xor y16
            val y18 = x0 xor y16

            // Non-linear section.
            val t2 = y12 and y15
            val t3 = y3 and y6
            val t4 = t3 xor t2
            val t5 = y4 and x7
            val t6 = t5 xor t2
            val t7 = y13 and y16
            val t8 = y5 and y1
            val t9 = t8 xor t7
            val t10 = y2 and y7
            val t11 = t10 xor t7
            val t12 = y9 and y11
            val t13 = y14 and y17
            val t14 = t13 xor t12
            val t15 = y8 and y10
            val t16 = t15 xor t12
            val t17 = t4 xor t14
            val t18 = t6 xor t16
            val t19 = t9 xor t14
            val t20 = t11 xor t16
            val t21 = t17 xor y20
            val t22 = t18 xor y19
            val t23 = t19 xor y21
            val t24 = t20 xor y18

            val t25 = t21 xor t22
            val t26 = t21 and t23
            val t27 = t24 xor t26
            val t28 = t25 and t27
            val t29 = t28 xor t22
            val t30 = t23 xor t24
            val t31 = t22 xor t26
            val t32 = t31 and t30
            val t33 = t32 xor t24
            val t34 = t23 xor t33
            val t35 = t27 xor t33
            val t36 = t24 and t35
            val t37 = t36 xor t34
            val t38 = t27 xor t36
            val t39 = t29 and t38
            val t40 = t25 xor t39

            val t41 = t40 xor t37
            val t42 = t29 xor t33
            val t43 = t29 xor t40
            val t44 = t33 xor t37
            val t45 = t42 xor t41
            val z0 = t44 and y15
            val z1 = t37 and y6
            val z2 = t33 and x7
            val z3 = t43 and y16
            val z4 = t40 and y1
            val z5 = t29 and y7
            val z6 = t42 and y11
            val z7 = t45 and y17
            val z8 = t41 and y10
            val z9 = t44 and y12
            val z10 = t37 and y3
            val z11 = t33 and y4
            val z12 = t43 and y13
            val z13 = t40 and y5
            val z14 = t29 and y2
            val z15 = t42 and y9
            val z16 = t45 and y14
            val z17 = t41 and y8

            // Bottom linear transformation.
            val t46 = z15 xor z16
            val t47 = z10 xor z11
            val t48 = z5 xor z13
            val t49 = z9 xor z10
            val t50 = z2 xor z12
            val t51 = z2 xor z5
            val t52 = z7 xor z8
            val t53 = z0 xor z3
            val t54 = z6 xor z7
            val t55 = z16 xor z17
            val t56 = z12 xor t48
            val t57 = t50 xor t53
            val t58 = z4 xor t46
            val t59 = z3 xor t54
            val t60 = t46 xor t57
            val t61 = z14 xor t57
            val t62 = t52 xor t58
            val t63 = t49 xor t58
            val t64 = z4 xor t59
            val t65 = t61 xor t62
            val t66 = z1 xor t63
            val s0 = t59 xor t63
            val s6 = t56 xor t62.inv()
            val s7 = t48 xor t60.inv()
            val t67 = t64 xor t65
            val s3 = t53 xor t66
            val s4 = t51 xor t66
            val s5 = t47 xor t65
            val s1 = t64 xor s3.inv()
            val s2 = t55 xor t67.inv()

            q[7] = s0; q[6] = s1; q[5] = s2; q[4] = s3
            q[3] = s4; q[2] = s5; q[1] = s6; q[0] = s7
        }

        /** The inverse affine transform of the S-box, with its constant, on bitsliced words. */
        private fun inverseAffine(q: IntArray) {
            val q0 = q[0].inv(); val q1 = q[1].inv(); val q2 = q[2]; val q3 = q[3]
            val q4 = q[4]; val q5 = q[5].inv(); val q6 = q[6].inv(); val q7 = q[7]
            q[7] = q1 xor q4 xor q6
            q[6] = q0 xor q3 xor q5
            q[5] = q7 xor q2 xor q4
            q[4] = q6 xor q1 xor q3
            q[3] = q5 xor q0 xor q2
            q[2] = q4 xor q7 xor q1
            q[1] = q3 xor q6 xor q0
            q[0] = q2 xor q5 xor q7
        }

        /** S(x) = A(I(x)) + 0x63, and I is its own inverse, so iS(x) = B(S(B(x + 0x63)) + 0x63) with B = A^-1. */
        internal fun invSbox(q: IntArray) {
            inverseAffine(q)
            sbox(q)
            inverseAffine(q)
        }

        private fun shiftRows(q: IntArray) {
            for (i in 0 until 8) {
                val x = q[i]
                q[i] = (x and 0x000000FF) or
                    ((x and 0x0000FC00) ushr 2) or ((x and 0x00000300) shl 6) or
                    ((x and 0x00F00000) ushr 4) or ((x and 0x000F0000) shl 4) or
                    ((x and -0x40000000) ushr 6) or ((x and 0x3F000000) shl 2)
            }
        }

        private fun invShiftRows(q: IntArray) {
            for (i in 0 until 8) {
                val x = q[i]
                q[i] = (x and 0x000000FF) or
                    ((x and 0x00003F00) shl 2) or ((x and 0x0000C000) ushr 6) or
                    ((x and 0x000F0000) shl 4) or ((x and 0x00F00000) ushr 4) or
                    ((x and 0x03000000) shl 6) or ((x and -0x4000000) ushr 2)
            }
        }

        private fun rotr8(x: Int): Int = (x ushr 8) or (x shl 24)

        private fun rotr16(x: Int): Int = (x shl 16) or (x ushr 16)

        internal fun mixColumns(q: IntArray) {
            val q0 = q[0]; val q1 = q[1]; val q2 = q[2]; val q3 = q[3]
            val q4 = q[4]; val q5 = q[5]; val q6 = q[6]; val q7 = q[7]
            val r0 = rotr8(q0); val r1 = rotr8(q1); val r2 = rotr8(q2); val r3 = rotr8(q3)
            val r4 = rotr8(q4); val r5 = rotr8(q5); val r6 = rotr8(q6); val r7 = rotr8(q7)
            q[0] = q7 xor r7 xor r0 xor rotr16(q0 xor r0)
            q[1] = q0 xor r0 xor q7 xor r7 xor r1 xor rotr16(q1 xor r1)
            q[2] = q1 xor r1 xor r2 xor rotr16(q2 xor r2)
            q[3] = q2 xor r2 xor q7 xor r7 xor r3 xor rotr16(q3 xor r3)
            q[4] = q3 xor r3 xor q7 xor r7 xor r4 xor rotr16(q4 xor r4)
            q[5] = q4 xor r4 xor r5 xor rotr16(q5 xor r5)
            q[6] = q5 xor r5 xor r6 xor rotr16(q6 xor r6)
            q[7] = q6 xor r6 xor r7 xor rotr16(q7 xor r7)
        }

        internal fun invMixColumns(q: IntArray) {
            val q0 = q[0]; val q1 = q[1]; val q2 = q[2]; val q3 = q[3]
            val q4 = q[4]; val q5 = q[5]; val q6 = q[6]; val q7 = q[7]
            val r0 = rotr8(q0); val r1 = rotr8(q1); val r2 = rotr8(q2); val r3 = rotr8(q3)
            val r4 = rotr8(q4); val r5 = rotr8(q5); val r6 = rotr8(q6); val r7 = rotr8(q7)
            q[0] = q5 xor q6 xor q7 xor r0 xor r5 xor r7 xor rotr16(q0 xor q5 xor q6 xor r0 xor r5)
            q[1] = q0 xor q5 xor r0 xor r1 xor r5 xor r6 xor r7 xor rotr16(q1 xor q5 xor q7 xor r1 xor r5 xor r6)
            q[2] = q0 xor q1 xor q6 xor r1 xor r2 xor r6 xor r7 xor rotr16(q0 xor q2 xor q6 xor r2 xor r6 xor r7)
            q[3] = q0 xor q1 xor q2 xor q5 xor q6 xor r0 xor r2 xor r3 xor r5 xor
                rotr16(q0 xor q1 xor q3 xor q5 xor q6 xor q7 xor r0 xor r3 xor r5 xor r7)
            q[4] = q1 xor q2 xor q3 xor q5 xor r1 xor r3 xor r4 xor r5 xor r6 xor r7 xor
                rotr16(q1 xor q2 xor q4 xor q5 xor q7 xor r1 xor r4 xor r5 xor r6)
            q[5] = q2 xor q3 xor q4 xor q6 xor r2 xor r4 xor r5 xor r6 xor r7 xor
                rotr16(q2 xor q3 xor q5 xor q6 xor r2 xor r5 xor r6 xor r7)
            q[6] = q3 xor q4 xor q5 xor q7 xor r3 xor r5 xor r6 xor r7 xor
                rotr16(q3 xor q4 xor q6 xor q7 xor r3 xor r6 xor r7)
            q[7] = q4 xor q5 xor q6 xor r4 xor r6 xor r7 xor rotr16(q4 xor q5 xor q7 xor r4 xor r7)
        }
    }
}
