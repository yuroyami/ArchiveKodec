package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArBsdFixtures
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.NameEncodingFixtures
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArchiveEntryCountTest {
    private class Fixture(val name: String, val bytes: ByteArray, val count: Int)

    private fun fixtures(): List<Fixture> = listOf(
        Fixture("ZIP", NameEncodingFixtures.utf8Zip, 3),
        Fixture("TAR including directories", RootArchiveFixtures.tar, 4),
        Fixture("ar short, GNU and BSD names", ar(), 3),
        Fixture("ar with a leading BSD symbol index", ArBsdFixtures.nativeArchive, 2),
        Fixture("cpio newc including directories", RootArchiveFixtures.newc, 4),
        Fixture("cpio odc including directories", RootArchiveFixtures.odc, 4),
        Fixture("cpio CRC hard links", crcRecord("a") + crcRecord("b") + crcRecord("c") + crcRecord("TRAILER!!!", byteArrayOf()), 3),
    )

    @Test
    fun rejectsDuringListingAndNeverCachesAPartialResult() {
        for (fixture in fixtures()) {
            for (ceiling in listOf(0, fixture.count - 1)) {
                val policy = ArchiveLimits { maxEntries = ceiling }
                ArchiveKodec.open(ByteArrayRandomAccessSource(fixture.bytes), policy).use { reader ->
                    repeat(2) {
                        val error = assertFailsWith<DecompressionBombException>(fixture.name) { reader.entries() }
                        assertTrue(error.message.orEmpty().contains("exceeds limit $ceiling"), fixture.name)
                    }
                }
            }
        }
    }

    @Test
    fun acceptsExactAndRelaxedLimitsWithoutCountingContainerMetadata() {
        for (fixture in fixtures()) {
            for (ceiling in listOf(fixture.count, fixture.count + 1, Int.MAX_VALUE)) {
                ArchiveKodec.open(ByteArrayRandomAccessSource(fixture.bytes), ArchiveLimits { maxEntries = ceiling }).use { reader ->
                    assertEquals(fixture.count, reader.entries().size, fixture.name)
                    assertEquals(fixture.count, reader.entries().size, "cached ${fixture.name}")
                }
            }
        }
    }

    @Test
    fun zeroAcceptsEmptyContainersAndTheirEndMarkers() {
        val empty = listOf(
            ZipWriter.write(emptyList()), ByteArray(1024), "!<arch>\n".encodeToByteArray(),
            crcRecord("TRAILER!!!", byteArrayOf()),
        )
        for (bytes in empty) {
            ArchiveKodec.open(ByteArrayRandomAccessSource(bytes), ArchiveLimits { maxEntries = 0 }).use { reader ->
                assertTrue(reader.entries().isEmpty(), reader.format.toString())
            }
        }
    }

    @Test
    fun stopsBeforeDecodingTheFirstExcessNameEvenWhenZipCountIsUnderstated() {
        val zip = NameEncodingFixtures.cp437Zip
        // Simulate a wrapped/understated classic count: the actual loop must still enforce the ceiling.
        zip.fill(0, zip.size - 14, zip.size - 10)
        val inputs = listOf(zip, RootArchiveFixtures.tar, ar(), RootArchiveFixtures.newc, RootArchiveFixtures.odc,
            crcRecord("a") + crcRecord("b") + crcRecord("c"))
        for (bytes in inputs) {
            var decoded = 0
            val decoder = ArchiveNameDecoder { name ->
                decoded++
                check(decoded <= 1) { "decoded a name beyond the entry ceiling" }
                ArchiveNameDecoder.CP437.decode(name)
            }
            ArchiveKodec.open(ByteArrayRandomAccessSource(bytes), decoder, ArchiveLimits { maxEntries = 1 }).use { reader ->
                assertFailsWith<DecompressionBombException>(reader.format.toString()) { reader.entries() }
                assertEquals(1, decoded, reader.format.toString())
            }
        }
    }

    @Test
    fun readerPolicyIsTheDefaultForReadsAndExplicitReadLimitsStillOverrideIt() {
        for (fixture in fixtures()) {
            val policy = ArchiveLimits { maxEntries = fixture.count; maxUncompressedBytes = 0 }
            ArchiveKodec.open(ByteArrayRandomAccessSource(fixture.bytes), policy).use { reader ->
                assertSame(policy, reader.limits)
                val entry = reader.entries().first { !it.isDirectory && it.size > 0 }
                assertFailsWith<DecompressionBombException>(fixture.name) { reader.read(entry) }
                assertEquals(entry.size, reader.read(entry, ArchiveLimits.DEFAULT).size.toLong(), fixture.name)
            }
        }
    }

    @Test
    fun concreteConstructorsAcceptLimitsWithDefaultAndExplicitNameDecoders() {
        val policy = ArchiveLimits { maxEntries = 0 }
        for (fixture in fixtures()) {
            for (explicitDecoder in listOf(false, true)) {
                val source = ByteArrayRandomAccessSource(fixture.bytes)
                val decoder = ArchiveNameDecoder.UTF8
                val reader: ArchiveReader = when (ArchiveKodec.sniff(source)) {
                    ArchiveFormat.ZIP -> if (explicitDecoder) ZipReader(source, decoder, policy) else ZipReader(source, policy)
                    ArchiveFormat.TAR -> if (explicitDecoder) TarReader(source, decoder, policy) else TarReader(source, policy)
                    ArchiveFormat.AR -> if (explicitDecoder) ArReader(source, decoder, policy) else ArReader(source, policy)
                    ArchiveFormat.CPIO -> if (explicitDecoder) CpioReader(source, decoder, policy) else CpioReader(source, policy)
                    else -> error(fixture.name)
                }
                reader.use {
                    assertSame(policy, it.limits)
                    assertFailsWith<DecompressionBombException>(fixture.name) { it.entries() }
                }
            }
        }
    }

    private fun arMember(name: String, data: ByteArray): ByteArray {
        val header = name.padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) +
            "100644".padEnd(8) + data.size.toString().padEnd(10) + "`\n"
        return header.encodeToByteArray() + data + ByteArray(data.size and 1) { 10 }
    }

    private fun ar(): ByteArray {
        val inlineName = "bsd-member-with-a-long-name".encodeToByteArray()
        return "!<arch>\n".encodeToByteArray() + arMember("/", byteArrayOf()) +
            arMember("//", "gnu-member-with-a-long-name/\n".encodeToByteArray()) +
            arMember("short/", byteArrayOf(42)) + arMember("/0", byteArrayOf(42)) +
            arMember("#1/${inlineName.size}", inlineName + byteArrayOf(42))
    }

    private fun crcRecord(name: String, data: ByteArray = byteArrayOf(42)): ByteArray {
        val storedName = name.encodeToByteArray() + byteArrayOf(0)
        val fields = longArrayOf(1, 0x81a4, 0, 0, 3, 0, data.size.toLong(), 0, 0, 0, 0,
            storedName.size.toLong(), data.sumOf { it.toLong() and 255 })
        val header = ("070702" + fields.joinToString("") { it.toString(16).padStart(8, '0') }).encodeToByteArray()
        val named = header + storedName
        return named + ByteArray((4 - named.size % 4) % 4) + data + ByteArray((4 - data.size % 4) % 4)
    }
}
