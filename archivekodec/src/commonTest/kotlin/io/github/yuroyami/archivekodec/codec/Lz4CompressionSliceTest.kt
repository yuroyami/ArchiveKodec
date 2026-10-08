package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import kotlin.test.Test
import kotlin.test.assertContentEquals

class Lz4CompressionSliceTest {
    @Test fun slicesKeepMatchesAndFinalLiteralsInsideTheirBounds() {
        val lengths = (0..21).toList() + listOf(31, 32, 33, 63, 64, 65, 127, 128, 129, 1024)
        for (offset in listOf(0, 1, 3, 7, 31)) {
            for (length in lengths) {
                val backing = ByteArray(offset + length + 37) { ('a'.code + it % 3).toByte() }
                val compressed = Lz4.blockCompress(backing, offset, length)
                assertContentEquals(
                    backing.copyOfRange(offset, offset + length),
                    Lz4.blockDecompress(compressed, 0, compressed.size),
                    "offset $offset, length $length",
                )
            }
        }
    }

    @Test fun frameBlocksReadTheirOwnSliceIncludingTheShortTail() {
        val input = ByteArray(4 * 1024 * 1024 + 19) { ((it * 37) xor (it ushr 7)).toByte() }
        val encoded = Lz4.frameCompress(input)
        assertContentEquals(input, Lz4.frameDecompress(encoded))
    }

    @Test fun wordExtensionStopsAtEachDifferingByteIncludingHighBits() {
        val prefix = ByteArray(32) { (it + 1).toByte() }
        for (byte in 0..3) {
            val repeated = prefix.copyOf(16)
            repeated[4 + byte] = (repeated[4 + byte].toInt() xor 0x80).toByte()
            val input = prefix + repeated + ByteArray(13) { (99 + it).toByte() }
            val encoded = Lz4.blockCompress(input)
            assertContentEquals(input, Lz4.blockDecompress(encoded, 0, encoded.size), "word byte $byte")
        }
    }
}
