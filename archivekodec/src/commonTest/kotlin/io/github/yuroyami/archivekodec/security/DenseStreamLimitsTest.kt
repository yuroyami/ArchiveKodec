package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Format-level fixtures near each codec's maximum expansion ratio, independent of
 * our encoders. CPython zlib and cramjam's LZ4/Snappy decoders also accept them.
 * Finite headers keep the measured ratios below the asymptotic format ceilings.
 */
class DenseStreamLimitsTest {
    // RFC 1951 dynamic block: A and EOB have two-bit codes; length 258 and distance
    // one each have a one-bit code. One literal followed by 4096 two-bit matches.
    // Code-length symbols 18, 1, 2 have lengths 1, 2, 2 respectively.
    private fun deflate(): ByteArray =
        hex("edc081000000008020b6fda516a9") + ByteArray(1024) + hex("06")

    @Test
    fun defaultAcceptsDeflateNearItsFormatCeiling() {
        checkLimits(Codecs.DEFLATE, deflate(), 1 + 4096 * 258, 1010.0, 1032.0, 1000)
    }

    @Test
    fun defaultAcceptsGzipNearItsFormatCeiling() {
        // Same DEFLATE body, fixed header, CRC-32 and ISIZE from CPython zlib.
        val encoded = hex("1f8b08000000000000ff") + deflate() + hex("4be4c84401201000")
        checkLimits(Codecs.GZIP, encoded, 1 + 4096 * 258, 995.0, 1032.0, 900)
    }

    @Test
    fun defaultAcceptsZlibNearItsFormatCeiling() {
        val encoded = hex("7801") + deflate() + hex("503b5daa")
        checkLimits(Codecs.ZLIB, encoded, 1 + 4096 * 258, 1010.0, 1032.0, 1000)
    }

    @Test
    fun defaultAcceptsLz4NearItsFormatCeiling() {
        // One 1 MiB block: literal A, distance-one match, then the required final
        // five literals. Every 0xff extension byte adds 255 bytes to the match.
        val block = hex("1f410100") + ByteArray(4111) { 0xff.toByte() } + hex("f6504141414141")
        val size = ByteArray(4) { (block.size ushr (8 * it)).toByte() }
        // Version 1, independent blocks, 1 MiB block maximum; XXH32 header byte 51.
        val encoded = hex("04224d18606051") + size + block + ByteArray(4)
        checkLimits(Codecs.LZ4, encoded, 1024 * 1024, 250.0, 255.0, 200)
    }

    @Test
    fun defaultAcceptsRawSnappyNearItsFormatCeiling() {
        // Varint length, literal A, then 16384 copies: each three-byte tag copies
        // 64 bytes from distance one, approaching the format's 64:3 bound.
        val encoded = hex("8180400041") + copies(16384)
        checkLimits(Codecs.SNAPPY, encoded, 1 + 16384 * 64, 21.0, 64.0 / 3, 20)
    }

    @Test
    fun defaultAcceptsFramedSnappyNearItsFormatCeiling() {
        // Sixteen 64 KiB chunks. Each has a literal A, 1023 length-64 copies and
        // one length-63 copy. Chunk headers include an independent masked CRC-32C.
        val chunk = hex("00090c00cde3e8108080040041") + copies(1023) + hex("fa0100")
        val encoded = hex("ff060000734e61507059") + ByteArray(chunk.size * 16) { chunk[it % chunk.size] }
        checkLimits(Codecs.SNAPPY_FRAMED, encoded, 1024 * 1024, 21.0, 64.0 / 3, 20)
    }

    @Test
    fun defaultAcceptsStoresOneToOneRatio() {
        val plain = ByteArray(1024 * 1024) { 65 }
        checkLimits(Codecs.STORE, plain, plain.size, 1.0, 1.0, 0)
    }

    private fun copies(count: Int): ByteArray = ByteArray(3 * count) {
        when (it % 3) {
            0 -> 0xfe.toByte()
            1 -> 1
            else -> 0
        }
    }

    private fun checkLimits(
        codec: Codec, encoded: ByteArray, size: Int,
        minimumRatio: Double, maximumRatio: Double, strictRatio: Int,
    ) {
        val ratio = size.toDouble() / encoded.size
        assertTrue(ratio in minimumRatio..maximumRatio, "${codec.id}: ratio $ratio")
        assertContentEquals(ByteArray(size) { 65 }, codec.decompress(encoded, ArchiveLimits.DEFAULT))
        val ratioFailure = assertFailsWith<DecompressionBombException> {
            codec.decompress(encoded, ArchiveLimits { maxCompressionRatio = strictRatio })
        }
        assertTrue(ratioFailure.message!!.contains("ratio"), ratioFailure.toString())
        val byteFailure = assertFailsWith<DecompressionBombException> {
            codec.decompress(encoded, ArchiveLimits { maxUncompressedBytes = size - 1L })
        }
        assertTrue(byteFailure.message!!.contains("uncompressed output"), byteFailure.toString())
    }
}
