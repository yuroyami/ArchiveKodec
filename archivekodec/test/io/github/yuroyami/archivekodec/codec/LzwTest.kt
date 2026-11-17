package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.lzw.Lzw
import io.github.yuroyami.archivekodec.codec.lzw.LzwBitOrder
import io.github.yuroyami.archivekodec.codec.lzw.LzwOptions
import io.github.yuroyami.archivekodec.hex
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LzwTest {

    private val dashes = "-----A---B".encodeToByteArray()

    @Test
    fun pdfSpecificationExampleDecodesAndEncodesExactly() {
        // ISO 32000-1, 7.4.4.2: codes 256 45 258 258 65 259 66 257 at 9 bits.
        val stream = hex("800b6050220c0c8501")
        assertContentEquals(dashes, Lzw.decode(stream))
        assertContentEquals(stream, Lzw.encode(dashes))
        assertContentEquals(stream, Codecs.LZW.compress(dashes))
        assertContentEquals(dashes, Codecs.LZW.decompress(stream))
    }

    @Test
    fun dataWithoutTheEndCodeEndsWithTheInput() {
        // The last byte holds most of the end code; many PDF writers leave it out.
        val result = Lzw.decode(hex("800b6050220c0c85"), LzwOptions.DEFAULT)
        assertContentEquals(dashes, result.output)
        assertTrue(result.isComplete)
    }

    @Test
    fun gifSpecificationSampleDecodesAndEncodesExactly() {
        // The 10 x 10 sample image of the GIF89a walkthrough: minimum code size 2, 22 data bytes.
        val data = hex("8c2d99872a1cdc33a00275ec95faa8de608c04914c01")
        val rows = listOf("1111122222", "1111122222", "1111122222", "1110000222", "1110000222",
            "2220000111", "2220000111", "2222211111", "2222211111", "2222211111")
        val pixels = rows.joinToString("").map { (it - '0').toByte() }.toByteArray()
        val options = LzwOptions.gif(2).copy { expectedSize = 100 }
        assertContentEquals(pixels, Lzw.decode(data, options).output)
        assertContentEquals(data, Lzw.encode(pixels, LzwOptions.gif(2)))
    }

    @Test
    fun everyFormRoundTripsAcrossTableFillsAndClears() {
        val forms = listOf(
            LzwOptions.DEFAULT,
            LzwOptions { earlyChange = false },
            LzwOptions.TIFF_OLD_STYLE,
            LzwOptions { bitOrder = LzwBitOrder.LSB_FIRST },
        ) + (2..8).map { LzwOptions.gif(it) }
        val random = Random(48)
        for (options in forms) {
            val mask = (1 shl options.literalBits) - 1
            val samples = listOf(
                ByteArray(0),
                byteArrayOf(1),
                ByteArray(100_000),
                ByteArray(70_000) { (random.nextInt() and mask).toByte() },
                ByteArray(50_000) { ((it / 7 + it % 3) and mask).toByte() },
            ) + (1..600 step 7).map { size -> ByteArray(size) { ((it * 31 + size) % (mask + 1)).toByte() } }
            for (data in samples) {
                val encoded = Lzw.encode(data, options)
                val result = Lzw.decode(encoded, options)
                assertContentEquals(data, result.output, "$options, ${data.size} bytes")
                assertEquals(encoded.size, result.inputConsumed, "$options, ${data.size} bytes")
                assertContentEquals(data, Lzw.decode(encoded, options.copy { expectedSize = data.size.toLong() }).output)
            }
        }
    }

    @Test
    fun theEncoderCompressesRepeatedData() {
        val text = "the quick brown fox jumps over the lazy dog. ".repeat(400).encodeToByteArray()
        assertTrue(Lzw.encode(text).size < text.size / 4)
    }

    @Test
    fun anExpectedSizeStopsTheDecodeAndIgnoresTheRest() {
        val stream = hex("800b6050220c0c8501") + byteArrayOf(0x55, 0x66)
        val result = Lzw.decode(stream, LzwOptions { expectedSize = 6 })
        assertEquals("-----A", result.output.decodeToString())
        assertTrue(result.isComplete)
    }

    @Test
    fun dataShorterThanTheExpectedSizeIsDamage() {
        val stream = hex("800b6050220c0c8501")
        assertFailsWith<ArchiveFormatException> { Lzw.decode(stream, LzwOptions { expectedSize = 20 }) }
        val partial = Lzw.decode(stream, LzwOptions { expectedSize = 20; keepPartialOutput = true })
        assertContentEquals(dashes, partial.output)
        assertIs<ArchiveFormatException>(partial.error)
    }

    @Test
    fun aFirstCodeThatIsNotALiteralIsDamage() {
        val stream = msb(9, 256, 300)
        assertFailsWith<ArchiveFormatException> { Lzw.decode(stream) }
        val partial = Lzw.decode(stream, LzwOptions { keepPartialOutput = true })
        assertEquals(0, partial.output.size)
        assertIs<ArchiveFormatException>(partial.error)
    }

    @Test
    fun aCodePastTheTableIsDamageAndKeepsTheBytesBefore() {
        val stream = msb(9, 256, 65, 66, 300)
        assertFailsWith<ArchiveFormatException> { Lzw.decode(stream) }
        val partial = Lzw.decode(stream, LzwOptions { keepPartialOutput = true })
        assertEquals("AB", partial.output.decodeToString())
        assertIs<ArchiveFormatException>(partial.error)
    }

    @Test
    fun theCodeBeingDefinedDecodes() {
        // 65, then 258 before it exists in the table: "A" + "AA".
        assertEquals("AAA", Lzw.decode(msb(9, 256, 65, 258, 257)).decodeToString())
    }

    @Test
    fun limitsThrowEvenWithPartialOutput() {
        val bomb = Lzw.encode(ByteArray(1_000_000))
        val small = ArchiveLimits { maxUncompressedBytes = 1000 }
        assertFailsWith<DecompressionBombException> { Lzw.decode(bomb, LzwOptions { limits = small }) }
        assertFailsWith<DecompressionBombException> {
            Lzw.decode(bomb, LzwOptions { limits = small; keepPartialOutput = true })
        }
        assertFailsWith<DecompressionBombException> {
            Lzw.decode(bomb, LzwOptions { limits = small; expectedSize = 1_000_000 })
        }
    }

    @Test
    fun aRangeDecodesOnlyItsBytes() {
        val stream = byteArrayOf(9, 9) + hex("800b6050220c0c8501") + byteArrayOf(9)
        val result = Lzw.decode(stream, 2, stream.size - 3, LzwOptions.DEFAULT)
        assertContentEquals(dashes, result.output)
        assertEquals(stream.size - 3, result.inputConsumed)
        assertFailsWith<IllegalArgumentException> { Lzw.decode(stream, 5, stream.size, LzwOptions.DEFAULT) }
    }

    @Test
    fun optionsValidateAndCompare() {
        assertFailsWith<IllegalArgumentException> { LzwOptions { literalBits = 1 } }
        assertFailsWith<IllegalArgumentException> { LzwOptions { literalBits = 9 } }
        assertFailsWith<IllegalArgumentException> { LzwOptions { expectedSize = -2 } }
        assertFailsWith<IllegalArgumentException> { Lzw.encode(byteArrayOf(4), LzwOptions.gif(2)) }
        assertEquals(LzwOptions.gif(4), LzwOptions.gif(4).copy())
        assertEquals(LzwOptions.gif(4).hashCode(), LzwOptions.gif(4).copy().hashCode())
        assertEquals(LzwBitOrder.LSB_FIRST, LzwOptions.TIFF_OLD_STYLE.bitOrder)
        assertNull(Lzw.decode(Lzw.encode(dashes), LzwOptions.DEFAULT).error)
    }

    /** Codes of [width] bits packed most significant bit first, padded with zeros. */
    private fun msb(width: Int, vararg codes: Int): ByteArray {
        val bits = StringBuilder()
        for (c in codes) bits.append(c.toString(2).padStart(width, '0'))
        while (bits.length % 8 != 0) bits.append('0')
        return ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
    }
}
