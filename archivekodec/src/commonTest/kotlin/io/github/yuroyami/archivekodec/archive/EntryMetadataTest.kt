package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.ar.ArWriter
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioWriter
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.tar.TarWriter
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Modification times, Unix modes and comments on read, and ZipWriter's matching settings (#88). */
@OptIn(ExperimentalEncodingApi::class)
class EntryMetadataTest {
    private val execMode = 0x81ED   // 0o100755
    private val dataMode = 0x81A0   // 0o100640

    // TZ=UTC Info-ZIP zip 3.0 of run.sh (0755) and data.txt (0640), both modified at 1700000001.
    private val utZip = Base64.decode(
        "UEsDBAoAAAAAAKuxblcdnfsECgAAAAoAAAAGABwAcnVuLnNoVVQJAAMB8VNlAfFTZXV4CwABBAAAAAAEAAAAACMhL2Jpbi9zaApQ" +
        "SwMECgAAAAAAq7FuV2Pz860EAAAABAAAAAgAHABkYXRhLnR4dFVUCQADAfFTZQHxU2V1eAsAAQQAAAAABAAAAABkYXRhUEsBAh4D" +
        "CgAAAAAAq7FuVx2d+wQKAAAACgAAAAYAGAAAAAAAAQAAAO2BAAAAAHJ1bi5zaFVUBQADAfFTZXV4CwABBAAAAAAEAAAAAFBLAQIe" +
        "AwoAAAAAAKuxbldj8/OtBAAAAAQAAAAIABgAAAAAAAEAAACggUoAAABkYXRhLnR4dFVUBQADAfFTZXV4CwABBAAAAAAEAAAAAFBL" +
        "BQYAAAAAAgACAJoAAACQAAAADABhcmNoaXZlIG5vdGU=")

    // The same files with zip -X: DOS time only, which Info-ZIP rounded up to 1700000002.
    private val dosZip = Base64.decode(
        "UEsDBAoAAAAAAKuxblcdnfsECgAAAAoAAAAGAAAAcnVuLnNoIyEvYmluL3NoClBLAwQKAAAAAACrsW5XY/PzrQQAAAAEAAAACAAA" +
        "AGRhdGEudHh0ZGF0YVBLAQIeAwoAAAAAAKuxblcdnfsECgAAAAoAAAAGAAAAAAAAAAEAAADtgQAAAABydW4uc2hQSwECHgMKAAAA" +
        "AACrsW5XY/PzrQQAAAAEAAAACAAAAAAAAAABAAAAoIEuAAAAZGF0YS50eHRQSwUGAAAAAAIAAgBqAAAAWAAAAAAA")

    // GNU ar rcU, keeping real times and modes.
    private val ar = Base64.decode(
        "ITxhcmNoPgpydW4uc2gvICAgICAgICAgMTcwMDAwMDAwMSAgMCAgICAgMCAgICAgMTAwNzU1ICAxMCAgICAgICAgYAojIS9iaW4v" +
        "c2gKZGF0YS50eHQvICAgICAgIDE3MDAwMDAwMDEgIDAgICAgIDAgICAgIDEwMDY0MCAgNCAgICAgICAgIGAKZGF0YQ==")

    // GNU tar --format=ustar.
    private val tar = Gzip.decompress(Base64.decode(
        "H4sIAAAAAAACA+3TsQqEMAzG8cz3FCfu+qWk9nkqDt6i0Ea4x79yo3A6VTjMb8nQDIHyT9vS5ZmqQhG8/85iPwF2xOKdhAABE7is" +
        "Cz1BF9iyxlROSeuqR3tn73+qbfrxtfR5fpC5oSlq7PSttfsfBL/7h+z6ZzfA+r/q/60CY4y5nw9qzd/GAAwAAA=="))

    private fun metadata(reader: ArchiveReader) = reader.use { r ->
        r.entries().map { Triple(it.name, it.lastModifiedEpochSeconds, it.unixMode) }
    }

    @Test
    fun systemToolArchivesReportTimesAndModes() {
        val expected = listOf(Triple("run.sh", 1_700_000_001L, execMode), Triple("data.txt", 1_700_000_001L, dataMode))
        assertEquals(expected, metadata(ZipReader(ByteArrayRandomAccessSource(utZip))))
        assertEquals(expected.map { it.copy(second = 1_700_000_002L) }, metadata(ZipReader(ByteArrayRandomAccessSource(dosZip))))
        assertEquals(expected, metadata(ArReader(ByteArrayRandomAccessSource(ar))))
        assertEquals(expected, metadata(TarReader(ByteArrayRandomAccessSource(tar))))
    }

    @Test
    fun dosTimeIsReadAsUtcAndUnixModeFromTheHostByte() {
        // The fixture from #88: a STORE entry patched to host 3, 2001-02-03 07:08:10 and 0o100755.
        val zip = ZipWriter.write(listOf(ZipWriter.FileSpec("run.sh", "#!/bin/sh\n".encodeToByteArray(), ZipMethod.STORED)))
        val cd = 46
        zip[cd + 5] = 3
        val time = (7 shl 11) or (8 shl 5) or 5
        val date = ((2001 - 1980) shl 9) or (2 shl 5) or 3
        zip[cd + 12] = time.toByte(); zip[cd + 13] = (time shr 8).toByte()
        zip[cd + 14] = date.toByte(); zip[cd + 15] = (date shr 8).toByte()
        zip[cd + 40] = 0xED.toByte(); zip[cd + 41] = 0x81.toByte()
        ArchiveKodec.open(ByteArrayRandomAccessSource(zip)).use { reader ->
            val entry = reader.entries().single()
            assertEquals(981_184_090L, entry.lastModifiedEpochSeconds)
            assertEquals(0b1000000111101101, entry.unixMode)
            assertNull(entry.comment)
        }
        // Without the Unix host byte, the attributes are DOS attributes and no mode is reported.
        zip[cd + 5] = 0
        ZipReader(ByteArrayRandomAccessSource(zip)).use { assertNull(it.entries().single().unixMode) }
        // An invalid DOS date (month 0) reports no time.
        zip[cd + 14] = 0; zip[cd + 15] = 0
        ZipReader(ByteArrayRandomAccessSource(zip)).use { assertNull(it.entries().single().lastModifiedEpochSeconds) }
    }

    @Test
    fun zipWriterSettingsRoundTrip() {
        val spec = ZipWriter.FileSpec("run.sh", "#!/bin/sh\n".encodeToByteArray(), ZipMethod.STORED) {
            lastModifiedEpochSeconds = 981_184_091
            unixMode = execMode
            comment = "entry note é"
        }
        val dir = ZipWriter.FileSpec("bin/", byteArrayOf()) { unixMode = 0x41ED }
        val plain = ZipWriter.FileSpec("plain.txt", "x".encodeToByteArray())
        val zip = ZipWriter.write(listOf(spec, dir, plain), "archive note")
        ZipReader(ByteArrayRandomAccessSource(zip)).use { reader ->
            val (file, folder, other) = reader.entries()
            // The extended timestamp keeps the odd second the DOS field cannot.
            assertEquals(981_184_091L, file.lastModifiedEpochSeconds)
            assertEquals(execMode, file.unixMode)
            assertEquals("entry note é", file.comment)
            assertEquals(0x41ED, folder.unixMode)
            assertTrue(folder.isDirectory)
            assertEquals(315_532_800L, other.lastModifiedEpochSeconds) // the default 1980-01-01
            assertNull(other.unixMode)
            assertNull(other.comment)
            assertEquals("#!/bin/sh\n", reader.read(file).decodeToString())
        }
        assertTrue(zip.copyOfRange(zip.size - 12, zip.size).decodeToString() == "archive note")
    }

    @Test
    fun dosFieldsRoundOddSecondsDownAndTimesOutsideIntUseDosOnly() {
        // 2096 does not fit the 32-bit extended timestamp, so only the DOS field is written.
        val zip = ZipWriter.write(listOf(ZipWriter.FileSpec("late", byteArrayOf()) { lastModifiedEpochSeconds = 4_000_000_001 }))
        ZipReader(ByteArrayRandomAccessSource(zip)).use { assertEquals(4_000_000_000L, it.entries().single().lastModifiedEpochSeconds) }
        // Before 1980 the DOS field clamps, but the extended timestamp keeps the real time.
        val early = ZipWriter.write(listOf(ZipWriter.FileSpec("early", byteArrayOf()) { lastModifiedEpochSeconds = 0 }))
        ZipReader(ByteArrayRandomAccessSource(early)).use { assertEquals(0L, it.entries().single().lastModifiedEpochSeconds) }
    }

    @Test
    fun defaultSpecsKeepTheirBytesAndInvalidSettingsAreRefused() {
        val plain = ZipWriter.FileSpec("a.txt", "abc".encodeToByteArray(), ZipMethod.STORED)
        val bytes = ZipWriter.write(listOf(plain))
        assertEquals(30 + 5 + 3 + 46 + 5 + 22, bytes.size)
        assertContentEquals(bytes, ZipWriter.write(listOf(plain), ""))
        assertFailsWith<IllegalArgumentException> { ZipWriter.FileSpec("a", byteArrayOf()) { unixMode = 0x10000 } }
        assertFailsWith<IllegalArgumentException> { ZipWriter.FileSpec("a", byteArrayOf()) { comment = "x".repeat(65_536) } }
        assertFailsWith<IllegalArgumentException> { ZipWriter.FileSpec("a", byteArrayOf()) { comment = "\uD800" } }
        assertFailsWith<IllegalArgumentException> { ZipWriter.write(listOf(plain), "x".repeat(65_536)) }
        val timed = plain.copy { lastModifiedEpochSeconds = 5 }
        assertNotEquals(plain, timed)
        assertEquals(timed, plain.copy { lastModifiedEpochSeconds = 5 })
        assertEquals(timed.hashCode(), plain.copy { lastModifiedEpochSeconds = 5 }.hashCode())
        assertTrue(timed.toString().endsWith("lastModifiedEpochSeconds=5)"), timed.toString())
        assertTrue(plain.toString().endsWith("method=STORED)"), plain.toString())
    }

    @Test
    fun writerMetadataRoundTripsThroughTarArAndCpio() {
        val payload = "x".encodeToByteArray()
        val tarBytes = TarWriter.write(listOf(TarWriter.FileSpec("f", payload) { mode = 0x1ED; modificationTime = 1_234 }))
        assertEquals(listOf(Triple("f", 1_234L, execMode)), metadata(TarReader(ByteArrayRandomAccessSource(tarBytes))))
        val arBytes = ArWriter.write(listOf(ArWriter.FileSpec("f", payload) { mode = dataMode; modificationTime = 1_234 }))
        assertEquals(listOf(Triple("f", 1_234L, dataMode)), metadata(ArReader(ByteArrayRandomAccessSource(arBytes))))
        for (dialect in CpioWriter.Dialect.entries) {
            val cpio = CpioWriter.write(listOf(CpioWriter.FileSpec("f", payload) { mode = execMode; modificationTime = 1_234 }), dialect)
            assertEquals(listOf(Triple("f", 1_234L, execMode)), metadata(CpioReader(ByteArrayRandomAccessSource(cpio))), "$dialect")
        }
    }

    @Test
    fun tarWithEmptyTimeAndModeFieldsReportsNull() {
        val bytes = TarWriter.write(listOf(TarWriter.FileSpec("f", byteArrayOf())))
        for (i in 100 until 108) bytes[i] = 0
        for (i in 136 until 148) bytes[i] = 0
        // Re-checksum the edited header.
        for (i in 148 until 156) bytes[i] = ' '.code.toByte()
        val sum = (0 until 512).sumOf { bytes[it].toInt() and 0xFF }
        (sum.toString(8).padStart(6, '0') + "\u0000 ").encodeToByteArray().copyInto(bytes, 148)
        TarReader(ByteArrayRandomAccessSource(bytes)).use { reader ->
            val entry = reader.entries().single()
            assertNull(entry.lastModifiedEpochSeconds)
            assertNull(entry.unixMode)
        }
    }

    @Test
    fun entryMetadataTakesPartInValueSemantics() {
        val base = ArchiveEntry("a", 1, false)
        val moded = base.copy { unixMode = execMode; comment = "c" }
        assertNotEquals(base, moded)
        assertEquals(moded, ArchiveEntry("a", 1, false) { unixMode = execMode; comment = "c" })
        assertEquals(moded.hashCode(), ArchiveEntry("a", 1, false) { unixMode = execMode; comment = "c" }.hashCode())
        assertTrue(moded.toString().contains("unixMode=100755, comment=\"c\""), moded.toString())
    }
}
