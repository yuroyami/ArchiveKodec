package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ForeignFacadeFixtures
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class MalformedMutationTest {
    private val limits = ArchiveLimits {
        maxUncompressedBytes = 4096
        maxCompressionRatio = 4096
        maxEntries = 16
    }
    private val payload = "foreign mutation payload\n".repeat(8).encodeToByteArray()

    @Test
    fun everyCodecUsesTheSameOutputAndRatioLimitException() {
        val data = "small limit".encodeToByteArray()
        for (id in Codecs.implemented) {
            val codec = Codecs.byId(id)!!
            val encoded = codec.compress(data)
            assertContentEquals(data, codec.decompress(encoded, ArchiveLimits { maxUncompressedBytes = data.size.toLong() }), "$id exact budget")
            assertFailsWith<DecompressionBombException>("$id output limit") {
                codec.decompress(encoded, ArchiveLimits { maxUncompressedBytes = data.size - 1L })
            }
            assertFailsWith<DecompressionBombException>("$id ratio limit") {
                codec.decompress(encoded, ArchiveLimits { maxCompressionRatio = 0 })
            }
            val zero = ArchiveLimits {
                maxUncompressedBytes = 0
                maxCompressionRatio = 0
            }
            assertContentEquals(byteArrayOf(), codec.decompress(codec.compress(byteArrayOf()), zero), "$id empty output")
            assertFailsWith<DecompressionBombException>("$id zero budget") { codec.decompress(encoded, zero) }
        }
    }

    @Test
    fun seededDecoderMutationsStayWithinTheTypedContract() {
        val started = TimeSource.Monotonic.markNow()
        // CPython zlib/gzip, liblz4 1.10.0 and libsnappy 1.2.2 output.
        val stored = hex("01c80037ff") + payload
        val fixed = hex("4bcb2f4acd4ccf53c82d2d492cc9cccf532848acccc94f4ce14a1b5a1200")
        val dynamic = hex("edcab70180301004c19c2aae358cf0e841bc70d54317241bcf781fb4e5a19e54253ba35abb34e665dd654748f28fe7f2b9d558573897cbe572b95c2e97cbe5727fbb2f")
        val dynamicPayload = "the quick brown fox jumps over the lazy dog\n".repeat(80).encodeToByteArray()
        for ((type, bytes) in listOf(stored, fixed, dynamic).withIndex()) {
            assertEquals(type, (bytes[0].toInt() ushr 1) and 3, "foreign DEFLATE block type")
        }
        val corpus = listOf(
            Decoder("STORE", payload, payload) { Codecs.STORE.decompress(it, limits) },
            Decoder("DEFLATE stored", stored, payload) { Codecs.DEFLATE.decompress(it, limits) },
            Decoder("DEFLATE fixed", fixed, payload) { Codecs.DEFLATE.decompress(it, limits) },
            Decoder("DEFLATE dynamic", dynamic, dynamicPayload) { Codecs.DEFLATE.decompress(it, limits) },
            Decoder("GZIP", hex("1f8b08000000000002ff4bcb2f4acd4ccf53c82d2d492cc9cccf532848acccc94f4ce14a1b5a120040a5e27ec8000000"), payload) { Codecs.GZIP.decompress(it, limits) },
            Decoder("ZLIB", hex("789c4bcb2f4acd4ccf53c82d2d492cc9cccf532848acccc94f4ce14a1b5a12002c064c79"), payload) { Codecs.ZLIB.decompress(it, limits) },
            Decoder("LZ4 frame", hex("04224d186c40c800000000000000a424000000ff0a666f726569676e206d75746174696f6e207061796c6f61640a190097506c6f61640a00000000d93a4998"), payload) { Codecs.LZ4.decompress(it, limits) },
            Decoder("SNAPPY", hex("c80160666f726569676e206d75746174696f6e207061796c6f61640afe1900fe1900ba1900"), payload) { Codecs.SNAPPY.decompress(it, limits) },
            Decoder("LZ4 block", hex("ff0a666f726569676e206d75746174696f6e207061796c6f61640a190097506c6f61640a"), payload) { Lz4.blockDecompress(it, 0, it.size) },
            Decoder("Inflate cursor", dynamic, dynamicPayload) {
                val output = Inflate.Output(limits, it.size)
                val next = Inflate.inflateInto(it, 0, it.size, output)
                assertTrue(next in 0..it.size)
                output.toByteArray()
            },
        )
        for (fixture in corpus) {
            assertContentEquals(fixture.output, fixture.decode(fixture.bytes), "${fixture.name} positive control")
            if (fixture.name != "STORE") {
                assertFailsWith<ArchiveException>("${fixture.name} empty input") { fixture.decode(byteArrayOf()) }
            }
            mutations(fixture.name, fixture.bytes, requireRejection = fixture.name != "STORE") { bytes, _ ->
                val output = fixture.decode(bytes)
                // Raw blocks expose no limits parameter. Each input byte can add
                // at most 255 length-extension bytes, plus token/match constants.
                val maximum = if (fixture.name == "LZ4 block") bytes.size * 256 + 64 else 4096
                assertTrue(output.size <= maximum, "${fixture.name} exceeded its output bound")
            }
        }
        println("Decoder mutation suite: ${started.elapsedNow().inWholeNanoseconds} ns")
    }

    @Test
    fun seededReaderMutationsCoverListingAndPayloadReads() {
        val started = TimeSource.Monotonic.markNow()
        val foreign = ForeignFacadeFixtures
        val corpus = listOf(
            Container("v7 TAR", foreign.v7, ::TarReader, listOf("empty-dir/", "sample.txt"), intArrayOf(0, 512)),
            Container("USTAR", foreign.ustar, ::TarReader, listOf("empty-dir/", foreign.longPath), intArrayOf(0, 512)),
            Container("PAX TAR", foreign.pax, ::TarReader, listOf("empty-dir/", "sample.txt"), intArrayOf(0, 1024, 1536, 2560)),
            Container("GNU TAR", foreign.gnu, ::TarReader, listOf("empty-dir/", foreign.longName), intArrayOf(0, 512, 1536)),
            Container("GNU binary-time TAR", foreign.gnuBinaryTime, ::TarReader, listOf("sample.txt"), intArrayOf(0)),
            Container("GNU ar", foreign.ar, ::ArReader, listOf("sample.txt", foreign.longName)),
            Container("newc CPIO", foreign.newc, ::CpioReader, listOf("sample.txt")),
            Container("odc CPIO", foreign.odc, ::CpioReader, listOf("sample.txt")),
            Container("crc CPIO", crcCpio(), ::CpioReader, listOf("sample.txt")),
            Container("ZIP64 STORE", foreign.zip64, ::ZipReader, listOf("sample.txt")),
            // CPython zipfile: one independently encoded DEFLATE member.
            Container("ZIP DEFLATE", hex("504b030414000000080000002150b770dd4619000000170000000a00000073616d706c652e7478744bcb2f4acd4ccf53484b4c4e4c49552848acccc94f4ce10200504b0102140314000000080000002150b770dd4619000000170000000a000000000000000000000080010000000073616d706c652e747874504b0506000000000100010038000000410000000000"), ::ZipReader, listOf("sample.txt")),
        )
        for (fixture in corpus) {
            fixture.open(ByteArrayRandomAccessSource(fixture.bytes)).use { reader ->
                val entries = reader.entries()
                assertEquals(fixture.names, entries.map { it.name }, "${fixture.name} positive control")
                for (entry in entries) {
                    assertContentEquals(if (entry.isDirectory) byteArrayOf() else foreign.payload, reader.read(entry, limits), fixture.name)
                }
            }
            var payloadReads = 0
            mutations(fixture.name, fixture.bytes) { bytes, iteration ->
                // Half of the byte-changing mutations retain correct TAR checksums
                // so numeric/name/range parsing is exercised beyond the checksum guard.
                if (iteration % 4 == 1) repairTarChecksums(bytes, fixture.tarHeaders)
                fixture.open(ByteArrayRandomAccessSource(bytes)).use { reader ->
                    val entries = reader.entries()
                    assertTrue(entries.size <= 16, "${fixture.name} unexpectedly multiplied its entries")
                    for (entry in entries) {
                        assertTrue(reader.read(entry, limits).size <= 4096)
                        if (!entry.isDirectory) payloadReads++
                    }
                }
            }
            assertTrue(payloadReads > 0, "${fixture.name} mutations never reached a payload read")
        }
        println("Reader mutation suite: ${started.elapsedNow().inWholeNanoseconds} ns")
    }

    private class Decoder(val name: String, val bytes: ByteArray, val output: ByteArray, val decode: (ByteArray) -> ByteArray)
    private class Container(
        val name: String, val bytes: ByteArray, val open: (RandomAccessSource) -> ArchiveReader,
        val names: List<String>, val tarHeaders: IntArray = intArrayOf(),
    )

    private fun mutations(name: String, original: ByteArray, requireRejection: Boolean = true, run: (ByteArray, Int) -> Unit) {
        val random = Seed(42)
        var rejected = 0
        // Boundary truncations are guaranteed even when the random seed happens
        // to select only complete records for a particular archive dialect.
        val boundaries = listOf(0, 1, 2, 4, 7, 8, 15, 16, 31, 32, original.size / 2, original.size - 1)
            .filter { it < original.size }.distinct()
        val cases = boundaries.map { original.copyOf(it) } + List(128) { iteration ->
            when (iteration % 4) {
                0 -> original.copyOf(random.next(original.size))
                3 -> original + ByteArray(1 + random.next(4)) { random.next(256).toByte() }
                else -> original.copyOf().also { bytes ->
                    repeat(1 + random.next(4)) {
                        // Alternate header-biased and whole-input positions.
                        val position = random.next(if (iteration and 4 == 0) minOf(512, bytes.size) else bytes.size)
                        bytes[position] = if (iteration % 4 == 1) {
                            (bytes[position].toInt() xor (1 shl random.next(8))).toByte()
                        } else {
                            (bytes[position].toInt() + 1 + random.next(255)).toByte()
                        }
                    }
                }
            }
        }
        for ((index, bytes) in cases.withIndex()) {
            try {
                run(bytes, index - boundaries.size)
            } catch (_: ArchiveException) {
                rejected++
            } catch (error: Throwable) {
                throw AssertionError("$name mutation $index escaped the typed contract (${bytes.size} input bytes)", error)
            }
        }
        if (requireRejection) assertTrue(rejected > 0, "$name mutation corpus did not reject any input")
    }

    private fun repairTarChecksums(bytes: ByteArray, headers: IntArray) {
        for (offset in headers) {
            if (bytes.size - offset < 512) continue
            bytes.fill(32, offset + 148, offset + 156)
            var sum = 0
            for (i in offset until offset + 512) sum += bytes[i].toInt() and 255
            sum.toString(8).padStart(6, '0').encodeToByteArray().copyInto(bytes, offset + 148)
            bytes[offset + 154] = 0
        }
    }

    private fun crcCpio(): ByteArray = ForeignFacadeFixtures.newc.copyOf().also { bytes ->
        // Convert the foreign newc records to the crc dialect, independently
        // computing its unsigned byte sum; the trailer has no body.
        val sum = ForeignFacadeFixtures.payload.sumOf { it.toInt() and 255 }
        for (offset in listOf(0, 148)) {
            "070702".encodeToByteArray().copyInto(bytes, offset)
            (if (offset == 0) sum else 0).toString(16).padStart(8, '0').encodeToByteArray().copyInto(bytes, offset + 102)
        }
    }

    private class Seed(private var value: Int) {
        fun next(bound: Int): Int {
            value = value xor (value shl 13)
            value = value xor (value ushr 17)
            value = value xor (value shl 5)
            return (value ushr 1) % bound
        }
    }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
