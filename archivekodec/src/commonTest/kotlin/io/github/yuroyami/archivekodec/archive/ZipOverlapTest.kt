package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipOverlapTest {
    private fun overlap(bytes: ByteArray, boundary: Long) {
        val error = assertFailsWith<ArchiveFormatException> { ZipReader(ByteArrayRandomAccessSource(bytes)).entries() }
        assertEquals("a", error.entryName)
        assertEquals(boundary, error.offset)
        assertTrue(error.message!!.contains("overlap"), error.toString())
    }

    @Test
    fun storedPayloadContainingAnotherCompleteMemberIsRejected() {
        overlap(zipOverlappingPair(ZipMethod.STORED), 31)
    }

    @Test
    fun originalReportedStoreFixtureIsRejected() {
        overlap(zipOverlapIssueFixture(), 31)
    }

    @Test
    fun quotedDeflateHeaderAndSharedStreamAreRejected() {
        overlap(zipOverlappingPair(ZipMethod.DEFLATED), 36)
    }

    @Test
    fun emptyOuterEntryCannotHideAnotherMemberInItsExtraFields() {
        for (method in listOf(ZipMethod.STORED, ZipMethod.DEFLATED)) {
            overlap(zipOverlappingPair(method, inExtra = true), 35)
        }
    }

    @Test
    fun physicalBoundariesAreCheckedEvenWithReversedDirectoryOrder() {
        overlap(zipOverlappingPair(ZipMethod.STORED, reversed = true), 31)
        overlap(zipOverlappingPair(ZipMethod.DEFLATED, reversed = true), 36)
        overlap(zipOverlappingPair(ZipMethod.STORED, inExtra = true, reversed = true), 35)
    }

    @Test
    fun readingBeforeListingCannotBypassTheGlobalExtentCheck() {
        val bytes = zipOverlappingPair(ZipMethod.STORED)
        val requests = mutableListOf<Pair<Long, Int>>()
        val source = object : RandomAccessSource {
            private val backing = ByteArrayRandomAccessSource(bytes)
            override val size get() = backing.size
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                requests.add(offset to length)
                return backing.read(offset, into, intoOffset, length)
            }
        }
        val reader = ZipReader(source)
        val innerBytes = bytes.copyOfRange(31, 76)
        val entries = listOf(
            ArchiveEntry("a", 45, false) { crc32 = Crc32.of(innerBytes) },
            ArchiveEntry("b", 14, false) { crc32 = Crc32.of("SHARED-PAYLOAD".encodeToByteArray()) },
        )
        for (entry in entries) {
            val error = assertFailsWith<ArchiveFormatException> { reader.read(entry, ArchiveLimits.UNBOUNDED) }
            assertEquals("a", error.entryName)
            assertEquals(31L, error.offset)
        }
        // Tail scanning may include payload bytes; neither member's payload read may run.
        assertTrue(31L to 45 !in requests)
        assertTrue(62L to 14 !in requests)
    }

    @Test
    fun payloadCannotConsumeTheFirstDirectoryByteEvenWithAValidCrc() {
        val piece = zipExtentPiece("a", "ABC".encodeToByteArray())
        for ((bytes, field) in listOf(piece.local to 18, piece.local to 22, piece.directory to 20, piece.directory to 24)) {
            putLe(bytes, field, 4, 4)
        }
        val crc = Crc32.of("ABCP".encodeToByteArray())
        putLe(piece.local, 14, crc, 4); putLe(piece.directory, 16, crc, 4)
        overlap(zipExtentArchive(piece.local, listOf(piece.directory)), piece.local.size.toLong())
    }

    @Test
    fun emptyEntryExtraFieldsCannotConsumeDirectoryAndEndRecords() {
        val piece = zipExtentPiece("a", byteArrayOf())
        // Directory signature 50 4b 01 02 is also a syntactically complete
        // unknown extra-field header with ID 0x4b50 and 513 data bytes.
        putLe(piece.local, 28, 517, 2)
        val bytes = zipWithComment(zipExtentArchive(piece.local, listOf(piece.directory)), ByteArray(600))
        overlap(bytes, piece.local.size.toLong())
    }

    @Test
    fun localHeaderInTheArchiveCommentIsRejected() {
        val piece = zipExtentPiece("a", "ABC".encodeToByteArray())
        val base = zipExtentArchive(piece.local, listOf(piece.directory))
        putLe(base, piece.local.size + 42, base.size.toLong(), 4)
        overlap(zipWithComment(base, piece.local), piece.local.size.toLong())
    }

    @Test
    fun exactAdjacencyAndZeroSizeMembersRemainReadable() {
        for (method in listOf(ZipMethod.STORED, ZipMethod.DEFLATED)) {
            val files = listOf("", "a", "é/文件😀", "dir/").map { ZipWriter.FileSpec(it, byteArrayOf(), method) }
            val reader = ZipReader(ByteArrayRandomAccessSource(ZipWriter.write(files)))
            assertEquals(files.map { it.name }, reader.entries().map { it.name })
            for (entry in reader.entries()) assertContentEquals(byteArrayOf(), reader.read(entry))
        }
    }

    @Test
    fun paddingAndDirectoryOrderDoNotRequirePhysicalOrder() {
        val first = zipExtentPiece("a", "ABC".encodeToByteArray())
        val second = zipExtentPiece("b", "DEF".encodeToByteArray(), ZipMethod.DEFLATED)
        val prefix = byteArrayOf(1, 2, 3)
        val padding = ByteArray(20) { 7 }
        putLe(first.directory, 42, prefix.size.toLong(), 4)
        putLe(second.directory, 42, (prefix.size + first.local.size + padding.size).toLong(), 4)
        val archive = zipExtentArchive(prefix + first.local + padding + second.local + padding, listOf(second.directory, first.directory))
        val reader = ZipReader(ByteArrayRandomAccessSource(archive))
        assertEquals(listOf("b", "a"), reader.entries().map { it.name })
        for (entry in reader.entries()) {
            assertContentEquals(if (entry.name == "a") "ABC".encodeToByteArray() else "DEF".encodeToByteArray(), reader.read(entry))
        }
    }

    @Test
    fun maximumLocalNameAndExtraLengthsRemainRepresentable() {
        val name = "a".repeat(65535)
        val extra = ByteArray(65535)
        putLe(extra, 0, 0xCAFE, 2); putLe(extra, 2, 65531, 2)
        val first = zipExtentPiece(name, byteArrayOf(65), localExtra = extra)
        val second = zipExtentPiece("b", byteArrayOf(66))
        putLe(second.directory, 42, first.local.size.toLong(), 4)
        val archive = zipExtentArchive(first.local + second.local, listOf(first.directory, second.directory))
        val reader = ZipReader(ByteArrayRandomAccessSource(archive))
        assertEquals(name, reader.entries()[0].name)
        assertContentEquals(byteArrayOf(65), reader.read(reader.entries()[0]))
        assertContentEquals(byteArrayOf(66), reader.read(reader.entries()[1]))
    }

    @Test
    fun zip64ExtentAtLongMaximumBoundaryDoesNotNarrow() {
        val (source, boundary) = sparseNearLongMaximum(overlapping = false)
        val entry = ZipReader(source).entries().single()
        assertEquals("a.txt", entry.name)
        assertEquals(boundary - 35, entry.compressedSize)
    }

    @Test
    fun zip64ExtentCannotConsumeDirectoryByteNearLongMaximum() {
        val (source, boundary) = sparseNearLongMaximum(overlapping = true)
        val error = assertFailsWith<ArchiveFormatException> { ZipReader(source).entries() }
        assertEquals("a.txt", error.entryName)
        assertEquals(boundary, error.offset)
        assertTrue(error.message!!.contains("overlap"))
    }

    @Test
    fun physicalSortingKeepsExactOffsetsNearLongMaximum() {
        val compact = zipEntryMetadata(4)
        val central = readU32Le(compact, compact.size - 6).toInt()
        val firstRecord = compact.copyOfRange(central, compact.size - 22)
        val secondRecord = firstRecord.copyOf().also { it[46] = 'b'.code.toByte() }
        val firstLocal = compact.copyOf(38)
        val secondLocal = firstLocal.copyOf().also { it[30] = 'b'.code.toByte() }
        val directoryOffset = Long.MAX_VALUE - firstRecord.size - secondRecord.size - 98
        val firstOffset = directoryOffset - 76
        val secondOffset = directoryOffset - 38
        putLe(firstRecord, 55, firstOffset, 8); putLe(secondRecord, 55, secondOffset, 8)
        val records = secondRecord + firstRecord
        val z64 = ByteArray(56)
        putLe(z64, 0, ZipFormat.Z64_EOCD, 4); putLe(z64, 4, 44, 8)
        putLe(z64, 12, 45, 2); putLe(z64, 14, 45, 2)
        putLe(z64, 24, 2, 8); putLe(z64, 32, 2, 8)
        putLe(z64, 40, records.size.toLong(), 8); putLe(z64, 48, directoryOffset, 8)
        val locator = ByteArray(20)
        putLe(locator, 0, ZipFormat.Z64_LOC, 4)
        putLe(locator, 8, directoryOffset + records.size, 8); putLe(locator, 16, 1, 4)
        val end = ByteArray(22)
        putLe(end, 0, ZipFormat.EOCD, 4)
        putLe(end, 8, 65535, 2); putLe(end, 10, 65535, 2)
        putLe(end, 12, 0xFFFFFFFF, 4); putLe(end, 16, 0xFFFFFFFF, 4)
        val source = sparseSource(listOf(firstOffset to firstLocal, secondOffset to secondLocal, directoryOffset to (records + z64 + locator + end)))
        val reader = ZipReader(source)
        val entries = reader.entries()
        assertEquals(listOf("b.txt", "a.txt"), entries.map { it.name })
        assertEquals(listOf(secondOffset, firstOffset), entries.map { it.dataOffset })
        for (entry in entries) assertContentEquals("ABC".encodeToByteArray(), reader.read(entry))
    }

    // Metadata-only sparse source; no enormous payload is materialized or decoded.
    private fun sparseNearLongMaximum(overlapping: Boolean): Pair<RandomAccessSource, Long> {
        val compact = zipEntryMetadata(2)
        val central = readU32Le(compact, compact.size - 6).toInt()
        val records = compact.copyOfRange(central, compact.size - 22)
        val boundary = Long.MAX_VALUE - records.size - 98
        putLe(records, 55, boundary - 35 + if (overlapping) 1 else 0, 8)
        val z64 = ByteArray(56)
        putLe(z64, 0, ZipFormat.Z64_EOCD, 4); putLe(z64, 4, 44, 8)
        putLe(z64, 12, 45, 2); putLe(z64, 14, 45, 2)
        putLe(z64, 24, 1, 8); putLe(z64, 32, 1, 8)
        putLe(z64, 40, records.size.toLong(), 8); putLe(z64, 48, boundary, 8)
        val locator = ByteArray(20)
        putLe(locator, 0, ZipFormat.Z64_LOC, 4)
        putLe(locator, 8, boundary + records.size, 8); putLe(locator, 16, 1, 4)
        val end = ByteArray(22)
        putLe(end, 0, ZipFormat.EOCD, 4)
        putLe(end, 8, 65535, 2); putLe(end, 10, 65535, 2)
        putLe(end, 12, 0xFFFFFFFF, 4); putLe(end, 16, 0xFFFFFFFF, 4)
        val regions = listOf(0L to compact.copyOf(35), boundary to (records + z64 + locator + end))
        return sparseSource(regions) to boundary
    }

    private fun sparseSource(regions: List<Pair<Long, ByteArray>>): RandomAccessSource = object : RandomAccessSource {
        override val size = Long.MAX_VALUE
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            check(length <= 65557) { "only bounded reads are allowed" }
            into.fill(0, intoOffset, intoOffset + length)
            for ((start, data) in regions) {
                val from = maxOf(start, offset)
                val to = minOf(start + data.size, offset + length)
                if (from < to) data.copyInto(into, intoOffset + (from - offset).toInt(), (from - start).toInt(), (to - start).toInt())
            }
            return length
        }
    }

    @Test
    fun repeatedOffsetsKeepTheirExistingGuardCategory() {
        val first = zipExtentPiece("a", byteArrayOf(65))
        val second = zipExtentPiece("b", byteArrayOf(66))
        val archive = zipExtentArchive(first.local + second.local, listOf(first.directory, second.directory))
        val error = assertFailsWith<DecompressionBombException> { ZipReader(ByteArrayRandomAccessSource(archive)).entries() }
        assertEquals("b", error.entryName)
        assertEquals(0L, error.offset)
    }
}
