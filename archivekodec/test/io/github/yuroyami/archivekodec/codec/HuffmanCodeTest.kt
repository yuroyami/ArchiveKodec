package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.codec.huffman.HuffmanCode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HuffmanCodeTest {

    @Test
    fun canonicalCodesFollowTheRfc1951Example() {
        // RFC 1951, 3.2.2: lengths (3, 3, 3, 3, 3, 2, 4, 4) for A to H.
        val codes = HuffmanCode.canonicalCodes(intArrayOf(3, 3, 3, 3, 3, 2, 4, 4))
        assertContentEquals(intArrayOf(0b010, 0b011, 0b100, 0b101, 0b110, 0b00, 0b1110, 0b1111), codes)
        assertContentEquals(intArrayOf(0, 0, 0), HuffmanCode.canonicalCodes(intArrayOf(0, 1, 0)))
        assertContentEquals(intArrayOf(0, 0, 1), HuffmanCode.canonicalCodes(intArrayOf(0, 1, 1)))
    }

    @Test
    fun lengthsAreOptimalWithinTheLimitAndFillTheSpace() {
        val random = Random(3)
        for (maxLength in listOf(9, 12, 15)) {
            repeat(20) {
                val frequencies = IntArray(286) { if (random.nextInt(5) == 0) 0 else random.nextInt(1, 1 shl random.nextInt(1, 20)) }
                val lengths = HuffmanCode.lengths(frequencies, maxLength)
                var kraft = 0.0
                for ((i, l) in lengths.withIndex()) {
                    assertEquals(frequencies[i] == 0, l == 0, "symbol $i")
                    assertTrue(l <= maxLength)
                    if (l > 0) kraft += 1.0 / (1 shl l)
                }
                assertEquals(1.0, kraft, 1e-12)
                val codes = HuffmanCode.canonicalCodes(lengths)
                val seen = HashSet<String>()
                for ((i, l) in lengths.withIndex()) if (l > 0) seen += codes[i].toString(2).padStart(l, '0')
                for (a in seen) for (b in seen) if (a != b) assertTrue(!b.startsWith(a), "$a is a prefix of $b")
            }
        }
    }

    @Test
    fun deepAndSkewedWeightsGiveCompleteOrderedCodes() {
        // Fibonacci weights force the deepest tree for a symbol count. 7 bits is the limit of the
        // 19-symbol code length alphabet, 15 bits the limit of the literal alphabet.
        val cases = ArrayList<Pair<IntArray, Int>>()
        for (maxLength in intArrayOf(7, 15)) for (n in 3..30) {
            var a = 1
            var b = 1
            cases += IntArray(n) { val f = a; val next = a + b; a = b; b = next; f } to maxLength
        }
        val random = Random(20260928)
        repeat(400) {
            for ((maxLength, alphabet) in listOf(7 to 19, 15 to 286)) {
                val used = random.nextInt(2, alphabet + 1)
                val ratio = 1.15 + random.nextDouble()
                val frequencies = IntArray(alphabet)
                var weight = 1.0
                for (symbol in (0 until alphabet).shuffled(random).take(used)) {
                    frequencies[symbol] = weight.toInt().coerceAtLeast(1)
                    weight = minOf(weight * ratio, 1.0e6)
                }
                cases += frequencies to maxLength
            }
        }
        for ((frequencies, maxLength) in cases) {
            val lengths = HuffmanCode.lengths(frequencies, maxLength)
            val used = frequencies.indices.filter { frequencies[it] > 0 }
            var kraft = 0L
            for (i in frequencies.indices) {
                assertEquals(frequencies[i] == 0, lengths[i] == 0, "symbol $i")
                assertTrue(lengths[i] <= maxLength)
                if (lengths[i] > 0) kraft += 1L shl (maxLength - lengths[i])
            }
            assertEquals(1L shl maxLength, kraft, "the code is complete")
            // A more frequent symbol never gets a longer code.
            for (x in used) for (y in used) {
                if (frequencies[x] > frequencies[y]) assertTrue(lengths[x] <= lengths[y], "$x is more frequent than $y")
            }
        }
    }

    @Test
    fun edgeCasesAndBadInput() {
        assertContentEquals(IntArray(4), HuffmanCode.lengths(IntArray(4), 15))
        assertContentEquals(intArrayOf(0, 1, 0), HuffmanCode.lengths(intArrayOf(0, 9, 0), 15))
        assertContentEquals(intArrayOf(1, 1), HuffmanCode.lengths(intArrayOf(1, 1000), 15))
        assertFailsWith<IllegalArgumentException> { HuffmanCode.lengths(intArrayOf(1, 2), 0) }
        assertFailsWith<IllegalArgumentException> { HuffmanCode.lengths(intArrayOf(1, 2), 16) }
        assertFailsWith<IllegalArgumentException> { HuffmanCode.lengths(intArrayOf(1, -2), 15) }
        assertFailsWith<IllegalArgumentException> { HuffmanCode.lengths(IntArray(5) { 1 }, 2) }
        assertFailsWith<IllegalArgumentException> { HuffmanCode.canonicalCodes(intArrayOf(1, 1, 1)) }
        assertFailsWith<IllegalArgumentException> { HuffmanCode.canonicalCodes(intArrayOf(16)) }
    }
}
