package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.*
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.readI64Le
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.test.*

class ZipPrefixTest {
    private val prefix = "#!/usr/bin/env python3\n".encodeToByteArray()
    private val payload = "ABC".encodeToByteArray()
    private fun central(bytes: ByteArray) = readU32Le(bytes, bytes.size - 6).toInt()
    private fun read(bytes: ByteArray): ByteArray = ZipReader(ByteArrayRandomAccessSource(bytes)).use { r -> r.read(r.entries().single()) }
    private fun assertArchive(r: ArchiveReader, base: Long) {
        assertEquals(ArchiveFormat.ZIP, r.format)
        val e = r.entries().single()
        assertEquals("a.txt", e.name); assertEquals(base, e.dataOffset)
        assertContentEquals(payload, r.read(e)); assertContentEquals(payload, r.read(e.copy()))
    }
    private fun extendedZip64(extension: ByteArray): ByteArray {
        val original = zip64WithComment(0)
        val at = original.size - 98
        val end = original.copyOfRange(at, at + 56)
        putLe(end, 4, 44L + extension.size, 8)
        return original.copyOf(at) + end + extension + original.copyOfRange(at + 56, original.size)
    }
    private fun absolute(bytes: ByteArray, prefixSize: Int, zip64: Boolean): ByteArray {
        val out = bytes.copyOf()
        val directory = if (zip64) readI64Le(out, out.size - 98 + 48).toInt() else central(out)
        putLe(out, directory + 42, prefixSize.toLong(), 4)
        if (zip64) {
            putLe(out, out.size - 98 + 48, directory.toLong() + prefixSize, 8)
            putLe(out, out.size - 42 + 8, (out.size - 98).toLong() + prefixSize, 8)
        } else putLe(out, out.size - 6, directory.toLong() + prefixSize, 4)
        return out
    }

    @Test fun classicLaunchersOpenThroughBothFacadeOverloadsAndDirectReader() {
        val bytes = prefix + zipMetadataBase()
        ZipReader(ByteArrayRandomAccessSource(bytes)).use { assertArchive(it, prefix.size.toLong()) }
        ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { assertArchive(it, prefix.size.toLong()) }
        ArchiveKodec.open(ByteArrayRandomAccessSource(bytes), ArchiveNameDecoder.UTF8).use { assertArchive(it, prefix.size.toLong()) }
    }

    @Test fun zip64LaunchersResolveEndAndLocalHeaderOffsets() {
        val bytes = prefix + zip64WithComment(0)
        ZipReader(ByteArrayRandomAccessSource(bytes)).use { assertArchive(it, prefix.size.toLong()) }
        ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { assertArchive(it, prefix.size.toLong()) }
        ArchiveKodec.open(ByteArrayRandomAccessSource(bytes), ArchiveNameDecoder.UTF8).use { assertArchive(it, prefix.size.toLong()) }
    }

    @Test fun offsetsAlreadyIncludingThePrefixAreNotShiftedAgain() {
        for (zip64 in listOf(false, true)) {
            val bytes = if (zip64) zip64WithComment(0) else zipMetadataBase()
            val full = prefix + absolute(bytes, prefix.size, zip64)
            ZipReader(ByteArrayRandomAccessSource(full)).use { assertArchive(it, prefix.size.toLong()) }
            ArchiveKodec.open(ByteArrayRandomAccessSource(full)).use { assertArchive(it, prefix.size.toLong()) }
        }
    }

    @Test fun allCommentLengthsWorkForPrefixedClassicAndZip64() {
        for (length in listOf(0, 1, 22, 65515, 65516, 65535)) for (base in listOf(zipMetadataBase(), zip64WithComment(0))) {
            val bytes = prefix + zipWithComment(base, ByteArray(length) { 99 })
            ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { assertArchive(it, prefix.size.toLong()) }
        }
    }

    @Test fun zip64ExtensibleSectorsAreNotAssumedToBeFixedSize() {
        for (size in listOf(1, 8191, 8192, 65536, 200007)) {
            val bytes = prefix + extendedZip64(ByteArray(size) { 65 })
            ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { assertArchive(it, prefix.size.toLong()) }
        }
    }

    @Test fun embeddedZip64SignatureWithExactFramingDoesNotHideTheRealRecord() {
        val extension = ByteArray(17000) { 65 }
        val fake = extension.size - 56
        putLe(extension, fake, ZipFormat.Z64_EOCD, 4); putLe(extension, fake + 4, 44, 8)
        putLe(extension, fake + 24, 1, 8); putLe(extension, fake + 32, 1, 8)
        putLe(extension, fake + 40, 51, 8); putLe(extension, fake + 48, 0, 8)
        ArchiveKodec.open(ByteArrayRandomAccessSource(prefix + extendedZip64(extension))).use { assertArchive(it, prefix.size.toLong()) }
    }

    @Test fun zip64SignaturesCrossingSearchChunksRemainReadable() {
        for (size in listOf(8192 - 55, 8192 - 54, 8192 - 1, 8192, 8192 + 1, 16384 - 55)) {
            assertContentEquals(payload, read(prefix + extendedZip64(ByteArray(size))))
        }
    }

    @Test fun prefixDecoyCentralDirectorySignatureDoesNotOverrideEndDerivedLocation() {
        val base = zipMetadataBase()
        val launcher = ByteArray(200) { 65 }
        putLe(launcher, central(base), ZipFormat.CEN, 4)
        assertContentEquals(payload, read(launcher + base))
    }

    @Test fun existingAbsoluteDirectoryGapsRemainReadable() {
        val base = zipMetadataBase()
        val end = base.size - 22
        val gap = ByteArray(37) { 65 }
        assertContentEquals(payload, read(base.copyOf(end) + gap + base.copyOfRange(end, base.size)))
        val absolute = absolute(base, prefix.size, false)
        assertContentEquals(payload, read(prefix + absolute.copyOf(end) + gap + absolute.copyOfRange(end, absolute.size)))
    }

    @Test fun existingAbsoluteZip64DirectoryAndLocatorGapsRemainReadable() {
        val base = zip64WithComment(0)
        val at = base.size - 98
        val gap = ByteArray(29) { 65 }
        val withDirectoryGap = base.copyOf(at) + gap + base.copyOfRange(at, base.size)
        putLe(withDirectoryGap, withDirectoryGap.size - 42 + 8, (at + gap.size).toLong(), 8)
        assertContentEquals(payload, read(withDirectoryGap))
        val locatorAt = base.size - 42
        assertContentEquals(payload, read(base.copyOf(locatorAt) + gap + base.copyOfRange(locatorAt, base.size)))
    }

    @Test fun zip64EntryOffsetsUseTheSamePhysicalDisplacement() {
        val base = zipEntryMetadata(4)
        ArchiveKodec.open(ByteArrayRandomAccessSource(prefix + base)).use { assertArchive(it, prefix.size.toLong()) }
    }

    @Test fun prefixedEmptyClassicAndZip64ArchivesRemainEmpty() {
        val z64 = ByteArray(56)
        putLe(z64, 0, ZipFormat.Z64_EOCD, 4); putLe(z64, 4, 44, 8)
        val locator = ByteArray(20)
        putLe(locator, 0, ZipFormat.Z64_LOC, 4); putLe(locator, 16, 1, 4)
        val end = ZipWriter.write(emptyList())
        for (bytes in listOf(end, z64 + locator + end)) {
            ArchiveKodec.open(ByteArrayRandomAccessSource(prefix + bytes)).use { assertTrue(it.entries().isEmpty()) }
        }
    }

    private class ObservedSource(val bytes: ByteArray, val chunk: Int = Int.MAX_VALUE) : RandomAccessSource {
        val reads = mutableListOf<Pair<Long, Int>>()
        var closes = 0
        var tailFailure: ArchiveIOException? = null
        var tailCount: Int? = null
        override val size: Long get() = bytes.size.toLong()
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            reads.add(offset to length)
            if (offset >= 512) { tailFailure?.let { throw it }; tailCount?.let { return it } }
            return ByteArrayRandomAccessSource(bytes).read(offset, into, intoOffset, minOf(length, chunk))
        }
        override fun close() { closes++ }
    }

    @Test fun shortReadsOpenAndExtractPrefixedArchivesWithoutClosingEarly() {
        for (chunk in listOf(1, 3, 127)) for (base in listOf(zipMetadataBase(), extendedZip64(ByteArray(20000)))) {
            val source = ObservedSource(prefix + base, chunk)
            ArchiveKodec.open(source).use { assertArchive(it, prefix.size.toLong()); assertEquals(0, source.closes) }
            assertEquals(1, source.closes)
        }
    }

    @Test fun headerSniffingRetainsItsBoundedContract() {
        val source = ObservedSource(ByteArray(100000) { 65 } + zipMetadataBase(), 3)
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveKodec.sniff(source))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveKodec.sniff(source.bytes))
        assertTrue(source.reads.all { (offset, length) -> offset + length <= 512 })
        assertEquals(0, source.closes)
    }

    @Test fun recognizedHeaderFormatsKeepPriorityOverZipFooters() {
        val bytes = byteArrayOf(0x1f, 0x8b.toByte(), 8) + zipMetadataBase()
        val malformedGzip = assertFailsWith<ArchiveFormatException> { ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)) }
        assertTrue(malformedGzip.message.orEmpty().contains("gzip", ignoreCase = true))
        val bzip = hex("425a6839177245385090") + zipMetadataBase()
        assertFailsWith<ArchiveUnsupportedException> { ArchiveKodec.open(ByteArrayRandomAccessSource(bzip)) }
        val zeroTar = ByteArray(512) + zipMetadataBase()
        ArchiveKodec.open(ByteArrayRandomAccessSource(zeroTar)).use { assertEquals(ArchiveFormat.TAR, it.format) }
    }

    @Test fun failedFooterReadsPreserveIoFailureAndCallerOwnership() {
        val failure = ArchiveIOException("footer unavailable", IllegalStateException("cause"), 123)
        for (explicit in listOf(false, true)) {
            val source = ObservedSource(ByteArray(100000) { 65 } + zipMetadataBase()).also { it.tailFailure = failure }
            assertSame(failure, assertFailsWith<ArchiveIOException> {
                if (explicit) ArchiveKodec.open(source, ArchiveNameDecoder.UTF8) else ArchiveKodec.open(source)
            })
            assertEquals(0, source.closes); source.close(); assertEquals(1, source.closes)
        }
    }

    @Test fun invalidFooterReadCountsAreTypedAndLeaveOwnershipWithCaller() {
        for (count in listOf(-1, 0, 65558)) {
            val source = ObservedSource(ByteArray(100000) { 65 } + zipMetadataBase()).also { it.tailCount = count }
            val error = assertFailsWith<ArchiveFormatException> { ArchiveKodec.open(source) }
            assertEquals(source.size - 65557, error.offset); assertEquals(0, source.closes)
        }
    }

    @Test fun footerCommentLengthAndEofAreRequiredByOpeningFallback() {
        for (bytes in listOf(prefix + zipMetadataBase() + byteArrayOf(1), prefix + zipWithComment(zipMetadataBase(), byteArrayOf(1)).also { it[it.size - 3] = 0 })) {
            val source = ObservedSource(bytes)
            assertFailsWith<ArchiveFormatException> { ArchiveKodec.open(source) }
            assertEquals(0, source.closes)
        }
    }

    @Test fun customNameDecoderRunsOnceAfterOffsetResolution() {
        val base = zipMetadataBase()
        putLe(base, 6, 0, 2); putLe(base, central(base) + 8, 0, 2)
        var calls = 0
        ArchiveKodec.open(ByteArrayRandomAccessSource(prefix + base), ArchiveNameDecoder { calls++; "renamed" }).use { r ->
            val e = r.entries().single(); assertEquals("renamed", e.name)
            assertContentEquals("a.txt".encodeToByteArray(), e.nameBytes)
            assertContentEquals(payload, r.read(e)); r.entries(); assertEquals(1, calls)
        }
    }

    @Test fun malformedDirectoryAndLocalFieldsKeepPhysicalOffsets() {
        val base = zipMetadataBase()
        val directory = central(base)
        val brokenDirectory = (prefix + base).also { it[prefix.size + directory] = 0 }
        assertEquals((prefix.size + directory).toLong(), assertFailsWith<ArchiveFormatException> { ZipReader(ByteArrayRandomAccessSource(brokenDirectory)).entries() }.offset)
        val inconsistent = (prefix + base).also { it[prefix.size + 8] = 8 }
        val error = assertFailsWith<ArchiveFormatException> { ZipReader(ByteArrayRandomAccessSource(inconsistent)).entries() }
        assertEquals("a.txt", error.entryName); assertEquals(prefix.size.toLong() + 8, error.offset)
    }

    @Test fun crcAndEncryptionChecksRemainAppliedAfterShifting() {
        val base = zipMetadataBase()
        val corrupt = (prefix + base).also { it[prefix.size + 35] = 0 }
        assertEquals(prefix.size.toLong() + 35, assertFailsWith<ArchiveFormatException> { read(corrupt) }.offset)
        putLe(base, 6, 1, 2); putLe(base, central(base) + 8, 1, 2)
        val encrypted = prefix + base
        assertEquals(prefix.size.toLong() + 6, assertFailsWith<ArchiveUnsupportedException> { read(encrypted) }.offset)
    }

    @Test fun shiftedOverlapsAndInconsistentOffsetConventionsRemainInvalid() {
        val original = ZipWriter.write(listOf(ZipWriter.FileSpec("a", payload, ZipMethod.STORED), ZipWriter.FileSpec("b", payload, ZipMethod.STORED)))
        val directory = central(original)
        putLe(original, directory + 20, 40, 4)
        assertFailsWith<ArchiveFormatException> { ZipReader(ByteArrayRandomAccessSource(prefix + original)).entries() }
        val mixed = zipMetadataBase()
        putLe(mixed, central(mixed) + 42, prefix.size.toLong(), 4)
        assertFailsWith<ArchiveFormatException> { read(prefix + mixed) }
    }

    @Test fun negativeDisplacementsAndMalformedZip64FramingAreRejected() {
        val classic = zipMetadataBase()
        putLe(classic, classic.size - 6, 0xfffffffeL, 4)
        assertFailsWith<ArchiveFormatException> { read(prefix + classic) }
        for (value in listOf(-1L, Long.MAX_VALUE, 43L)) {
            val base = zip64WithComment(0)
            putLe(base, base.size - 98 + 4, value, 8)
            assertFailsWith<ArchiveFormatException> { read(prefix + base) }
        }
        for (value in listOf(-1L, Long.MAX_VALUE)) {
            val base = zip64WithComment(0)
            putLe(base, base.size - 42 + 8, value, 8)
            assertFailsWith<ArchiveFormatException> { read(prefix + base) }
        }
    }

    private class SparseSource(val archive: ByteArray, val base: Long) : RandomAccessSource {
        override val size = base + archive.size
        val reads = mutableListOf<Pair<Long, Int>>()
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            reads.add(offset to length)
            assertTrue(length <= 65557)
            assertTrue(offset >= 0 && offset <= size && length <= size - offset)
            into.fill(65, intoOffset, intoOffset + length)
            val start = maxOf(offset, base); val end = minOf(offset + length, size)
            if (start < end) archive.copyInto(into, intoOffset + (start - offset).toInt(), (start - base).toInt(), (end - base).toInt())
            return length
        }
    }

    @Test fun hugePrefixesAndNearLongMaxOffsetsUseBoundedReads() {
        for (archive in listOf(zipMetadataBase(), zip64WithComment(0))) for (base in listOf(1L shl 32, Long.MAX_VALUE - archive.size)) {
            val source = SparseSource(archive, base)
            ArchiveKodec.open(source).use { assertArchive(it, base) }
            assertTrue(source.reads.size < 30)
        }
    }

    @Test fun zip64LocalOffsetAdditionCannotOverflow() {
        val archive = zipEntryMetadata(4, zip64Extra(4, offset = Long.MAX_VALUE))
        val source = SparseSource(archive, Long.MAX_VALUE - archive.size)
        val error = assertFailsWith<ArchiveFormatException> { ZipReader(source).entries() }
        assertEquals("a.txt", error.entryName)
    }

    @Test fun invalidAnchoredZip64SizesFailWithoutScanningAHugePrefix() {
        for (value in listOf(43L, -1L, Long.MAX_VALUE)) {
            val archive = zip64WithComment(0)
            val at = archive.size - 98
            putLe(archive, at + 4, value, 8)
            val source = SparseSource(archive, Long.MAX_VALUE - archive.size)
            val error = assertFailsWith<ArchiveFormatException> { ZipReader(source).entries() }
            assertEquals(source.base + at + 4, error.offset)
            assertTrue(source.reads.size < 30)
        }
    }

    @Test fun outputLimitsStillApplyToPrefixedPayloads() {
        ArchiveKodec.open(ByteArrayRandomAccessSource(prefix + zipMetadataBase())).use { r ->
            val e = r.entries().single()
            assertFailsWith<DecompressionBombException> { r.read(e, ArchiveLimits { maxUncompressedBytes = 2 }) }
            assertContentEquals(payload, r.read(e, ArchiveLimits { maxUncompressedBytes = 3 }))
        }
    }
}
