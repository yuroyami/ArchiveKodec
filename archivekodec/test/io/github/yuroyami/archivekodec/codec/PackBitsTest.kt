package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.packbits.PackBits
import io.github.yuroyami.archivekodec.codec.packbits.PackBitsOptions
import io.github.yuroyami.archivekodec.codec.packbits.PackBitsVariant
import io.github.yuroyami.archivekodec.hex
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PackBitsTest {

    // TIFF 6.0, section 9: the example of Apple's PackBits.
    private val packed = hex("feaa0280002afdaa0380002a22f7aa")
    private val unpacked = hex("aaaaaa80002aaaaaaaaa80002a22aaaaaaaaaaaaaaaaaaaa")

    @Test
    fun tiffSpecificationExampleDecodesAndEncodesExactly() {
        assertContentEquals(unpacked, PackBits.decode(packed))
        assertContentEquals(packed, PackBits.encode(unpacked))
        assertContentEquals(unpacked, Codecs.PACKBITS.decompress(packed))
    }

    @Test
    fun theLengthByte128MeansNothingInPackBitsAndTheEndInPdf() {
        val data = hex("00418000428000ff")
        assertEquals("AB", PackBits.decode(data).decodeToString().substring(0, 2))
        assertContentEquals(hex("4142ff"), PackBits.decode(data))
        val pdf = PackBits.decode(data, PackBitsOptions.PDF_RUN_LENGTH)
        assertEquals("A", pdf.output.decodeToString())
        assertEquals(3, pdf.inputConsumed)
        assertTrue(pdf.isComplete)
    }

    @Test
    fun thePdfVariantEndsWith128AndRoundTrips() {
        val encoded = PackBits.encode(unpacked, PackBitsOptions.PDF_RUN_LENGTH)
        assertEquals(0x80, encoded.last().toInt() and 0xFF)
        assertContentEquals(unpacked, PackBits.decode(encoded + byteArrayOf(1, 2, 3), PackBitsOptions.PDF_RUN_LENGTH).output)
    }

    @Test
    fun runsAndLiteralsRoundTripAtEveryBoundary() {
        val random = Random(9)
        val samples = mutableListOf(ByteArray(0), byteArrayOf(7), ByteArray(100_000), ByteArray(50_000) { random.nextInt().toByte() })
        for (n in listOf(1, 2, 3, 127, 128, 129, 130, 255, 256, 257, 1000)) {
            samples += ByteArray(n) { 5 }
            samples += ByteArray(n) { it.toByte() }
            samples += ByteArray(n) { if (it % 5 < 3) 1 else it.toByte() }
        }
        for (variant in PackBitsVariant.entries) {
            val options = PackBitsOptions { this.variant = variant }
            for (data in samples) {
                val encoded = PackBits.encode(data, options)
                assertContentEquals(data, PackBits.decode(encoded, options).output, "$variant ${data.size}")
                assertContentEquals(data, PackBits.decode(encoded, options.copy { expectedSize = data.size.toLong() }).output)
            }
        }
        assertTrue(PackBits.encode(ByteArray(10_000)).size < 200)
    }

    @Test
    fun anExpectedSizeStopsTheDecode() {
        val result = PackBits.decode(packed, PackBitsOptions { expectedSize = 5 })
        assertContentEquals(unpacked.copyOf(5), result.output)
        // The literal run is read whole, though only two of its bytes are kept.
        assertEquals(6, result.inputConsumed)
    }

    @Test
    fun cutDataIsDamageAndKeepsTheBytesBefore() {
        val cutLiteral = hex("0341")
        assertFailsWith<ArchiveFormatException> { PackBits.decode(cutLiteral) }
        val partial = PackBits.decode(cutLiteral, PackBitsOptions { keepPartialOutput = true })
        assertEquals(0, partial.output.size)
        assertIs<ArchiveFormatException>(partial.error)

        val cutRepeat = hex("0041fe")
        val repeat = PackBits.decode(cutRepeat, PackBitsOptions { keepPartialOutput = true })
        assertEquals("A", repeat.output.decodeToString())
        assertIs<ArchiveFormatException>(repeat.error)

        assertFailsWith<ArchiveFormatException> { PackBits.decode(packed, PackBitsOptions { expectedSize = 30 }) }
    }

    @Test
    fun limitsThrow() {
        val bomb = PackBits.encode(ByteArray(1_000_000))
        val small = ArchiveLimits { maxUncompressedBytes = 1000 }
        assertFailsWith<DecompressionBombException> { PackBits.decode(bomb, PackBitsOptions { limits = small }) }
        assertFailsWith<DecompressionBombException> {
            PackBits.decode(bomb, PackBitsOptions { limits = small; keepPartialOutput = true })
        }
        assertFailsWith<DecompressionBombException> {
            PackBits.decode(bomb, PackBitsOptions { limits = ArchiveLimits { maxCompressionRatio = 10 } })
        }
    }

    @Test
    fun optionsValidateAndCompare() {
        assertFailsWith<IllegalArgumentException> { PackBitsOptions { expectedSize = -2 } }
        assertEquals(PackBitsOptions.PDF_RUN_LENGTH, PackBitsOptions { variant = PackBitsVariant.PDF_RUN_LENGTH })
        assertEquals(PackBitsOptions.DEFAULT.hashCode(), PackBitsOptions().hashCode())
    }
}
