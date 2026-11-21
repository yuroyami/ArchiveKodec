package io.github.yuroyami.archivekodec.security

import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.ar.ArStreamReader
import io.github.yuroyami.archivekodec.archive.ar.ArWriter
import io.github.yuroyami.archivekodec.archive.cpio.CpioStreamReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioWriter
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.tar.TarStreamReader
import io.github.yuroyami.archivekodec.archive.tar.TarWriter
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.ByteSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A repeated name: the last one wins a lookup, and rejectDuplicateNames refuses the listing. */
class DuplicateNameTest {
    private val strict = ArchiveLimits { rejectDuplicateNames = true }
    private val one = "one".encodeToByteArray()
    private val two = "two".encodeToByteArray()
    private val tar = TarWriter.write(listOf(TarWriter.FileSpec("notes", one), TarWriter.FileSpec("notes", two)))

    @Test
    fun aLookupGivesTheLastEntryAndStrictListingsRefuseTheRepeat() {
        TarReader(ByteArrayRandomAccessSource(tar)).use { reader ->
            assertEquals(2, reader.entries().size)
            assertContentEquals(two, reader.read(reader.entry("notes")!!))
        }
        val error = assertFailsWith<ArchivePathException> { TarReader(ByteArrayRandomAccessSource(tar), strict).use { it.entries() } }
        assertEquals("notes", error.entryName)
        val zip = ZipWriter.write(listOf(ZipWriter.FileSpec("notes", one), ZipWriter.FileSpec("notes", two)))
        ZipReader(ByteArrayRandomAccessSource(zip)).use { assertContentEquals(two, it.read(it.entry("notes")!!)) }
        assertFailsWith<ArchivePathException> { ZipReader(ByteArrayRandomAccessSource(zip), strict).use { it.entries() } }
    }

    @Test
    fun onlyRepeatedDirectoriesAreAllowed() {
        val dirs = TarWriter.write(listOf(
            TarWriter.FileSpec("d/", ByteArray(0)) { isDirectory = true },
            TarWriter.FileSpec("d/", ByteArray(0)) { isDirectory = true },
            TarWriter.FileSpec("d/a", one),
        ))
        assertEquals(3, TarReader(ByteArrayRandomAccessSource(dirs), strict).use { it.entries().size })
        // In cpio a directory's name has no trailing slash, so a file can share it.
        val mixed = CpioWriter.write(listOf(
            CpioWriter.FileSpec("x", ByteArray(0)) { mode = 0x41ED },
            CpioWriter.FileSpec("x", one),
        ))
        assertFailsWith<ArchivePathException> { CpioStreamReader(ByteSource.of(mixed), strict).let { r -> while (r.next() != null) Unit } }
        assertEquals(0x41ED, CpioStreamReader(ByteSource.of(mixed)).next()!!.unixMode)
    }

    @Test
    fun streamReadersRefuseTheRepeatWhenTheyReachIt() {
        val tarStream = TarStreamReader(ByteSource.of(tar), strict)
        assertEquals("notes", tarStream.next()!!.name)
        assertFailsWith<ArchivePathException> { tarStream.next() }
        val ar = ArWriter.write(listOf(ArWriter.FileSpec("notes", one), ArWriter.FileSpec("notes", two)))
        val arStream = ArStreamReader(ByteSource.of(ar), strict)
        arStream.next()
        assertFailsWith<ArchivePathException> { arStream.next() }
        val relaxed = ArStreamReader(ByteSource.of(ar))
        assertEquals(listOf("notes", "notes"), generateSequence { relaxed.next() }.map { it.name }.toList())
    }

    @Test
    fun thePolicyIsOffByDefaultAndPartOfEquality() {
        assertEquals(false, ArchiveLimits.DEFAULT.rejectDuplicateNames)
        assertEquals(false, ArchiveLimits.UNBOUNDED.rejectDuplicateNames)
        assertEquals(strict, ArchiveLimits.DEFAULT.copy { rejectDuplicateNames = true })
        assertEquals(true, strict != ArchiveLimits.DEFAULT && strict.hashCode() != ArchiveLimits.DEFAULT.hashCode())
        assertEquals(true, strict.copy().rejectDuplicateNames)
        assertEquals(true, strict.toString().contains("rejectDuplicateNames=true"))
    }
}
