package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.ar.ArWriter
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioWriter
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zipForeignStoredName
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Default listings refuse names that would escape an extraction directory (#2). */
class UnsafeNameListingTest {
    private val data = "x".encodeToByteArray()

    /** Minimal ustar member, so TAR names can be anything the writer would refuse. */
    private fun tarMember(name: String, type: Char = '0', payload: ByteArray = byteArrayOf()): ByteArray {
        val header = ByteArray(512)
        fun put(offset: Int, text: String) = text.encodeToByteArray().copyInto(header, offset)
        put(0, name); put(100, "0000644"); put(124, payload.size.toString(8).padStart(11, '0'))
        put(136, "00000000000"); header[156] = type.code.toByte(); put(257, "ustar"); put(263, "00")
        put(148, "        ")
        put(148, header.sumOf { it.toInt() and 0xFF }.toString(8).padStart(6, '0') + "\u0000 ")
        return header + payload + ByteArray((512 - payload.size % 512) % 512)
    }

    @Test
    fun zipTraversalNameFailsTheDefaultListing() {
        val zip = zipForeignStoredName("../../evil.txt", data)
        ArchiveKodec.open(ByteArrayRandomAccessSource(zip)).use { reader ->
            val error = assertFailsWith<ArchivePathException> { reader.entries() }
            assertEquals("../../evil.txt", error.entryName)
        }
        ZipReader(ByteArrayRandomAccessSource(zip), ArchiveLimits { rejectUnsafeNames = false }).use { reader ->
            val entry = reader.entries().single()
            assertEquals("../../evil.txt", entry.name)
            assertContentEquals(data, reader.read(entry))
        }
        ArchiveKodec.open(ByteArrayRandomAccessSource(zip), ArchiveLimits.UNBOUNDED).use { reader ->
            assertEquals("../../evil.txt", reader.entries().single().name)
        }
    }

    @Test
    fun everyReaderRejectsEachUnsafeKind() {
        for (name in listOf("../up.txt", "a/../../up.txt", "/etc/passwd", "C:/x", "..\\up.txt", "a\u0001b")) {
            val archives = listOfNotNull(
                ZipReader(ByteArrayRandomAccessSource(zipForeignStoredName(name, data))),
                TarReader(ByteArrayRandomAccessSource(tarMember(name, payload = data))),
                // The cpio writer refuses only names it cannot frame, so most unsafe names reach the reader.
                runCatching { CpioWriter.write(listOf(CpioWriter.FileSpec(name, data))) }.getOrNull()
                    ?.let { CpioReader(ByteArrayRandomAccessSource(it)) },
            )
            for (reader in archives) reader.use {
                assertEquals(name, assertFailsWith<ArchivePathException>("$name in ${it.format}") { it.entries() }.entryName)
            }
        }
    }

    @Test
    fun arAbsoluteNamesAreRejected() {
        val ar = "!<arch>\n".encodeToByteArray() +
            ("/abs".padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "644".padEnd(8) + "1".padEnd(10) + "`\n")
                .encodeToByteArray() + data + byteArrayOf(10)
        ArReader(ByteArrayRandomAccessSource(ar)).use { reader ->
            assertEquals("/abs", assertFailsWith<ArchivePathException> { reader.entries() }.entryName)
        }
        ArReader(ByteArrayRandomAccessSource(ArWriter.write(listOf(ArWriter.FileSpec("ok.o", data))))).use { reader ->
            assertEquals("ok.o", reader.entries().single().name)
        }
    }

    @Test
    fun rootDirectoryEntriesStayListable() {
        val tar = tarMember("./", '5') + tarMember("./a.txt", payload = data) + ByteArray(1024)
        TarReader(ByteArrayRandomAccessSource(tar)).use { reader ->
            val entries = reader.entries()
            assertEquals(listOf("./", "./a.txt"), entries.map { it.name })
            assertTrue(entries[0].isDirectory)
            assertContentEquals(data, reader.read(entries[1]))
        }
        // A root-named regular file is not a directory, so it is refused.
        TarReader(ByteArrayRandomAccessSource(tarMember(".", payload = data))).use { reader ->
            assertFailsWith<ArchivePathException> { reader.entries() }
        }
        val cpio = CpioWriter.write(listOf(
            CpioWriter.FileSpec(".", byteArrayOf()) { mode = 0x41ed },
            CpioWriter.FileSpec("bin/sh", data),
        ))
        CpioReader(ByteArrayRandomAccessSource(cpio)).use { reader ->
            assertEquals(listOf(".", "bin/sh"), reader.entries().map { it.name })
        }
    }

    @Test
    fun gzipWrappedArchivesUseTheSamePolicy() {
        val wrapped = Gzip.compress(tarMember("../evil", payload = data) + ByteArray(1024))
        ArchiveKodec.open(wrapped).use { reader ->
            assertFailsWith<ArchivePathException> { reader.entries() }
        }
        ArchiveKodec.open(wrapped, ArchiveLimits { rejectUnsafeNames = false }).use { reader ->
            assertEquals("../evil", reader.entries().single().name)
        }
    }

    @Test
    fun policyValueSemanticsIncludeTheNameSetting() {
        assertTrue(ArchiveLimits.DEFAULT.rejectUnsafeNames)
        assertFalse(ArchiveLimits.UNBOUNDED.rejectUnsafeNames)
        val relaxed = ArchiveLimits { rejectUnsafeNames = false }
        assertNotEquals(ArchiveLimits.DEFAULT, relaxed)
        assertEquals(relaxed, ArchiveLimits.DEFAULT.copy { rejectUnsafeNames = false })
        assertEquals(relaxed.hashCode(), ArchiveLimits.DEFAULT.copy { rejectUnsafeNames = false }.hashCode())
        assertTrue(relaxed.toString().contains("rejectUnsafeNames=false"))
        assertFalse(relaxed.copy().rejectUnsafeNames)
    }
}
