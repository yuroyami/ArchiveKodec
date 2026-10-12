package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.ForeignFacadeFixtures
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.CodecRegistry
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FormatRegistryTest {
    private val squash = ArchiveFormat("myfs")
    private val data = "payload".encodeToByteArray()
    private val bytes = "MYFS".encodeToByteArray() + data
    private val descriptor = FormatSpec(squash,
        matches = { source -> source.size >= 4 && source.readFully(0, 4).decodeToString() == "MYFS" },
        open = { source, options -> TestReader(source, squash, options) })
    private val registry = FormatRegistry.DEFAULT.with(descriptor)

    private class TestReader(
        private val source: RandomAccessSource, override val format: ArchiveFormat,
        val options: ArchiveOpenOptions,
    ) : ArchiveReader {
        override val limits = options.limits
        override fun entries(): List<ArchiveEntry> {
            limits.checkEntryCount(1)
            val name = options.nameDecoder?.decode("file".encodeToByteArray()) ?: "file"
            return listOf(ArchiveEntry(name, source.size - 4, false))
        }
        override fun read(entry: ArchiveEntry, limits: ArchiveLimits): ByteArray {
            limits.checkOutputSize(entry.size)
            limits.checkRatio(entry.size, entry.size)
            return source.readFullyChecked(4, entry.size)
        }
        override fun close() = source.close()
    }

    private class Source(bytes: ByteArray) : RandomAccessSource {
        private val delegate = ByteArrayRandomAccessSource(bytes)
        var reads = 0
        var closes = 0
        var rejectReads = false
        override val size get() = delegate.size
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            reads++
            check(!rejectReads) { "unexpected read at $offset" }
            return delegate.read(offset, into, intoOffset, length)
        }
        override fun close() { closes++ }
    }

    @Test fun identifiersAreOpenValuesAndUnknownIsReserved() {
        val same = ArchiveFormat("MYFS")
        assertEquals(squash, same); assertEquals(squash.hashCode(), same.hashCode())
        assertEquals("myfs", same.id); assertEquals("MYFS", same.name)
        assertEquals("MYFS", same.toString())
        assertNotEquals(squash, ArchiveFormat("myfs-v2"))
        assertEquals(ArchiveFormat.TAR, ArchiveFormat("tar"))
        for (bad in listOf("", " tar", "tar ", "/tar", "t:r", "K", "-x")) {
            assertFailsWith<IllegalArgumentException> { ArchiveFormat(bad) }
            assertNull(registry.byName(bad))
        }
        assertFailsWith<IllegalArgumentException> { FormatSpec("UNKNOWN", { true }, { source, options -> TestReader(source, squash, options) }) }
    }

    @Test fun customReaderIsReachableFromBytesAndSourcesWithoutChangingDefaults() {
        assertSame(descriptor, registry.byId(ArchiveFormat("MYFS")))
        assertSame(descriptor, registry.byName("MYFS"))
        assertNull(FormatRegistry.DEFAULT.byId(squash))
        assertNull(registry.byName("missing"))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveKodec.sniff(bytes))
        assertEquals(squash, ArchiveKodec.sniff(bytes, registry))
        assertEquals(squash, ArchiveKodec.sniff(Source(bytes), registry))
        assertFailsWith<ArchiveFormatException> { ArchiveKodec.open(bytes) }
        val source = Source(bytes)
        for (reader in listOf(ArchiveKodec.open(bytes, registry), ArchiveKodec.open(source, registry))) reader.use {
            assertIs<TestReader>(it)
            assertEquals(squash, it.format)
            assertContentEquals(data, it.read(it.entries().single()))
        }
        assertEquals(1, source.closes)
    }

    @Test fun probingDoesNotOpenOrCloseTheSource() {
        var opened = 0
        val spec = FormatSpec("probe", { true }, { source, options ->
            opened++; TestReader(source, ArchiveFormat("probe"), options)
        })
        val source = Source(bytes)
        assertSame(spec, registry.with(spec).sniff(source))
        assertEquals(0, opened); assertEquals(0, source.closes)
    }

    @Test fun laterDescriptorsTakePrecedenceAndReplacementsStayIsolated() {
        val first = FormatSpec("first", { true }, { s, o -> TestReader(s, ArchiveFormat("first"), o) })
        val second = FormatSpec("second", { true }, { s, o -> TestReader(s, ArchiveFormat("second"), o) })
        val a = registry.with(first)
        val b = a.with(second)
        assertSame(first, a.sniff(Source(bytes)))
        assertSame(second, b.sniff(Source(bytes)))
        val replacement = FormatSpec("FIRST", { true }, { s, o -> TestReader(s, ArchiveFormat("first"), o) })
        val c = b.with(replacement)
        assertSame(replacement, c.sniff(Source(bytes)))
        assertSame(first, b.byName("first"))
        assertEquals(1, c.formats.count { it.id == first.id })
        val exposed = c.formats
        (exposed as MutableList<FormatSpec>).clear()
        assertSame(replacement, c.byName("first"))
        assertTrue(c.formats.isNotEmpty())
    }

    @Test fun arbitraryOffsetProbeCanReplaceTheBuiltInIsoRule() {
        val image = ByteArray(32774)
        "CD001".encodeToByteArray().copyInto(image, 32769)
        val iso = FormatSpec("iso9660", { source ->
            source.size >= 32774 && source.readFully(32769, 5).decodeToString() == "CD001"
        }, { s, o -> TestReader(s, ArchiveFormat("iso9660"), o) })
        val formats = FormatRegistry.DEFAULT.with(iso)
        // The built-in rule finds ISO 9660 at byte 32769 behind a zero system area.
        assertEquals(ArchiveFormat.ISO9660, ArchiveKodec.sniff(image))
        assertEquals(ArchiveFormat.TAR, ArchiveKodec.sniff(image.copyOf(32768)))
        assertEquals(iso.id, ArchiveKodec.sniff(image, formats))
        ArchiveKodec.open(image, formats).use { assertEquals(iso.id, it.format) }
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveKodec.sniff(byteArrayOf(1), formats))
    }

    @Test fun allBuiltInRulesShareOneBoundedHeaderRead() {
        for ((content, expected) in listOf(ByteArray(1024) { 65 } to null, ForeignFacadeFixtures.v7 to ArchiveFormat.TAR)) {
            val source = Source(content)
            val spec = FormatRegistry.DEFAULT.sniff(source)
            assertEquals(expected, spec?.id)
            assertEquals(1, source.reads)
            assertEquals(0, source.closes)
        }
        val source = Source(byteArrayOf())
        assertNull(FormatRegistry.DEFAULT.sniff(source)); assertEquals(0, source.reads)
    }

    @Test fun explicitSelectionSkipsAllProbesAndFooterSearch() {
        var probes = 0
        val spec = FormatSpec("chosen", { probes++; error("must not sniff") }, { s, o -> TestReader(s, ArchiveFormat("chosen"), o) })
        val formats = registry.with(spec)
        val source = Source(bytes).also { it.rejectReads = true }
        ArchiveKodec.open(source, formats, format = "CHOSEN").use { assertEquals(spec.id, it.format) }
        assertEquals(0, probes); assertEquals(0, source.reads); assertEquals(1, source.closes)
        val tarSource = Source(ForeignFacadeFixtures.v7).also { it.rejectReads = true }
        ArchiveKodec.open(tarSource, format = "tar").use {
            assertIs<TarReader>(it); assertEquals(0, tarSource.reads)
            tarSource.rejectReads = false
            assertEquals(2, it.entries().size)
        }
        ArchiveKodec.open(ForeignFacadeFixtures.v7, format = "TAR").use { assertEquals(2, it.entries().size) }
        ArchiveKodec.open(ForeignFacadeFixtures.v7, format = null).use { assertEquals(2, it.entries().size) }
    }

    @Test fun unknownOrUnavailableExplicitIdsFailWithoutSourceReadsOrOwnershipTransfer() {
        for (id in listOf("missing", "", "lzma", "brotli", "bzip2")) {
            val source = Source(bytes).also { it.rejectReads = true }
            assertFailsWith<ArchiveUnsupportedException> { ArchiveKodec.open(source, format = id) }
            assertEquals(0, source.reads); assertEquals(0, source.closes)
        }
        val lzma = FormatSpec(ArchiveFormat.LZMA, { false }, { s, o -> TestReader(s, ArchiveFormat.LZMA, o) })
        ArchiveKodec.open(bytes, registry.with(lzma), format = "lzma").use { assertEquals(ArchiveFormat.LZMA, it.format) }
    }

    @Test fun probeFailuresPropagateWithoutFallbackOrClose() {
        val failure = ArchiveFormatException("probe failed", 32769)
        val spec = FormatSpec("broken", { throw failure }, { _, _ -> error("must not open") })
        val source = Source(ForeignFacadeFixtures.v7)
        val formats = registry.with(spec)
        assertSame(failure, assertFailsWith<ArchiveFormatException> { ArchiveKodec.sniff(source, formats) })
        assertSame(failure, assertFailsWith<ArchiveFormatException> { ArchiveKodec.open(source, formats) })
        assertEquals(0, source.reads); assertEquals(0, source.closes)
    }

    @Test fun factoryFailuresRetainCallerOwnershipIncludingThroughGzip() {
        val failure = ArchiveFormatException("factory failed")
        val spec = FormatSpec(squash, { s -> s.size >= 4 && s.readFully(0, 4).decodeToString() == "MYFS" }, { _, _ -> throw failure })
        for (content in listOf(bytes, Gzip.compress(bytes))) {
            val source = Source(content)
            assertSame(failure, assertFailsWith<ArchiveFormatException> { ArchiveKodec.open(source, registry.with(spec)) })
            assertEquals(0, source.closes)
            source.close(); assertEquals(1, source.closes)
        }
    }

    @Test fun gzipUnwrappingKeepsTheRegistryOptionsAndOriginalSourceOwnership() {
        val source = Source(Gzip.compress(bytes))
        val options = ArchiveOpenOptions { nameDecoder = ArchiveNameDecoder { "renamed" }; limits = ArchiveLimits { maxEntries = 1 } }
        for (reader in listOf(ArchiveKodec.open(source, registry, options = options),
            ArchiveKodec.open(Gzip.compress(bytes), registry, options = options))) reader.use {
            assertEquals(squash, it.format)
            assertSame(options, assertIs<TestReader>(it).options)
            val entry = it.entries().single()
            assertEquals("renamed", entry.name); assertContentEquals(data, it.read(entry))
        }
        assertEquals(1, source.closes)
        assertFailsWith<DecompressionBombException> {
            ArchiveKodec.open(Gzip.compress(bytes), registry, options = options.copy { limits = ArchiveLimits { maxUncompressedBytes = 1 } })
        }
        assertFailsWith<ArchiveUnsupportedException> { ArchiveKodec.open(Gzip.compress(Gzip.compress(bytes)), registry) }
    }

    @Test fun replacingGzipDescriptorUsesItsFactoryInsteadOfBuiltInUnwrapping() {
        val gzip = FormatSpec(ArchiveFormat.GZIP, { true }, { s, o -> TestReader(s, ArchiveFormat.GZIP, o) })
        val malformed = byteArrayOf(0x1f, 0x8b.toByte(), 8)
        assertFailsWith<ArchiveException> { ArchiveKodec.open(malformed) }
        ArchiveKodec.open(malformed, FormatRegistry.DEFAULT.with(gzip)).use { assertEquals(ArchiveFormat.GZIP, it.format) }
    }

    @Test fun optionsAreSnapshotsAndFactoriesEnforceTheirPolicies() {
        val builder = ArchiveOpenOptions.Builder()
        builder.limits = ArchiveLimits { maxEntries = 0 }
        val options = builder.build()
        builder.limits = ArchiveLimits.UNBOUNDED
        assertEquals(0, options.limits.maxEntries)
        val copy = options.copy { limits = ArchiveLimits { maxUncompressedBytes = 1 } }
        assertNotSame(copy, options)
        ArchiveKodec.open(bytes, registry, options = options).use { assertFailsWith<DecompressionBombException> { it.entries() } }
        ArchiveKodec.open(bytes, registry, options = copy).use {
            assertFailsWith<DecompressionBombException> { it.read(it.entries().single()) }
            assertContentEquals(data, it.read(it.entries().single(), ArchiveLimits.UNBOUNDED))
        }
    }

    @Test fun codecOptionsReachPlainAndGzipWrappedZipThroughTheFacade() {
        var calls = 0
        val codec = object : Codec {
            override val id = CodecId.STORE
            override fun compress(data: ByteArray) = data
            override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray {
                calls++; return Codecs.STORE.decompress(data, limits)
            }
        }
        val zip = ZipWriter.write(listOf(ZipWriter.FileSpec("a", data, ZipMethod.STORED)))
        val options = ArchiveOpenOptions { codecs = CodecRegistry.DEFAULT.with(codec) }
        for (content in listOf(zip, Gzip.compress(zip))) {
            ArchiveKodec.open(content, FormatRegistry.DEFAULT, options = options).use { assertContentEquals(data, it.read(it.entries().single())) }
            ArchiveKodec.open(Source(content), FormatRegistry.DEFAULT, options = options).use { assertContentEquals(data, it.read(it.entries().single())) }
        }
        assertEquals(4, calls)
        assertSame(CodecRegistry.DEFAULT, ArchiveOpenOptions.DEFAULT.codecs)
    }
}
