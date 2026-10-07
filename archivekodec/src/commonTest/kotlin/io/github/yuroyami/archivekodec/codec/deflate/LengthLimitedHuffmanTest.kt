package io.github.yuroyami.archivekodec.codec.deflate

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LengthLimitedHuffmanTest {
    private fun cost(frequencies: IntArray, lengths: IntArray): Long =
        frequencies.indices.sumOf { frequencies[it].toLong() * lengths[it] }

    private fun checked(frequencies: IntArray, limit: Int): IntArray {
        val lengths = LengthLimitedHuffman.codeLengths(frequencies, limit)
        assertEquals(frequencies.size, lengths.size)
        var used = 0
        var slots = 0
        for (i in frequencies.indices) {
            if (frequencies[i] == 0) assertEquals(0, lengths[i])
            else {
                assertTrue(lengths[i] in 1..limit, "symbol $i has length ${lengths[i]}")
                used++
                slots += 1 shl (limit - lengths[i])
            }
        }
        assertEquals(when (used) { 0 -> 0; 1 -> 1 shl (limit - 1); else -> 1 shl limit }, slots)
        return lengths
    }

    private fun fibonacci(count: Int): IntArray = IntArray(count).also {
        it[0] = 1; it[1] = 1
        for (i in 2 until count) it[i] = it[i - 1] + it[i - 2]
    }

    @Test
    fun handlesEmptyAndSingleSymbolAlphabets() {
        for (limit in 1..15) {
            assertContentEquals(IntArray(0), checked(IntArray(0), limit))
            assertContentEquals(IntArray(286), checked(IntArray(286), limit))
            for (symbol in listOf(0, 18, 29, 256, 285)) {
                val frequencies = IntArray(286).also { it[symbol] = Int.MAX_VALUE }
                assertEquals(1, checked(frequencies, limit)[symbol])
            }
        }
    }

    @Test
    fun twoSymbolsAlwaysHaveOneBitCodes() {
        for (limit in 1..15) {
            for (frequencies in listOf(intArrayOf(1, 1), intArrayOf(1, Int.MAX_VALUE), intArrayOf(Int.MAX_VALUE, 1))) {
                assertContentEquals(intArrayOf(1, 1), checked(frequencies, limit))
            }
        }
    }

    @Test
    fun aFullAlphabetUsesTheEntireAvailableCodeSpace() {
        for (limit in 1..8) {
            val frequencies = IntArray(1 shl limit) { if (it == 0) Int.MAX_VALUE else it + 1 }
            assertContentEquals(IntArray(frequencies.size) { limit }, checked(frequencies, limit))
        }
    }

    @Test
    fun fibonacciRegressionIsCompleteAtFifteenBits() {
        val frequencies = fibonacci(23)
        val lengths = checked(frequencies, 15)
        assertEquals(15, lengths.max())
        assertEquals(196398L, cost(frequencies, lengths))
    }

    @Test
    fun boundedCostsMatchZopfliReferenceAnswers() {
        // Weighted costs from the primary Zopfli katajainen.c implementation.
        // Equal-cost trees may choose different lengths when frequencies tie.
        for ((frequencies, limit, expected) in listOf(
            Triple(fibonacci(19), 7, 29027L),
            Triple(IntArray(19) { it + 1 }, 7, 768L),
            Triple(IntArray(286) { 1 }, 15, 2348L),
            Triple(intArrayOf(1, 2, 3, 5, 8, 13, 21, 34, 55, 89), 4, 636L),
        )) {
            assertEquals(expected, cost(frequencies, checked(frequencies, limit)))
        }
    }

    @Test
    fun packageWeightsAndCostsDoNotOverflowInt() {
        val equal = IntArray(286) { Int.MAX_VALUE }
        val lengths = checked(equal, 15)
        assertContentEquals(checked(IntArray(286) { 1 }, 15), lengths)
        assertEquals(2348L * Int.MAX_VALUE, cost(equal, lengths))
        val skew = IntArray(286) { if (it == 0) Int.MAX_VALUE else 1 }
        val skewLengths = checked(skew, 15)
        assertEquals(1, skewLengths[0])
        assertEquals(Int.MAX_VALUE.toLong() + 2623, cost(skew, skewLengths))
    }

    /** Independent enumeration of full prefix-code lengths, without merging packages. */
    private fun possibleLengths(count: Int, limit: Int): List<IntArray> {
        val results = ArrayList<IntArray>()
        val candidate = IntArray(count)
        fun enumerate(index: Int, occupied: Int) {
            if (index == count) {
                if (occupied == 1 shl limit) results.add(candidate.copyOf())
                return
            }
            for (length in 1..limit) {
                val next = occupied + (1 shl (limit - length))
                if (next <= 1 shl limit) {
                    candidate[index] = length
                    enumerate(index + 1, next)
                }
            }
        }
        enumerate(0, 0)
        return results
    }

    @Test
    fun costsAreOptimalAcrossExhaustiveSmallAlphabets() {
        var checkedCases = 0
        for (count in 2..6) {
            var combinations = 1
            repeat(count) { combinations *= 3 }
            for (limit in 1..4) {
                if (count > 1 shl limit) continue
                val possibilities = possibleLengths(count, limit)
                for (combination in 0 until combinations) {
                    var value = combination
                    val frequencies = IntArray(count) { (value % 3 + 1).also { value /= 3 } }
                    val optimum = possibilities.minOf { cost(frequencies, it) }
                    assertEquals(optimum, cost(frequencies, checked(frequencies, limit)))
                    checkedCases++
                }
            }
        }
        assertEquals(2304, checkedCases)
    }

    @Test
    fun unboundedCasesMatchIndependentHuffmanMergeCosts() {
        for (count in 2..16) {
            repeat(32) { sample ->
                val frequencies = IntArray(count) { 1 + ((it * 31 + sample * 17) % 101) }
                val weights = frequencies.map { it.toLong() }.toMutableList()
                var expected = 0L
                while (weights.size > 1) {
                    weights.sort()
                    val merged = weights.removeAt(0) + weights.removeAt(0)
                    expected += merged
                    weights.add(merged)
                }
                // A tree with at most 16 leaves needs at most 15 bits.
                assertEquals(expected, cost(frequencies, checked(frequencies, 15)))
            }
        }
    }

    @Test
    fun sparseDeflateAlphabetsRemainMonotoneAndDeterministic() {
        var random = 0x12345678
        fun next(): Int {
            random = random xor (random shl 13)
            random = random xor (random ushr 17)
            random = random xor (random shl 5)
            return random and Int.MAX_VALUE
        }
        for ((count, limit) in listOf(19 to 7, 30 to 15, 286 to 15)) {
            repeat(250) {
                val frequencies = IntArray(count) { if (next() % 4 == 0) 0 else 1 + next() % 1_000_000 }
                val lengths = checked(frequencies, limit)
                assertContentEquals(lengths, checked(frequencies, limit))
                val ordered = frequencies.indices.filter { frequencies[it] > 0 }.sortedBy { frequencies[it] }
                for (i in 1 until ordered.size) {
                    val low = ordered[i - 1]
                    val high = ordered[i]
                    if (frequencies[low] < frequencies[high]) assertTrue(lengths[low] >= lengths[high])
                }
            }
        }
    }

    @Test
    fun rejectsImpossibleLimitsAndNegativeFrequencies() {
        for (limit in listOf(0, 16)) assertFailsWith<IllegalArgumentException> { LengthLimitedHuffman.codeLengths(intArrayOf(1, 1), limit) }
        assertFailsWith<IllegalArgumentException> { LengthLimitedHuffman.codeLengths(IntArray(129) { 1 }, 7) }
        assertFailsWith<IllegalArgumentException> { LengthLimitedHuffman.codeLengths(intArrayOf(1, -1), 15) }
    }
}
