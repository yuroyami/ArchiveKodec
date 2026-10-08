package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveClosedException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.cpio.InitramfsReader
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.*

class InitramfsReaderTest {
    private fun record(
        name: String, data: ByteArray = byteArrayOf(), crc: Boolean = false,
        links: Long = 1, checksum: Long? = null, mode: Long = 0x81a4,
    ): ByteArray {
        val nameBytes = name.encodeToByteArray() + byteArrayOf(0)
        val sum = checksum ?: if (crc) data.sumOf { it.toLong() and 255 } and 0xffffffffL else 0
        val fields = longArrayOf(1, mode, 0, 0, links, 0, data.size.toLong(), 1, 13, 0, 0, nameBytes.size.toLong(), sum)
        val header = ((if (crc) "070702" else "070701") + fields.joinToString("") {
            it.toString(16).padStart(8, '0')
        }).encodeToByteArray()
        val named = header + nameBytes
        return named + ByteArray((4 - named.size % 4) % 4) + data + ByteArray((4 - data.size % 4) % 4)
    }

    private val trailer: ByteArray get() = record("TRAILER!!!")
    private fun reader(bytes: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT) =
        InitramfsReader(ByteArrayRandomAccessSource(bytes), limits)

    @Test fun browsesTheComplete760ByteReproductionWithoutChangingSingleArchiveReading() {
        val first = record("first", byteArrayOf(65)) + trailer
        val second = record("second", byteArrayOf(66)) + trailer
        val joined = first + ByteArray(512 - first.size) + second
        assertEquals(760, joined.size)
        reader(joined).use { r ->
            assertEquals(ArchiveFormat.CPIO, r.format)
            assertEquals(listOf("first", "second"), r.entries().map { it.name })
            val segments = r.segments()
            assertEquals(listOf(0L, 512L), segments.map { it.startOffset })
            assertEquals(listOf(244L, 760L), segments.map { it.endOffset })
            assertTrue(segments.all { it.hasTrailer })
            assertEquals(r.entries(), segments.flatMap { it.entries })
            assertContentEquals(byteArrayOf(65), r.read(segments[0].entries.single()))
            assertContentEquals(byteArrayOf(66), r.read(segments[1].entries.single()))
        }
        CpioReader(ByteArrayRandomAccessSource(joined)).use { assertEquals(listOf("first"), it.entries().map { e -> e.name }) }
        ArchiveKodec.open(ByteArrayRandomAccessSource(joined)).use { assertEquals(listOf("first"), it.entries().map { e -> e.name }) }
    }

    @Test fun mixesNewcAndCrcSegmentsPreservingDuplicateNamesAndAbsoluteOffsets() {
        val first = record("same", byteArrayOf(1)) + trailer
        val second = record("same", byteArrayOf(-1, -128), crc = true) + record("TRAILER!!!", crc = true)
        val bytes = ByteArray(8) + first + ByteArray(12) + second + ByteArray(7)
        reader(bytes).use { r ->
            val entries = r.entries()
            assertEquals(listOf("same", "same"), entries.map { it.name })
            assertEquals(listOf(124L, (8 + first.size + 12 + 116).toLong()), entries.map { it.dataOffset })
            assertContentEquals(byteArrayOf(1), r.read(entries[0]))
            assertContentEquals(byteArrayOf(-1, -128), r.read(entries[1]))
            assertEquals(listOf(8L, (8 + first.size + 12).toLong()), r.segments().map { it.startOffset })
            assertEquals((bytes.size - 7).toLong(), r.segments().last().endOffset)
        }
    }

    @Test fun trailersResetHardLinksEvenWhenDeviceAndInodeAreReused() {
        val first = record("one", byteArrayOf(11), links = 2) + record("one-alias", links = 2) + trailer
        val second = record("two-alias", crc = true, links = 2) + record("two", byteArrayOf(22), crc = true, links = 2) + trailer
        val third = record("empty", links = 2) + trailer
        val fourth = record("symlink", byteArrayOf(33), links = 2, mode = 0xa1ff) + trailer
        reader(first + second + third + fourth).use { r ->
            assertEquals(4, r.segments().size)
            val expected = listOf(byteArrayOf(11), byteArrayOf(11), byteArrayOf(22), byteArrayOf(22), byteArrayOf(), byteArrayOf(33))
            for ((entry, body) in r.entries().zip(expected)) assertContentEquals(body, r.read(entry), entry.name)
        }
    }

    @Test fun zeroPaddingWithinASequenceDoesNotResetHardLinksOrInventBoundaries() {
        val bytes = record("carrier", byteArrayOf(7), links = 2) + ByteArray(512) +
            record("alias", crc = true, links = 2) + ByteArray(4) + trailer
        reader(bytes).use { r ->
            assertEquals(1, r.segments().size)
            assertEquals(bytes.size.toLong(), r.segments().single().endOffset)
            for (entry in r.entries()) assertContentEquals(byteArrayOf(7), r.read(entry))
        }
    }

    @Test fun acceptsEmptyBuffersEmptySegmentsAndAnOptionalFinalTrailer() {
        for (bytes in listOf(byteArrayOf(), ByteArray(1), ByteArray(1025))) reader(bytes).use {
            assertTrue(it.entries().isEmpty())
            assertTrue(it.segments().isEmpty())
        }
        val last = record("last", byteArrayOf(9))
        val bytes = trailer + ByteArray(8) + trailer + last + ByteArray(5)
        reader(bytes).use { r ->
            assertEquals(listOf(true, true, false), r.segments().map { it.hasTrailer })
            assertEquals(listOf(0, 0, 1), r.segments().map { it.entries.size })
            assertEquals((bytes.size - 5).toLong(), r.segments().last().endOffset)
            assertContentEquals(byteArrayOf(9), r.read(r.entries().single()))
        }
        CpioReader(ByteArrayRandomAccessSource(byteArrayOf())).use {
            assertFailsWith<ArchiveFormatException> { it.entries() }
        }
    }

    @Test fun rejectsUnalignedRawHeadersButAllowsArbitraryTrailingZeroCounts() {
        for (count in 1..3) {
            for (prefix in listOf(byteArrayOf(), trailer)) {
                reader(prefix + ByteArray(count) + record("a")).use { r ->
                    val error = assertFailsWith<ArchiveFormatException> { r.segments() }
                    assertEquals((prefix.size + count).toLong(), error.offset)
                    assertTrue(error.message.orEmpty().contains("aligned"))
                }
            }
            reader(trailer + ByteArray(count)).use { assertEquals(124L, it.segments().single().endOffset) }
        }
    }

    @Test fun laterMalformedAndTruncatedSegmentsFailAtomicallyWithTheirOffset() {
        val first = record("ok", byteArrayOf(1)) + trailer
        val bad = listOf("broken".encodeToByteArray(), "07070".encodeToByteArray(), "070701".encodeToByteArray())
        for (suffix in bad) reader(first + ByteArray(16) + suffix).use { r ->
            repeat(2) {
                assertEquals((first.size + 16).toLong(), assertFailsWith<ArchiveFormatException> { r.entries() }.offset)
                assertEquals((first.size + 16).toLong(), assertFailsWith<ArchiveFormatException> { r.segments() }.offset)
            }
        }
        val malformed = record("later").also { it[14] = 'g'.code.toByte() }
        reader(first + malformed).use {
            assertEquals(first.size + 14L, assertFailsWith<ArchiveFormatException> { it.entries() }.offset)
        }
    }

    @Test fun rejectsCompressedSegmentsAtTheirActualPositionIncludingAfterOddPadding() {
        val examples = listOf(
            "gzip" to ArchiveKodec.gzip.compress(trailer),
            "gzip" to byteArrayOf(0x1f, 0x9e.toByte()),
            "bzip2" to "BZh9".encodeToByteArray(),
            "xz" to byteArrayOf(0xfd.toByte(), 0x37, 0x7a, 0x58, 0x5a, 0),
            "LZMA" to byteArrayOf(0x5d, 0),
            "LZO" to byteArrayOf(0x89.toByte(), 0x4c, 0x5a, 0x4f),
            "LZ4" to byteArrayOf(0x02, 0x21, 0x4c, 0x18),
            "LZ4" to byteArrayOf(0x04, 0x22, 0x4d, 0x18),
            "Zstandard" to byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte()),
        )
        for ((name, bytes) in examples) for (prefix in listOf(byteArrayOf(), trailer + ByteArray(1))) {
            reader(prefix + bytes).use { r ->
                val error = assertFailsWith<ArchiveUnsupportedException> { r.entries() }
                assertEquals(prefix.size.toLong(), error.offset)
                assertTrue(error.message.orEmpty().contains(name))
                assertTrue(error.message.orEmpty().contains("compressed segments"))
            }
        }
    }

    @Test fun refusesOdcAndBothBinaryDialectsInLaterSegments() {
        for (bytes in listOf("070707".encodeToByteArray(), byteArrayOf(0xc7.toByte(), 0x71), byteArrayOf(0x71, 0xc7.toByte()))) {
            reader(trailer + bytes).use { r ->
                assertEquals(124L, assertFailsWith<ArchiveUnsupportedException> { r.segments() }.offset)
            }
        }
    }

    @Test fun laterCrcBodyAndTrailerFailuresKeepPhysicalContext() {
        val first = record("ok", byteArrayOf(8)) + trailer
        val badBody = record("bad", byteArrayOf(9), crc = true, checksum = 10)
        reader(first + badBody + trailer).use { r ->
            assertContentEquals(byteArrayOf(8), r.read(r.entries()[0]))
            val error = assertFailsWith<ArchiveFormatException> { r.read(r.entries()[1]) }
            assertEquals(first.size + 102L, error.offset)
            assertEquals("bad", error.entryName)
        }
        reader(first + record("TRAILER!!!", crc = true, checksum = 1)).use {
            val error = assertFailsWith<ArchiveFormatException> { it.segments() }
            assertEquals(first.size + 102L, error.offset)
            assertEquals("TRAILER!!!", error.entryName)
        }
    }

    @Test fun countsEntriesAcrossSegmentsAndKeepsOutputLimitsPerRead() {
        val first = record("one", byteArrayOf(1, 2)) + trailer
        val second = record("two", byteArrayOf(3, 4)) + trailer
        reader(first + second, ArchiveLimits { maxEntries = 1 }).use { r ->
            val error = assertFailsWith<DecompressionBombException> { r.entries() }
            assertEquals(first.size + 110L, error.offset)
            assertEquals("two", error.entryName)
        }
        val limits = ArchiveLimits { maxEntries = 2; maxUncompressedBytes = 1 }
        reader(first + second, limits).use { r ->
            assertSame(limits, r.limits)
            assertEquals(2, r.entries().size)
            for (entry in r.entries()) {
                assertFailsWith<DecompressionBombException> { r.read(entry) }
                assertEquals(2, r.read(entry, limits.copy { maxUncompressedBytes = 2 }).size)
            }
        }
    }

    @Test fun countsEmptySegmentsIndependentlyOfEntriesAndBoundsZeroPolicies() {
        val zero = ArchiveLimits { maxSegments = 0; maxEntries = 0 }
        reader(ByteArray(5), zero).use { assertTrue(it.segments().isEmpty()) }
        for (bytes in listOf(trailer, record("a"))) reader(bytes, zero).use {
            assertEquals(0L, assertFailsWith<DecompressionBombException> { it.segments() }.offset)
        }
        val two = zero.copy { maxSegments = 2 }
        reader(trailer + trailer, two).use { assertEquals(2, it.segments().size) }
        reader(trailer + trailer + trailer, two).use {
            assertEquals(248L, assertFailsWith<DecompressionBombException> { it.entries() }.offset)
        }
        reader(trailer + record("a"), two).use {
            val error = assertFailsWith<DecompressionBombException> { it.entries() }
            assertTrue(error.message.orEmpty().contains("entry count"))
        }
        // Single-archive readers have no segment policy.
        CpioReader(ByteArrayRandomAccessSource(trailer), zero).use { assertTrue(it.entries().isEmpty()) }
    }

    private class ObservedSource(bytes: ByteArray, private val bodies: List<LongRange> = emptyList()) : RandomAccessSource {
        private val delegate = ByteArrayRandomAccessSource(bytes)
        var forbidPayload = true
        var reads = 0
        var closes = 0
        var shortReads = false
        var closeFailure = false
        var onRead: (() -> Unit)? = null
        override val size: Long get() = delegate.size
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            if (forbidPayload && bodies.any { offset <= it.last && offset + length > it.first }) {
                error("metadata listing touched a payload at $offset + $length")
            }
            reads++
            onRead?.invoke()
            return delegate.read(offset, into, intoOffset, if (shortReads) minOf(3, length) else length)
        }
        override fun close() {
            closes++
            if (closeFailure) throw ArchiveIOException("close failed", IllegalStateException())
        }
    }

    @Test fun metadataOnlyBrowsingHandlesPaddingAndShortReadsAndCachesBothViews() {
        val first = record("first", ByteArray(1025) { 7 }) + trailer
        val second = record("other", ByteArray(2049) { 8 }, crc = true) + trailer
        for (padding in listOf(0, 4, 108, 112, 268, 512, 8192)) {
            val secondStart = first.size + padding
            val source = ObservedSource(first + ByteArray(padding) + second, listOf(116L..1140L, secondStart + 116L..secondStart + 2164L))
            source.shortReads = true
            InitramfsReader(source).use { r ->
                val segments = r.segments()
                val calls = source.reads
                assertSame(segments, r.segments())
                assertSame(r.entries(), r.entries())
                assertEquals(calls, source.reads)
                assertSame(segments[1].entries.single(), r.entries()[1])
                source.forbidPayload = false
                assertContentEquals(ByteArray(2049) { 8 }, r.read(r.entries()[1]))
            }
            assertEquals(1, source.closes)
        }
    }

    @Test fun customNameDecodingAppliesToAllSegmentsAndPreservesRawNames() {
        val entry = record("x", byteArrayOf(1)).also { it[110] = 0x82.toByte() }
        val bytes = trailer + entry + trailer
        reader(bytes).use {
            assertEquals(234L, assertFailsWith<ArchiveFormatException> { it.entries() }.offset)
        }
        InitramfsReader(ByteArrayRandomAccessSource(bytes), ArchiveNameDecoder.CP437).use {
            assertEquals("é", it.entries().single().name)
            assertContentEquals(byteArrayOf(0x82.toByte()), it.entries().single().nameBytes)
        }
    }

    @Test fun closingInvalidatesBothCachedViewsAndReleasesTheSourceOnlyOnceEvenOnFailure() {
        for (fail in listOf(false, true)) {
            val source = ObservedSource(record("a", byteArrayOf(1)) + trailer).apply { closeFailure = fail }
            val r = InitramfsReader(source)
            val entry = r.entries().single()
            r.segments()
            if (fail) assertFailsWith<ArchiveIOException> { r.close() } else r.close()
            r.close()
            assertEquals(1, source.closes)
            assertFailsWith<ArchiveClosedException> { r.entries() }
            assertFailsWith<ArchiveClosedException> { r.segments() }
            assertFailsWith<ArchiveClosedException> { r.read(entry) }
        }
        val source = ObservedSource("broken".encodeToByteArray())
        val r = InitramfsReader(source)
        assertFailsWith<ArchiveFormatException> { r.entries() }
        r.close()
        assertEquals(1, source.closes)
    }

    @Test fun reentrantCloseDuringListingCannotPublishCachedSegments() {
        val source = ObservedSource(trailer)
        val r = InitramfsReader(source)
        source.onRead = { r.close() }
        assertFailsWith<ArchiveClosedException> { r.segments() }
        assertFailsWith<ArchiveClosedException> { r.entries() }
        assertEquals(1, source.closes)
    }
}
