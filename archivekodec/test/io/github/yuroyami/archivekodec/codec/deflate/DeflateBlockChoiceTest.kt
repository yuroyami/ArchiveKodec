package io.github.yuroyami.archivekodec.codec.deflate

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeflateBlockChoiceTest {
    private fun roundTrip(data: ByteArray, options: DeflateOptions = DeflateOptions.DEFAULT): ByteArray {
        val stream = Deflate.encode(data, options)
        assertContentEquals(data, Inflate.inflate(stream))
        return stream
    }

    @Test
    fun neighbouringStoredBlocksShareHeaders() {
        // 200,000 bytes that do not compress need four stored blocks of at most 65,535 bytes.
        val data = Random(3).nextBytes(200_000)
        for (level in intArrayOf(1, 6, 9)) {
            assertEquals(data.size + 4 * 5, roundTrip(data, DeflateOptions { this.level = level }).size, "level $level")
        }
    }

    @Test
    fun storedRunEndsTheStreamWhenTheLastBlockIsEmpty() {
        // Two full blocks of literals leave no symbol for the final block: the stored run carries the final bit.
        val data = Random(4).nextBytes(32_768)
        val stream = roundTrip(data, DeflateOptions { strategy = DeflateStrategy.HUFFMAN_ONLY })
        assertEquals(data.size + 5, stream.size)
        assertEquals(1, stream[0].toInt() and 7, "one final stored block")
    }

    @Test
    fun storedRunIsWrittenBeforeACompressedBlock() {
        val text = "the quick brown fox jumps over the lazy dog ".repeat(2_000).encodeToByteArray()
        val noise = Random(5).nextBytes(150_000)
        // Each input and its count of noise parts: a noise part costs its size plus three stored headers.
        val inputs = listOf(noise + text to 1, text + noise to 1, text + noise + text to 1, noise + text + noise to 2)
        for ((data, noiseParts) in inputs) {
            for (level in intArrayOf(1, 6, 9)) {
                val stream = roundTrip(data, DeflateOptions { this.level = level })
                assertTrue(stream.size < noiseParts * (noise.size + 15) + 8_000, "level $level size ${stream.size}")
            }
        }
    }

    @Test
    fun matchesReachOneWholeWindowBack() {
        // The second half repeats the first at distance 32,768, the longest DEFLATE allows.
        val half = Random(6).nextBytes(32_768)
        for (level in intArrayOf(1, 6, 9)) {
            val stream = roundTrip(half + half, DeflateOptions { this.level = level })
            assertTrue(stream.size < 40_000, "level $level size ${stream.size}")
        }
    }

    @Test
    fun shortMatchesOneWindowBackRoundTrip() {
        // Every fourth position repeats three bytes from exactly one window back, then differs.
        val period = 32_768
        val random = Random(7)
        val data = ByteArray(period * 4)
        random.nextBytes(data, 0, period)
        for (i in period until data.size) {
            data[i] = if (i % 4 == 3) (data[i - period] + 1 + random.nextInt(255)).toByte() else data[i - period]
        }
        for (level in 1..9) roundTrip(data, DeflateOptions { this.level = level })
    }
}
