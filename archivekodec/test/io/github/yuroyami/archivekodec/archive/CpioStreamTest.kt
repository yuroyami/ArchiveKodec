package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioStreamReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioStreamWriter
import io.github.yuroyami.archivekodec.archive.cpio.CpioWriter
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.StreamParity
import io.github.yuroyami.archivekodec.security.ArchivePathException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** CpioStreamWriter and CpioStreamReader against CpioWriter, CpioReader and GNU cpio. */
class CpioStreamTest {
    // GNU cpio 2.15: `find . | sort | cpio -o -H bin` over a directory, a file of odd size, a
    // symbolic link and a 300-byte file in a subdirectory; padded to a 512-byte block.
    private val gnuBinary = Base64.decode(
        "x3EAAAAA7UEAAAAAAgAAAFNlAPECAAAAAAAuAMdxAAABAKSBAAAAAAEAAABTZQDxBgAAAAUAYS50eHQAb2RkLgoAx3EAAAIA/6EA" +
        "AAAAAQAAAP9qwC8FAAAABQBsaW5rAABhLnR4dADHcQAAAwDtQQAAAAACAAAAU2UA8QQAAAAAAHN1YgDHcQAABACkgQAAAAABAAAA" +
        "U2UA8QoAAAAsAXN1Yi9iLmJpbgB4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4" +
        "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4" +
        "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4" +
        "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4" +
        "eHh4eHh4eHh4eHh4eHh4eHh4eHjHcQAAAAAAAAAAAAABAAAAAAAAAAsAAAAAAFRSQUlMRVIhISEAAAAAAAA="
    )

    private fun listRandom(archive: ByteArray): String = try {
        CpioReader(ByteArrayRandomAccessSource(archive)).use { reader ->
            reader.entries().joinToString("|") { e -> "${e.name} ${e.unixMode} ${e.lastModifiedEpochSeconds} ${e.isDirectory} " + reader.read(e).decodeToString() }
        }
    } catch (e: ArchiveException) { e::class.simpleName!! }

    private fun listStreamed(archive: ByteArray, step: Int, readEvery: Boolean = true): String = try {
        val reader = CpioStreamReader(StreamParity.trickle(archive, step))
        val out = ArrayList<String>()
        while (true) {
            val e = reader.next() ?: break
            val data = ByteArrayBuilder(64)
            if (readEvery) reader.read({ b, o, n -> data.append(b, o, n) })
            out += "${e.name} ${e.unixMode} ${e.lastModifiedEpochSeconds} ${e.isDirectory} " + data.takeByteArray().decodeToString()
        }
        assertNull(reader.next())
        out.joinToString("|")
    } catch (e: ArchiveException) { e::class.simpleName!! }

    private fun assertSame(archive: ByteArray, what: String) {
        val expected = listRandom(archive)
        for (step in listOf(1, 77, 4096)) assertEquals(expected, listStreamed(archive, step), "$what, step $step")
    }

    private fun assertBothFailOrSame(archive: ByteArray, what: String) {
        val expected = listRandom(archive)
        for (step in listOf(1, 4096)) {
            val actual = listStreamed(archive, step)
            if (expected.endsWith("Exception")) assertEquals(true, actual.endsWith("Exception"), "$what, step $step: $actual")
            else assertEquals(expected, actual, "$what, step $step")
        }
    }

    private val specs = listOf(
        CpioWriter.FileSpec("dir", ByteArray(0)) { mode = 0x41ED; modificationTime = 1_700_000_000 },
        CpioWriter.FileSpec("dir/odd.txt", "odd".encodeToByteArray()),
        CpioWriter.FileSpec("dir/link", "odd.txt".encodeToByteArray()) { mode = 0xA1FF },
        CpioWriter.FileSpec("dir/big.bin", StreamParity.text(5001, 4)) { uid = 1000; gid = 1000 },
        CpioWriter.FileSpec("empty", ByteArray(0)),
    )

    @Test
    fun theStreamReaderListsWhatCpioReaderLists() {
        assertEquals(". a.txt link sub sub/b.bin", CpioReader(ByteArrayRandomAccessSource(gnuBinary)).use { r -> r.entries().joinToString(" ") { it.name } })
        assertSame(gnuBinary, "GNU binary")
        for (dialect in CpioWriter.Dialect.entries) {
            val archive = CpioWriter.write(specs, dialect)
            assertSame(archive, "$dialect")
            // Records skipped unread still pass their crc sums.
            assertEquals(specs.joinToString("|") { "${it.name} " }, listStreamed(archive, 4096, readEvery = false)
                .split("|").joinToString("|") { it.substringBefore(' ') + " " }, "$dialect skipped")
        }
    }

    @Test
    fun theStreamWriterWritesWhatCpioWriterWrites() {
        for (dialect in listOf(CpioWriter.Dialect.NEWC, CpioWriter.Dialect.ODC)) {
            val out = ByteArrayBuilder(1024)
            val writer = CpioStreamWriter({ b, o, n -> out.append(b, o, n) }, dialect)
            writer.putDirectory("dir", modificationTime = 1_700_000_000)
            writer.putFile("dir/odd.txt", 3, ByteSource.of("odd".encodeToByteArray()))
            writer.putSymbolicLink("dir/link", "odd.txt")
            val big = StreamParity.text(5001, 4)
            writer.putFile("dir/big.bin", big.size.toLong(), StreamParity.trickle(big, 100), uid = 1000, gid = 1000)
            writer.putFile("empty", 0, ByteSource.of(ByteArray(0)))
            writer.finish()
            writer.finish()
            assertContentEquals(CpioWriter.write(specs, dialect), out.takeByteArray(), "$dialect")
        }
        val empty = ByteArrayBuilder(128)
        CpioStreamWriter({ b, o, n -> empty.append(b, o, n) }).finish()
        assertContentEquals(CpioWriter.write(emptyList()), empty.takeByteArray())
        assertFailsWith<IllegalArgumentException> { CpioStreamWriter({ _, _, _ -> }, CpioWriter.Dialect.CRC) }
        assertFailsWith<ArchiveFormatException> { CpioStreamWriter({ _, _, _ -> }).putFile("x", 3, ByteSource.of(ByteArray(2))) }
        assertFailsWith<ArchiveFormatException> { CpioStreamWriter({ _, _, _ -> }).putFile("TRAILER!!!", 0, ByteSource.of(ByteArray(0))) }
    }

    @Test
    fun truncationsCorruptionsAndUnsafeNamesFail() {
        for (dialect in CpioWriter.Dialect.entries) {
            val archive = CpioWriter.write(specs.take(3), dialect)
            for (cut in archive.indices) assertBothFailOrSame(archive.copyOf(cut), "$dialect cut at $cut")
            for (at in archive.indices) assertBothFailOrSame(archive.copyOf().also { it[at] = (it[at].toInt() xor 0x01).toByte() }, "$dialect byte $at")
        }
        // A crc body's sum is checked even when the body is skipped unread.
        val crc = CpioWriter.write(specs, CpioWriter.Dialect.CRC)
        // The first "odd" is in the name dir/odd.txt; the second is that file's body.
        val text = crc.decodeToString()
        val at = text.indexOf("odd", text.indexOf("odd") + 1)
        val bad = crc.copyOf().also { it[at] = 'O'.code.toByte() }
        assertEquals("ArchiveFormatException", listStreamed(bad, 4096, readEvery = false))
        val unsafe = CpioWriter.write(listOf(CpioWriter.FileSpec("../x", ByteArray(0))))
        assertFailsWith<ArchivePathException> { CpioStreamReader(ByteSource.of(unsafe)).next() }
        assertFailsWith<ArchiveFormatException> { CpioStreamReader(ByteSource.of(ByteArray(0))).next() }
    }
}
