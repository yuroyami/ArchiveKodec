package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.codec.ppmd.Ppmd7z
import io.github.yuroyami.archivekodec.codec.ppmd.PpmdZip
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

/**
 * PPMd (#15). The archives were written by 7-Zip 23.01; the inputs come from the generators
 * below, and are sized so that the models run out of memory and restart or cut off.
 */
@OptIn(ExperimentalEncodingApi::class)
class PpmdTest {
    // 7z a -m0=PPMd:o=6:mem=64k: text(20_000), var.H, restarting five times.
    private val text7z = Base64.decode(
        "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" +
        "gQAA",
    )
    // 7z a -tzip -mm=PPMd -mx=9 -mmem=1m -mo=16: dna(44_000) + text(8_000), var.I, cut off once.
    private val cutOffZip = Base64.decode(
        "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",
    )
    // The same at -mx=5, which restarts the model instead.
    private val restartZip = Base64.decode(
        "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",
    )

    private val words = ("the of and to in a is that for it as was with be by on not he I this are or his from at which but have an " +
        "had they you were their one all we can her has there been if more when will would who so no").split(" ")

    /** Words from a fixed list, separated by spaces, commas and full stops. */
    private fun text(size: Int): ByteArray {
        var x = 7L
        val out = StringBuilder()
        while (out.length < size) {
            x = (x * 1103515245 + 12345) and 0x7fffffff
            out.append(words[((x shr 16) % words.size).toInt()])
            val r = (x shr 8) % 16
            out.append(if (r == 0L) ". " else if (r == 1L) ", " else " ")
        }
        return out.substring(0, size).encodeToByteArray()
    }

    /** Random A, C, G and T, which make PPMd create contexts fast and so exhaust its memory. */
    private fun dna(size: Int): ByteArray {
        var x = 5L
        return ByteArray(size) {
            x = x * 6364136223846793005L + 1442695040888963407L
            "ACGT"[(x ushr 62).toInt()].code.toByte()
        }
    }

    private val dnaText = dna(44_000) + text(8_000)

    /** The compressed data of a ZIP's first entry. */
    private fun firstEntryData(zip: ByteArray): ByteArray {
        fun u16(o: Int) = (zip[o].toInt() and 0xFF) or ((zip[o + 1].toInt() and 0xFF) shl 8)
        val size = u16(18) or (u16(20) shl 16)
        val start = 30 + u16(26) + u16(28)
        return zip.copyOfRange(start, start + size)
    }

    @Test
    fun readsSevenZipArchives() {
        ArchiveKodec.open(text7z).use { reader ->
            val entry = reader.entries().single()
            assertEquals(CodecId.PPMD, entry.codec)
            assertContentEquals(text(20_000), reader.read(entry))
        }
        // The 7z packed stream starts after the 32-byte signature header.
        val packed = text7z.copyOfRange(32, 32 + 5348)
        val properties = byteArrayOf(6, 0, 0, 1, 0)
        var restarts = 0
        assertContentEquals(text(20_000), Ppmd7z.decompress(packed, properties, 20_000, ArchiveLimits.DEFAULT) { restarts = it.restarts })
        assertTrue(restarts > 1, "the fixture must restart the model, restarted $restarts times")
    }

    @Test
    fun readsZipArchivesWithBothRestoreMethods() {
        for (zip in listOf(cutOffZip, restartZip)) {
            ArchiveKodec.open(zip).use { reader -> assertContentEquals(dnaText, reader.read(reader.entries().single())) }
        }
        var cutOffs = 0
        assertContentEquals(dnaText, PpmdZip.decompress(firstEntryData(cutOffZip), ArchiveLimits.DEFAULT, dnaText.size.toLong()) { cutOffs = it.cutOffs })
        assertTrue(cutOffs > 0, "the cut-off fixture must cut the model off")
        var restarts = 0
        assertContentEquals(dnaText, PpmdZip.decompress(firstEntryData(restartZip), ArchiveLimits.DEFAULT, dnaText.size.toLong()) { restarts = it.restarts })
        assertTrue(restarts > 1, "the restart fixture must restart the model")
        // 7-Zip ends each entry with an end marker, so the codec decodes it without a size.
        assertContentEquals(dnaText, Codecs.PPMD.decompress(firstEntryData(cutOffZip)))
    }

    @Test
    fun facadeAndRegistry() {
        assertSame(Codecs.PPMD, ArchiveKodec.ppmd)
        assertSame(Codecs.PPMD, CodecRegistry.DEFAULT.byZipMethod(98))
        assertSame(Codecs.PPMD, CodecRegistry.DEFAULT.byName("PPMd"))
    }

    @Test
    fun encoderRoundTrips() {
        val random = ByteArray(100_000).also { var x = 3L; for (i in it.indices) { x = x * 6364136223846793005L + 1442695040888963407L; it[i] = (x ushr 56).toByte() } }
        for (data in listOf(ByteArray(0), byteArrayOf(7), text(200_000), random, ByteArray(300_000), dnaText)) {
            assertContentEquals(data, Codecs.PPMD.decompress(Codecs.PPMD.compress(data), ArchiveLimits.UNBOUNDED), "${data.size} bytes")
        }
        assertTrue(Codecs.PPMD.compress(text(200_000)).size < 50_000)
    }

    @Test
    fun malformedStreamsFail() {
        val stream = firstEntryData(cutOffZip)
        for (end in listOf(0, 1, 2, 5, 100, stream.size / 2, stream.size - 3)) {
            assertFailsWith<ArchiveFormatException>("truncated to $end") { Codecs.PPMD.decompress(stream.copyOf(end)) }
        }
        // Bit flips fail or decode to other bytes, and every failure is an archive error.
        for (position in listOf(2, 3, 10, 1000, 9000)) {
            val damaged = stream.copyOf().also { it[position] = (it[position].toInt() xor 0x10).toByte() }
            val result = runCatching { Codecs.PPMD.decompress(damaged) }
            result.exceptionOrNull()?.let { assertTrue(it is ArchiveException, "byte $position: $it") }
            assertTrue(result.isFailure || !result.getOrThrow().contentEquals(dnaText), "byte $position")
        }
        // Order 1, then the freeze restore method and an undefined one.
        assertFailsWith<ArchiveFormatException> { Codecs.PPMD.decompress(byteArrayOf(0x00, 0x00, 0, 0, 0, 0)) }
        assertFailsWith<ArchiveUnsupportedException> { Codecs.PPMD.decompress(byteArrayOf(0x05, 0x20, 0, 0, 0, 0)) }
        assertFailsWith<ArchiveFormatException> { Codecs.PPMD.decompress(byteArrayOf(0x05, 0x30, 0, 0, 0, 0)) }
        val packed = text7z.copyOfRange(32, 32 + 5348)
        assertFailsWith<ArchiveFormatException> { Ppmd7z.decompress(packed, byteArrayOf(1, 0, 0, 1, 0), 20_000, ArchiveLimits.DEFAULT) }
        assertFailsWith<ArchiveFormatException> { Ppmd7z.decompress(packed, byteArrayOf(6, 0, 1, 0, 0), 20_000, ArchiveLimits.DEFAULT) }
        assertFailsWith<ArchiveFormatException> { Ppmd7z.decompress(packed.copyOf(2000), byteArrayOf(6, 0, 0, 1, 0), 20_000, ArchiveLimits.DEFAULT) }
    }

    @Test
    fun limitsApply() {
        // The model memory a header asks for is checked before it is allocated.
        val error = assertFailsWith<DecompressionBombException> {
            Codecs.PPMD.decompress(firstEntryData(cutOffZip), ArchiveLimits { maxDecoderMemory = (1L shl 20) - 1 })
        }
        assertTrue(error.message!!.contains("model size"), error.message)
        assertContentEquals(dnaText, Codecs.PPMD.decompress(firstEntryData(cutOffZip), ArchiveLimits { maxDecoderMemory = 1L shl 20 }))
        val packed = text7z.copyOfRange(32, 32 + 5348)
        assertFailsWith<DecompressionBombException> {
            Ppmd7z.decompress(packed, byteArrayOf(6, 0, 0, 1, 0), 20_000, ArchiveLimits { maxDecoderMemory = 65535 })
        }
        assertFailsWith<DecompressionBombException> {
            Codecs.PPMD.decompress(firstEntryData(cutOffZip), ArchiveLimits { maxUncompressedBytes = 2L shl 20; maxCompressionRatio = 2 })
        }
        assertFailsWith<DecompressionBombException> {
            Codecs.PPMD.decompress(Codecs.PPMD.compress(ByteArray(1_000_000)), ArchiveLimits { maxCompressionRatio = 100 })
        }
    }
}
