package io.github.yuroyami.archivekodec.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class EncoderMatchTableTest {
    @Test
    fun latestPositionsMatchDirectIndexingAcrossSizesAndSliceBases() {
        for (bits in listOf(15, 16)) {
            val hashSize = 1 shl bits
            val boundary = hashSize / 4
            for (length in listOf(0, 1, 13, 100, 1024, boundary - 1, boundary, boundary + 1)) {
                for (base in listOf(0, Int.MAX_VALUE - length)) {
                    val expected = IntArray(hashSize) { -1 }
                    val actual = EncoderMatchTable(bits, length, base)
                    var random = 0x12345678
                    repeat(length) { at ->
                        random = random xor (random shl 13)
                        random = random xor (random ushr 17)
                        random = random xor (random shl 5)
                        val hash = when (at % 11) {
                            0 -> 0
                            1 -> hashSize - 1
                            else -> random and (hashSize - 1)
                        }
                        val position = base + at
                        assertEquals(expected[hash], actual.put(hash, position))
                        expected[hash] = position
                        assertEquals(position, actual[hash])
                        val probe = (hash + 1) and (hashSize - 1)
                        assertEquals(expected[probe], actual[probe])
                    }
                    assertContentEquals(expected, IntArray(hashSize) { actual[it] },
                        "bits $bits length $length base $base")
                }
            }
        }
    }
}
