package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.codec.snappy.Snappy
import io.github.yuroyami.archivekodec.hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnappyCompressionTest {
    @Test fun repetitiveInputActuallyShrinks() {
        for (input in listOf("hello world, ".repeat(1000).encodeToByteArray(), ByteArray(100_000) { 42 })) {
            val compressed = Snappy.compress(input)
            assertTrue(compressed.size < input.size / 2, "${input.size} bytes encoded as ${compressed.size}")
            assertContentEquals(input, Snappy.decompress(compressed))
        }
    }

    @Test fun copyLengthsCoverCompactTagsAndLongMatches() {
        for (length in listOf(4, 5, 10, 11, 12, 63, 64, 65, 66, 67, 68, 127, 128, 129)) {
            val input = ByteArray(length + 1) { 0x5a }
            val compressed = Snappy.compress(input)
            val copies = copies(compressed)
            assertTrue(copies.isNotEmpty(), "no copy for match length $length")
            assertEquals(length, copies.sumOf { it.length })
            assertTrue(copies.all { it.offset == 1 && it.length in 1..64 })
            if (length <= 64) {
                assertEquals(if (length <= 11) 1 else 2, copies.single().type, "match length $length")
            }
            assertContentEquals(input, Snappy.decompress(compressed))
        }
    }

    @Test fun copyOffsetsSelectAllThreeWireForms() {
        val prefix = hex("1c037afd920be4618d5e2a37")
        for ((distance, type) in listOf(300 to 1, 2047 to 1, 2048 to 2, 65535 to 2, 65536 to 3, 70000 to 3)) {
            val input = prefix + ByteArray(distance - prefix.size) + prefix.copyOf(7)
            val compressed = Snappy.compress(input)
            assertTrue(copies(compressed).any { it == Copy(type, 7, distance) }, "missing type $type copy at offset $distance")
            assertContentEquals(input, Snappy.decompress(compressed))
        }
    }

    @Test fun incompressibleAndShortInputsRemainLosslessAtLengthBoundaries() {
        var random = 0x12345678
        for (size in listOf(0, 1, 2, 3, 4, 5, 60, 61, 62, 63, 64, 65, 127, 128, 129,
                255, 256, 257, 16383, 16384, 16385, 65535, 65536, 65537)) {
            val input = ByteArray(size) {
                random = random xor (random shl 13)
                random = random xor (random ushr 17)
                random = random xor (random shl 5)
                random.toByte()
            }
            val compressed = Snappy.compress(input)
            assertTrue(compressed.size <= size + size / 6 + 32, "excessive overhead at size $size")
            assertContentEquals(input, Snappy.decompress(compressed), "input size $size")
        }
    }

    private data class Copy(val type: Int, val length: Int, val offset: Int)

    // Inspect wire tags independently of the codec's decoding and byte-order helpers.
    private fun copies(bytes: ByteArray): List<Copy> {
        var at = 0
        fun byte(): Int = bytes[at++].toInt() and 255
        fun littleEndian(count: Int): Int {
            var value = 0
            repeat(count) { value = value or (byte() shl (8 * it)) }
            return value
        }
        while (byte() and 128 != 0) { /* skip the uncompressed-length varint */ }
        val result = mutableListOf<Copy>()
        while (at < bytes.size) {
            val tag = byte()
            when (val type = tag and 3) {
                0 -> {
                    val length = tag ushr 2
                    val literalSize = if (length < 60) length + 1 else littleEndian(length - 59) + 1
                    at += literalSize
                    assertTrue(at <= bytes.size, "literal exceeds compressed input")
                }
                1 -> result.add(Copy(type, 4 + ((tag ushr 2) and 7), ((tag and 0xe0) shl 3) or byte()))
                2 -> result.add(Copy(type, 1 + (tag ushr 2), littleEndian(2)))
                3 -> result.add(Copy(type, 1 + (tag ushr 2), littleEndian(4)))
            }
        }
        return result
    }
}
