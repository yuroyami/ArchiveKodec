package io.github.yuroyami.archivekodec.codec.deflate

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.hex
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InflateLookupTest {
    // Hand-built RFC 1951 dynamic block, independently decoded by CPython zlib.
    // Literal and distance trees both reach 15 bits; the payload uses every
    // distance code 0..15, each extra-bit maximum, and lengths 3 and 258.
    private val dynamic = hex(
        "edef0190244992244922b1a87964f52c00000000000000000000000000000000000000000000000000c00300000000000000000000000000"
        + "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        + "00000000000000000000000000000000000000000000000000fc03000000000000000000000000c023b1a87964f5ecfd6bf7bedfdfbffffe"
        + "f77fffefffa3ddfb7e7ffffefbdffffdbfff8f76effbfdfdfbef7ffff7fffe3fdabdeff7f7efbffffddffffbff68f7bedfdfbffffef77fff"
        + "efffa3ddfb7e7ffffefbdffffdbfff8f76effbfdfdfbef7ffff7fffe3fdabdeff7f7efbffffddffffbff68f7bedfdfbffffef77fffefffa3"
        + "ddfb7e7ffffefbdffffdbfff8f76effbfdfdfbef7ffff7fffe3fdabdeff7f7efbffffddffffbff68f7bedfdfbffffef77fffefffa3ddfb7e"
        + "7ffffefbdffffdbfff8f76effbfdfdfbef7ffff7fffe3fdabdeff7f7efbffffddffffbff68f7bedfdfbffffef77fffefffa3ddfb7e7ffffe"
        + "fbdffffdbfff8f76effbfdfdfbef7ffff7fffe3fdabdeff7f7efbffffddffffbff68f7bedfdfbffffef77fffefffa3ddfb7e7ffffefbdfff"
        + "fdbfff8f76effbfdfdfbef7ffff7fffe3fdabdeff7f7efbffffddffffbff68f7bedfdfbffffef77fffefffa3ddfb7e7ffffefbdffffdbfff"
        + "8f76effbfdfdfbef7ffff7fffe3fdabdeff7f7efbffffddffffbff68f7bedfdfbffffef77fffefffa3ddfb7e7ffffefbdffffdbfff8f76ef"
        + "fbfdfdfbef7ffff7fffe3fdabdeff7f7efbffffddffffbff68f7bedfdfbffffef77fffefffa3ddfb7e7ffffefbdffffdbfff8f76effbfdfd"
        + "fbef7ffff7fffe3fdabdeff7f7efbffffddffffbff68f7bedfdfffcfffafffdffe7ff7ff7bff7fdfffdfefffdfdfffbf7ffffffdf7ffdfff"
        + "fefff77ffffffb7ffffff7fffdffdfffeffffffdffffffffffffffff02"
    )
    private val plain = hex(
        "000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41"
        + "000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41"
        + "000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41"
        + "000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41"
        + "000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41"
        + "000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41"
        + "000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41"
        + "000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41"
        + "000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41"
        + "00010203040506070707070707070707070707070707070707070707070707070707070304050405060506070405060304050c4100070809"
        + "0a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c4100010203040506070809"
        + "0a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c4100010203040506070809"
        + "0a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c4100010203040506070809"
        + "0a0b0c41000102030405060708090a0b0c41000102030405060708090a0b0c41000102030405060707070707070707070707070707070707"
        + "07070707070707070707070304050405060506070405060304050c41000708090a0b"
    )
    private val dynamicBits = 5602

    @Test fun singleBitCodesAndUnusedEmptyDistanceTrees() {
        // Independent zlib-verified streams: only EOB, or A/EOB, and no distances.
        for ((raw, expected) in listOf(
            "05c001040000000010000000000000000000000000000000000000000000000000000000000000008000" to byteArrayOf(),
            "05c001040000000010000000000000000001000000000000000000000000000000000000000000008020" to "AAAA".encodeToByteArray(),
        )) {
            val input = hex(raw)
            assertContentEquals(expected, Inflate.inflate(input))
            val output = Inflate.Output(ArchiveLimits.DEFAULT, input.size)
            assertEquals(input.size, Inflate.inflateInto(input + ByteArray(8), 0, input.size + 8, output))
            assertContentEquals(expected, output.toByteArray())
        }
    }

    @Test fun longCodesAndStoredTransitionsAtEveryBitAlignment() {
        val tail = "stored tail bytes".encodeToByteArray()
        for (prefixLength in 0..7) {
            val bits = Bits()
            bits.write(2, 3) // non-final fixed block
            repeat(prefixLength) { bits.code(0x190 + 200 - 144, 9) }
            bits.code(0, 7) // fixed end of block
            repeat(dynamicBits) { at ->
                bits.write(if (at == 0) 0 else (dynamic[at / 8].toInt() ushr (at % 8)) and 1, 1)
            }
            bits.write(1, 3) // final stored block
            bits.align()
            bits.write(tail.size, 16)
            bits.write(tail.size xor 0xFFFF, 16)
            for (byte in tail) bits.write(byte.toInt() and 255, 8)
            val raw = bits.bytes()
            val expected = ByteArray(prefixLength) { 200.toByte() } + plain + tail
            assertContentEquals(expected, Inflate.inflate(raw), "alignment $prefixLength")
            val input = byteArrayOf(99, 98, 97) + raw + ByteArray(8) { 0x5A }
            val output = Inflate.Output(ArchiveLimits.DEFAULT, raw.size)
            assertEquals(3 + raw.size, Inflate.inflateInto(input, 3, input.size, output))
            assertContentEquals(expected, output.toByteArray())
            assertFailsWith<ArchiveFormatException> { Inflate.inflate(raw + byteArrayOf(0)) }
        }
    }

    @Test fun finalShortCodesAndEveryTruncatedTailRespectInputSlices() {
        for (count in 0..7) {
            val bits = Bits()
            bits.write(3, 3)
            repeat(count) { bits.code(0x190 + 200 - 144, 9) }
            bits.code(0, 7)
            val raw = bits.bytes()
            assertContentEquals(ByteArray(count) { 200.toByte() }, Inflate.inflate(raw))
            val backing = byteArrayOf(99) + raw + ByteArray(8)
            for (length in 0 until raw.size) {
                val error = assertFailsWith<InflateException> {
                    Inflate.inflateInto(backing, 1, 1 + length, Inflate.Output(ArchiveLimits.DEFAULT, raw.size))
                }
                assertEquals(InflateError.DATA_DID_NOT_TERMINATE, error.error)
                assertEquals((1 + length).toLong(), error.offset)
            }
        }
    }

    @Test fun invalidSymbolsKeepTheirErrorAndConsumedOffset() {
        for (symbol in 286..287) {
            val bits = Bits()
            bits.write(3, 3)
            bits.code(192 + symbol - 280, 8)
            val error = assertFailsWith<InflateException> { Inflate.inflate(bits.bytes() + ByteArray(8)) }
            assertEquals(InflateError.INVALID_LITERAL_OR_DISTANCE_CODE, error.error)
            assertEquals(2L, error.offset)
        }
        for (distance in 30..31) {
            val bits = Bits()
            bits.write(3, 3)
            bits.code(0x30 + 65, 8)
            bits.code(1, 7) // length 3
            bits.code(distance, 5)
            val error = assertFailsWith<InflateException> { Inflate.inflate(bits.bytes() + ByteArray(8)) }
            assertEquals(InflateError.INVALID_LITERAL_OR_DISTANCE_CODE, error.error)
            assertEquals(5L, error.offset) // puff rejects after at most 15 code bits
        }
        val error = assertFailsWith<InflateException> { Inflate.inflate(byteArrayOf(7) + ByteArray(8)) }
        assertEquals(InflateError.INVALID_BLOCK_TYPE, error.error)
        assertEquals(1L, error.offset)
    }

    @Test fun capacityBoundariesNeverBypassOutputRatioOrExactSizeLimits() {
        for (maximum in listOf(0, 1, 255, 256, 257, plain.size - 1)) {
            assertFailsWith<DecompressionBombException> {
                Inflate.inflate(dynamic, ArchiveLimits { maxUncompressedBytes = maximum.toLong() })
            }
        }
        assertContentEquals(plain, Inflate.inflate(dynamic, ArchiveLimits { maxUncompressedBytes = plain.size.toLong() }))
        assertFailsWith<DecompressionBombException> {
            Inflate.inflate(dynamic, ArchiveLimits { maxCompressionRatio = 1 })
        }
        for (expected in listOf(plain.size - 1, plain.size + 1)) {
            assertFailsWith<ArchiveFormatException> {
                Inflate.inflateExact(dynamic, ArchiveLimits.DEFAULT, expected.toLong())
            }
        }
        assertContentEquals(plain, Inflate.inflateExact(dynamic, ArchiveLimits.DEFAULT, plain.size.toLong()))
    }

    private class Bits {
        private val bits = mutableListOf<Int>()
        fun write(value: Int, count: Int) { repeat(count) { bits += (value ushr it) and 1 } }
        fun code(value: Int, count: Int) { for (at in count - 1 downTo 0) bits += (value ushr at) and 1 }
        fun align() { while (bits.size % 8 != 0) bits += 0 }
        fun bytes(): ByteArray = ByteArray((bits.size + 7) / 8).also { output ->
            for (at in bits.indices) output[at / 8] = (output[at / 8].toInt() or (bits[at] shl (at % 8))).toByte()
        }
    }
}
