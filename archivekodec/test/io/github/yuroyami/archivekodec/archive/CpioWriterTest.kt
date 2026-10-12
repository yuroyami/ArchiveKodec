package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveSizeException
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.ArchivePathException

/** Lists names as stored, so tests can check raw-name preservation for PathGuard. */
private val RAW_NAMES = ArchiveLimits { rejectUnsafeNames = false }

class CpioWriterTest {
    private val dialects = CpioWriter.Dialect.entries

    private fun read(bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): Pair<List<ArchiveEntry>, List<ByteArray>> {
        val reader = CpioReader(ByteArrayRandomAccessSource(bytes), limits)
        try {
            val entries = reader.entries()
            return entries to entries.map { reader.read(it) }
        } finally {
            reader.close()
        }
    }

    private fun field(bytes: ByteArray, header: Int, index: Int): Long =
        bytes.decodeToString(header + 6 + index * 8, header + 14 + index * 8).toLong(16)

    @Test
    fun emptyArchivesContainOnlyThePaddedTrailer() {
        for (dialect in dialects) {
            val bytes = CpioWriter.write(emptyList(), dialect)
            assertEquals(124, bytes.size)
            assertEquals(if (dialect == CpioWriter.Dialect.NEWC) "070701" else "070702", bytes.decodeToString(0, 6))
            assertEquals(0L, field(bytes, 0, 6))
            assertEquals(11L, field(bytes, 0, 11))
            assertEquals(0L, field(bytes, 0, 12))
            assertEquals("TRAILER!!!", bytes.decodeToString(110, 120))
            assertContentEquals(ByteArray(4), bytes.copyOfRange(120, 124))
            assertTrue(read(bytes).first.isEmpty())
        }
        assertContentEquals(CpioWriter.write(emptyList(), CpioWriter.Dialect.NEWC), CpioWriter.write(emptyList()))
    }

    @Test
    fun headersKeepAllThirteenFieldsInOrderWithoutSignedTruncation() {
        val file = CpioWriter.FileSpec("x", byteArrayOf(0, -1, -128, 1)) {
            inode = 0xffffffffL
            mode = 0x8fff
            uid = 0xffffffffL
            gid = 0x89abcdefL
            linkCount = 0xffffffffL
            modificationTime = 0xffffffffL
            deviceMajor = 0x12345678
            deviceMinor = 0x87654321L
            rdevMajor = 0xffffffffL
            rdevMinor = 0xfedcba98L
        }
        for (dialect in dialects) {
            val bytes = CpioWriter.write(listOf(file), dialect)
            val expected = listOf(0xffffffffL, 0x8fffL, 0xffffffffL, 0x89abcdefL, 0xffffffffL,
                0xffffffffL, 4L, 0x12345678L, 0x87654321L, 0xffffffffL, 0xfedcba98L, 2L,
                if (dialect == CpioWriter.Dialect.CRC) 384L else 0L)
            assertEquals(expected, (0..12).map { field(bytes, 0, it) })
            assertEquals("ffffffff00008fff", bytes.decodeToString(6, 22))
            assertContentEquals(file.data, read(bytes).second.single())
        }
    }

    @Test
    fun everyNameAndBodyRemainderUsesZeroPaddingAtFourByteBoundaries() {
        val files = (1..8).flatMap { nameLength -> (0..7).map { bodyLength ->
            CpioWriter.FileSpec("雪" + "n".repeat(nameLength), ByteArray(bodyLength) { (it + 1).toByte() })
        } }
        for (dialect in dialects) {
            val bytes = CpioWriter.write(files, dialect)
            var at = 0
            for (file in files) {
                assertEquals(0, at % 4)
                val name = file.name.encodeToByteArray()
                assertEquals(name.size + 1L, field(bytes, at, 11))
                assertContentEquals(name, bytes.copyOfRange(at + 110, at + 110 + name.size))
                var dataAt = at + 110 + name.size
                assertEquals(0, bytes[dataAt++].toInt())
                while (dataAt % 4 != 0) assertEquals(0, bytes[dataAt++].toInt())
                assertContentEquals(file.data, bytes.copyOfRange(dataAt, dataAt + file.data.size))
                at = dataAt + file.data.size
                while (at % 4 != 0) assertEquals(0, bytes[at++].toInt())
            }
            assertEquals("TRAILER!!!", bytes.decodeToString(at + 110, at + 120))
            assertEquals(at + 124, bytes.size)
            val (entries, bodies) = read(bytes)
            assertEquals(files.map { it.name }, entries.map { it.name })
            files.forEachIndexed { index, file -> assertContentEquals(file.data, bodies[index]) }
        }
    }

    @Test
    fun crcChecksumsCoverUnsignedDataAndDetectCorruption() {
        val bytes = CpioWriter.write(listOf(CpioWriter.FileSpec("data", byteArrayOf(-1, -128, 0, 1))), CpioWriter.Dialect.CRC)
        val dataAt = read(bytes).first.single().dataOffset.toInt()
        assertEquals(384L, field(bytes, 0, 12))
        bytes[dataAt] = 0
        val failure = assertFailsWith<ArchiveFormatException> { read(bytes) }
        assertEquals("data", failure.entryName)
        assertTrue(failure.message!!.contains("checksum"))
    }

    @Test
    fun directoriesSymlinksAndHardLinksKeepTheirTypesAndBodies() {
        val payload = "linked data".encodeToByteArray()
        val files = listOf(
            CpioWriter.FileSpec("dir", byteArrayOf()) { mode = 0x41ed; inode = 42; linkCount = 2 },
            CpioWriter.FileSpec("dir/alias", byteArrayOf()) { inode = 42; linkCount = 2 },
            CpioWriter.FileSpec("dir/file", payload) { inode = 42; linkCount = 2 },
            CpioWriter.FileSpec("dir/symlink", "file".encodeToByteArray()) { mode = 0xa1ff },
        )
        for (dialect in dialects) {
            val (entries, bodies) = read(CpioWriter.write(files, dialect))
            assertEquals(listOf(true, false, false, false), entries.map { it.isDirectory })
            assertContentEquals(byteArrayOf(), bodies[0])
            assertContentEquals(payload, bodies[1])
            assertContentEquals(payload, bodies[2])
            assertContentEquals("file".encodeToByteArray(), bodies[3])
        }
    }

    @Test
    fun hardLinkGroupsRejectConflictingTypesButSeparateDeviceNumbers() {
        val regular = CpioWriter.FileSpec("file", byteArrayOf(65)) { linkCount = 2 }
        val symlink = CpioWriter.FileSpec("link", byteArrayOf(65)) { linkCount = 2; mode = 0xa1ff }
        assertFailsWith<ArchiveFormatException> { CpioWriter.write(listOf(regular, symlink)) }
        val separate = symlink.copy { deviceMinor = 1 }
        assertEquals(2, read(CpioWriter.write(listOf(regular, separate))).first.size)
    }

    @Test
    fun unsignedMetadataAndFileTypeConstraintsAreValidatedAtBuildTime() {
        val settings: List<CpioWriter.FileSpec.Builder.(Long) -> Unit> = listOf(
            { inode = it }, { uid = it }, { gid = it }, { linkCount = it },
            { modificationTime = it }, { deviceMajor = it }, { deviceMinor = it },
            { rdevMajor = it }, { rdevMinor = it },
        )
        for (setting in settings) {
            for (valid in listOf(0L, 0xffffffffL)) CpioWriter.FileSpec.Builder("x", byteArrayOf()).apply { setting(valid) }.build()
            for (bad in listOf(-1L, 0x100000000L, Long.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> {
                    CpioWriter.FileSpec.Builder("x", byteArrayOf()).apply { setting(bad) }.build()
                }
            }
        }
        for (bad in listOf(-1, 0, 0xf000, 0x10000)) {
            assertFailsWith<IllegalArgumentException> { CpioWriter.FileSpec("x", byteArrayOf()) { mode = bad } }
        }
        for (type in listOf(0x1000, 0x2000, 0x4000, 0x6000, 0xc000)) {
            val empty = CpioWriter.FileSpec("special", byteArrayOf()) { mode = type or 0x1a4 }
            assertEquals(type or 0x1a4, empty.mode)
            assertFailsWith<IllegalArgumentException> { empty.copy { data = byteArrayOf(1) } }
        }
        for (target in listOf(byteArrayOf(), byteArrayOf(65, 0, 66))) {
            assertFailsWith<IllegalArgumentException> { CpioWriter.FileSpec("link", target) { mode = 0xa1ff } }
        }
    }

    @Test
    fun namesAreValidatedWithoutRewritingTheirSpellingOrRemovingDuplicates() {
        for (name in listOf("", "TRAILER!!!", "a\u0000b", "\uD800", "\uDC00")) {
            val failure = assertFailsWith<ArchiveFormatException> {
                CpioWriter.write(listOf(CpioWriter.FileSpec(name, byteArrayOf())))
            }
            assertEquals(name, failure.entryName)
        }
        val names = listOf(".", "./a", "a/b", "雪", "same", "same")
        val files = names.map { CpioWriter.FileSpec(it, byteArrayOf()) }
        assertEquals(names, read(CpioWriter.write(files), RAW_NAMES).first.map { it.name })
        // A regular file named "." names no path, so the default policy refuses the listing.
        assertEquals(".", assertFailsWith<ArchivePathException> { read(CpioWriter.write(files)) }.entryName)
    }

    @Test
    fun descriptionsAndCopiesOwnTheirDataAndAllMetadata() {
        val input = byteArrayOf(1, 2, 3)
        val builder = CpioWriter.FileSpec.Builder("data", input).apply {
            inode = 11; uid = 12; gid = 13; linkCount = 14; modificationTime = 15
            deviceMajor = 16; deviceMinor = 17; rdevMajor = 18; rdevMinor = 19
        }
        val file = builder.build()
        input.fill(9)
        builder.inode = 999
        file.data.fill(8)
        val copy = file.copy { data[0] = 7; mode = 0x81ed }
        assertContentEquals(byteArrayOf(1, 2, 3), file.data)
        assertContentEquals(byteArrayOf(7, 2, 3), copy.data)
        assertEquals(file, file.copy())
        assertEquals(file.hashCode(), file.copy().hashCode())
        assertNotEquals(file, copy)
        assertTrue(CpioWriter.FileSpec("x".repeat(1000), input).toString().length < 160)
    }

    @Test
    fun anImpossibleHeaderCountFailsBeforeTraversingTheList() {
        val files = object : AbstractList<CpioWriter.FileSpec>() {
            override val size: Int = 50_000_000
            override fun get(index: Int): CpioWriter.FileSpec = error("must reject before reading members")
        }
        assertFailsWith<ArchiveSizeException> { CpioWriter.write(files) }
    }
}
