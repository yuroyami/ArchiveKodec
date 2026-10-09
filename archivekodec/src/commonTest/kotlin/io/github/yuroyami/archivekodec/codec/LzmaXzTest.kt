package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** LZMA, LZMA2 and xz (#18, #19, #20). Fixtures come from xz 5.4 and CPython's lzma; encoder output was checked with liblzma. */
@OptIn(ExperimentalEncodingApi::class)
class LzmaXzTest {
    private val hello = "hello world".encodeToByteArray()
    private fun b64(text: String) = Base64.decode(text)

    // xz --format=lzma: unknown size, so the stream ends with an end marker.
    private val helloLzma = b64("XQAAgAD//////////wA0GUnujekXiTozYAX3z2T/+3ggAA==")
    private val helloXz = b64("/Td6WFoAAATm1rRGAgAhARYAAAB0L+WjAQAKaGVsbG8gd29ybGQAANpSI+/NfgNTAAEjC8Ib/QkftvN9AQAAAAAEWVo=")                 // xz: CRC-64
    private val helloCrc32 = b64("/Td6WFoAAAFpIt42AgAhARYAAAB0L+WjAQAKaGVsbG8gd29ybGQAAIURSg0AAR8LPWIOepBCmQ0BAAAAAAFZWg==")           // xz -C crc32
    private val helloSha256 = b64("/Td6WFoAAArh+wyhAgAhARYAAAB0L+WjAQAKaGVsbG8gd29ybGQAALlNJ7mTTT4IpS5S19p9q/rEhO/jelOA7pCI96zi783pAAE7C5uD5osYm0uaAQAAAAAKWVo=")         // xz -C sha256
    private val helloNone = b64("/Td6WFoAAAD/EtlBAgAhARYAAAB0L+WjAQAKaGVsbG8gd29ybGQAAAABGws5p2IeBnKeegEAAAAAAFla")             // xz -C none
    private val helloDelta = b64("/Td6WFoAAATm1rRGAgEDAQEhARYcR3hW4ADbABVdADQZPIBP52HL6jBRULoWe8v1v0AAAAAAAAA7NpgdnCJNRwABMdwBAAAA6PXK+LHEZ/sCAAAAAARZWg==")           // xz --delta=dist=2 --lzma2, of "hello world" × 20
    private val helloX86 = b64("/Td6WFoAAATm1rRGAgEEACEBFgANhjUfAQAKaGVsbG8gd29ybGQAANpSI+/NfgNTAAEjC8Ib/QkftvN9AQAAAAAEWVo=")               // xz --x86 --lzma2
    private val helloLzma2 = b64("AQAKaGVsbG8gd29ybGQA")           // raw LZMA2 from CPython
    private val zip14 = b64("UEsDBD8AAgAOABAbSV1Kjpg0NgEAAOITAAAIAAAAbm90ZS50eHQJBAUAXQAAgAAANh6J3X1JPjRU9RYF9B6eEy+hwsGHNfhPQS1bPK/AYkLasPlYCpjkmdWyAWugg1dd4P2hvTY8/qeGgHjXdmytKv6NYEb9vEYi7Sw+Mmum0BVocqDi/R/r9C8u9hAQdMQJD/eBcYRhARFae3OdEl/rJfMdMDjAWup+8MjF6i2prcKTowVT+XrxucyivXSu6RhqRuUKB9TIOvomCPPUGH5ucaWoWu6hZA97RS33+p6BlwBecSmXSHk448oqZ6Dfe6y3/Mdb5OSS+oHydue4IlctI+5hug0vbaYCbsbIAcif0AazNRY96esk6/YobrxhDMemmcALrne06AfS86sR8WYyEUzoI3FVjijunfNHd3PDF4DLKtXrJoHyjUp9i0+hPLL/2nYV5Qf9///3EJGXUEsBAj8DPwACAA4AEBtJXUqOmDQ2AQAA4hMAAAgAAAAAAAAAAAAAAIABAAAAAG5vdGUudHh0UEsFBgAAAAABAAEANgAAAFwBAAAAAA==")                      // CPython zipfile ZIP_LZMA, 200 lines

    // xz -6 --block-size=300000 of generated(1_100_000): four blocks, with long runs and distant matches.
    private val multiBlock = Base64.decode(
        "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")

    /** The fixture generator: an LCG choosing words, newlines and long runs of 'z'. */
    private fun generated(size: Int): ByteArray {
        val words = listOf("alpha", "beta", "gamma", "delta").map { it.encodeToByteArray() }
        var x = 1L
        val out = ArrayList<Byte>(size + 400)
        while (out.size < size) {
            x = (x * 1103515245 + 12345) and 0x7fffffff
            val r = (x shr 8) % 64
            if (r == 0L) repeat(((x shr 4) % 300).toInt()) { out.add('z'.code.toByte()) }
            for (b in words[if (r < 8) ((x shr 16) % 4).toInt() else 0]) out.add(b)
            out.add(if (r == 9L) 10 else 32)
        }
        return out.subList(0, size).toByteArray()
    }

    @Test
    fun decodesXzToolOutput() {
        assertContentEquals(hello, Codecs.LZMA.decompress(helloLzma))
        for (stream in listOf(helloXz, helloCrc32, helloSha256, helloNone)) assertContentEquals(hello, Codecs.XZ.decompress(stream))
        assertContentEquals(hello, Codecs.LZMA2.decompress(helloLzma2))
        assertContentEquals(ByteArray(20 * hello.size) { hello[it % hello.size] }, Codecs.XZ.decompress(helloDelta))
        assertContentEquals(generated(1_100_000), Codecs.XZ.decompress(multiBlock))
    }

    @Test
    fun facadeAndRegistry() {
        assertContentEquals(hello, ArchiveKodec.decompress(helloXz))
        assertSame(Codecs.XZ, ArchiveKodec.xz)
        assertSame(Codecs.LZMA, ArchiveKodec.lzma)
        assertSame(Codecs.LZMA2, ArchiveKodec.lzma2)
        assertSame(Codecs.LZMA, CodecRegistry.DEFAULT.byZipMethod(14))
    }

    @Test
    fun concatenatedStreamsAndPaddingDecode() {
        assertContentEquals(hello + hello, Codecs.XZ.decompress(helloXz + helloCrc32))
        assertContentEquals(hello + hello, Codecs.XZ.decompress(helloXz + ByteArray(8) + helloSha256 + ByteArray(4)))
        assertFailsWith<ArchiveFormatException> { Codecs.XZ.decompress(helloXz + ByteArray(3)) }
    }

    @Test
    fun zipMethodFourteenEntriesRead() {
        ZipReader(ByteArrayRandomAccessSource(zip14)).use { reader ->
            val entry = reader.entries().single()
            assertEquals(14, entry.zipMethod)
            assertEquals(CodecId.LZMA, entry.codec)
            assertEquals((0 until 200).joinToString("") { "lzma inside zip, line $it\n" }, reader.read(entry).decodeToString())
        }
    }

    @Test
    fun bcjFiltersAreRefusedByName() {
        val error = assertFailsWith<ArchiveUnsupportedException> { Codecs.XZ.decompress(helloX86) }
        assertTrue(error.message!!.contains("x86"), error.message)
    }

    @Test
    fun corruptionIsDetected() {
        // A flipped payload byte must fail the block check or the LZMA2 framing, never pass silently.
        for (stream in listOf(helloXz, helloCrc32, helloSha256)) {
            for (position in 12 until stream.size - 12) {
                val damaged = stream.copyOf().also { it[position] = (it[position].toInt() xor 0x01).toByte() }
                val result = runCatching { Codecs.XZ.decompress(damaged) }
                assertTrue(result.isFailure, "byte $position of ${stream.size} decoded to ${result.getOrNull()?.decodeToString()}")
            }
        }
        for (bad in listOf(ByteArray(0), helloXz.copyOf(20), helloLzma.copyOf(20), helloLzma + byteArrayOf(0))) {
            assertFailsWith<ArchiveFormatException> {
                if (bad.size > 6 && bad[0].toInt() == 0xFD.toByte().toInt()) Codecs.XZ.decompress(bad) else Codecs.LZMA.decompress(bad)
            }
        }
    }

    @Test
    fun encodersRoundTrip() {
        val random = ByteArray(200_000).also { var x = 9L; for (i in it.indices) { x = x * 6364136223846793005L + 1442695040888963407L; it[i] = (x ushr 56).toByte() } }
        val cases = listOf(ByteArray(0), hello, ByteArray(1_000_000), random, generated(1_100_000), random + generated(300_000) + random)
        for (data in cases) {
            for (codec in listOf(Codecs.LZMA, Codecs.LZMA2, Codecs.XZ)) {
                assertContentEquals(data, codec.decompress(codec.compress(data)), "${codec.id} ${data.size}")
            }
        }
        assertTrue(Codecs.XZ.compress(generated(1_100_000)).size < 60_000)
        assertEquals(32, Codecs.XZ.compress(ByteArray(0)).size)
    }

    @Test
    fun limitsApply() {
        assertFailsWith<DecompressionBombException> { Codecs.XZ.decompress(multiBlock, ArchiveLimits { maxUncompressedBytes = 500_000 }) }
        val zeros = Codecs.LZMA.compress(ByteArray(1_000_000))
        // The .lzma header declares the size, so the bomb is refused before decoding.
        assertFailsWith<DecompressionBombException> { Codecs.LZMA.decompress(zeros, ArchiveLimits { maxUncompressedBytes = 1000 }) }
        assertFailsWith<DecompressionBombException> { Codecs.LZMA2.decompress(Codecs.LZMA2.compress(ByteArray(1_000_000)), ArchiveLimits { maxCompressionRatio = 100 }) }
    }
}
