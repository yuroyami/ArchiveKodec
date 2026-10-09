package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.ar.ArWriter
import io.github.yuroyami.archivekodec.archive.cpio.CpioWriter
import io.github.yuroyami.archivekodec.archive.tar.TarWriter
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.knownCodecIds
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArchiveFacadeConvenienceTest {
    private val data = "hello".encodeToByteArray()
    private fun zip(payload: ByteArray = data): ByteArray = ZipWriter.write(listOf(ZipWriter.FileSpec("hello.txt", payload)))

    @Test
    fun namesAndFormatsUseTheSameRegisteredInstances() {
        for (id in knownCodecIds) {
            assertSame(ArchiveKodec.codec(id), ArchiveKodec.codec(id.displayName), id.toString())
            assertSame(Codecs.byId(id), Codecs.byName(id.displayName.uppercase()), id.toString())
        }
        val supported = mapOf(ArchiveFormat.GZIP to Codecs.GZIP, ArchiveFormat.ZLIB to Codecs.ZLIB,
            ArchiveFormat.LZ4 to Codecs.LZ4, ArchiveFormat.SNAPPY to Codecs.SNAPPY_FRAMED)
        for (format in ArchiveFormat.entries) {
            assertSame(supported[format], ArchiveKodec.codec(format), format.toString())
            assertSame(supported[format], Codecs.byFormat(format), format.toString())
        }
        for (name in listOf("", "missing", " gzip ", "x-gzip", "gzip, deflate")) assertNull(ArchiveKodec.codec(name))
        assertSame(Codecs.DEFLATE, ArchiveKodec.codec("DEFLATE"))
        assertSame(Codecs.SNAPPY, ArchiveKodec.codec("snappy"))
        assertSame(Codecs.SNAPPY_FRAMED, ArchiveKodec.codec("snappy-framed"))
    }

    @Test
    fun detectsEveryImplementedStreamAndAppliesLimits() {
        for (codec in listOf(Codecs.GZIP, Codecs.ZLIB, Codecs.LZ4, Codecs.SNAPPY_FRAMED)) {
            val input = codec.compress(data)
            assertContentEquals(data, ArchiveKodec.decompress(input), codec.id.toString())
            assertContentEquals(data, ArchiveKodec.decompress(input, ArchiveLimits { maxUncompressedBytes = 5 }))
            assertFailsWith<DecompressionBombException> {
                ArchiveKodec.decompress(input, ArchiveLimits { maxUncompressedBytes = 4 })
            }
            assertContentEquals(byteArrayOf(), ArchiveKodec.decompress(codec.compress(byteArrayOf()),
                ArchiveLimits { maxUncompressedBytes = 0 }))
        }
    }

    @Test
    fun decodesIndependentGzipAndZlibAndKeepsIntegrityChecks() {
        // RFC-framed fixtures also consumed by the independent JDK interop tests.
        val gzip = hex("1f8b08020000000000ff90c9cb48cdc9c9070086a6103605000000")
        val zlib = hex("789ccb48cdc9c90700062c0215")
        for (input in listOf(gzip, zlib)) {
            assertContentEquals(data, ArchiveKodec.decompress(input))
            val damaged = input.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertFailsWith<ArchiveFormatException> { ArchiveKodec.decompress(damaged) }
        }
    }

    @Test
    fun unsupportedDecodingNamesTheDetectedFormat() {
        for ((input, format) in listOf(
            hex("425a6839177245385090") to ArchiveFormat.BZIP2,
            zip() to ArchiveFormat.ZIP,
            data to ArchiveFormat.UNKNOWN,
            byteArrayOf() to ArchiveFormat.UNKNOWN,
        )) {
            val error = assertFailsWith<ArchiveUnsupportedException> { ArchiveKodec.decompress(input) }
            assertTrue(error.message.orEmpty().contains(format.name), "Expected $format in ${error.message}")
            assertEquals(0L, error.offset)
        }
    }

    @Test
    fun decodingUnwrapsOneLayerAndKeepsConcatenatedMemberBehavior() {
        val inner = Gzip.compress(data)
        assertContentEquals(inner, ArchiveKodec.decompress(Gzip.compress(inner)))
        assertContentEquals(data + data, ArchiveKodec.decompress(inner + inner))
    }

    @Test
    fun opensAllContainersFromBytesAndThroughGzip() {
        val fixtures = listOf(ArchiveFormat.ZIP to zip(),
            ArchiveFormat.TAR to TarWriter.write(listOf(TarWriter.FileSpec("hello.txt", data))),
            ArchiveFormat.AR to ArWriter.write(listOf(ArWriter.FileSpec("hello.txt", data))),
            ArchiveFormat.CPIO to CpioWriter.write(listOf(CpioWriter.FileSpec("hello.txt", data))))
        for ((format, plain) in fixtures) for (bytes in listOf(plain, Gzip.compress(plain))) {
            val policy = ArchiveLimits { maxUncompressedBytes = 4096 }
            val readers = listOf(ArchiveKodec.open(bytes), ArchiveKodec.open(bytes, policy),
                ArchiveKodec.open(bytes, ArchiveNameDecoder.UTF8),
                ArchiveKodec.open(bytes, ArchiveNameDecoder.UTF8, policy))
            for (reader in readers) reader.use {
                assertEquals(format, it.format)
                val entry = it.entries().single()
                assertEquals("hello.txt", entry.name)
                assertContentEquals(data, it.read(entry))
            }
            val source = TrackingSource(bytes)
            ArchiveKodec.open(source).use {
                assertEquals(format, it.format)
                assertContentEquals(data, it.read(it.entries().single()))
            }
            assertEquals(1, source.closes)
        }
    }

    @Test
    fun keepsZipFooterDetectionInsideGzip() {
        val prefixed = "#!/bin/example\n".encodeToByteArray() + zip()
        for (bytes in listOf(prefixed, Gzip.compress(prefixed))) {
            ArchiveKodec.open(bytes).use { assertContentEquals(data, it.read(it.entries().single())) }
            ArchiveKodec.open(TrackingSource(bytes)).use { assertContentEquals(data, it.read(it.entries().single())) }
        }
    }

    @Test
    fun enforcesGzipOutputRatioAndListingLimitsAtTheirOwnStages() {
        val plain = TarWriter.write(listOf(TarWriter.FileSpec("hello.txt", data)))
        val bytes = Gzip.compress(plain)
        for (sourceCall in listOf(false, true)) {
            fun open(policy: ArchiveLimits): ArchiveReader = if (sourceCall) ArchiveKodec.open(TrackingSource(bytes), policy)
                else ArchiveKodec.open(bytes, policy)
            assertFailsWith<DecompressionBombException> { open(ArchiveLimits { maxUncompressedBytes = plain.size - 1L }) }
            assertFailsWith<DecompressionBombException> { open(ArchiveLimits { maxCompressionRatio = 1 }) }
            val exact = ArchiveLimits { maxUncompressedBytes = plain.size.toLong() }
            open(exact).use {
                assertSame(exact, it.limits)
                assertContentEquals(data, it.read(it.entries().single()))
            }
            open(exact.copy { maxEntries = 0 }).use { assertFailsWith<DecompressionBombException> { it.entries() } }
        }
    }

    @Test
    fun theUnwrappedReaderRetainsItsDefaultEntryPolicy() {
        val payload = ByteArray(8192)
        val plain = zip(payload)
        val bytes = Gzip.compress(plain)
        val policy = ArchiveLimits { maxUncompressedBytes = plain.size.toLong() }
        assertTrue(plain.size < payload.size)
        for (reader in listOf(ArchiveKodec.open(bytes, policy), ArchiveKodec.open(TrackingSource(bytes), policy))) reader.use {
            assertSame(policy, it.limits)
            val entry = it.entries().single()
            assertFailsWith<DecompressionBombException> { it.read(entry) }
            assertContentEquals(payload, it.read(entry, ArchiveLimits.UNBOUNDED))
        }
    }

    @Test
    fun explicitNameDecoderReachesTheUnwrappedReader() {
        val bytes = Gzip.compress(TarWriter.write(listOf(TarWriter.FileSpec("hello.txt", data))))
        val decoder = ArchiveNameDecoder { "custom.txt" }
        val policy = ArchiveLimits { maxEntries = 1 }
        for (reader in listOf(ArchiveKodec.open(bytes, decoder, policy),
            ArchiveKodec.open(TrackingSource(bytes), decoder), ArchiveKodec.open(TrackingSource(bytes), decoder, policy))) reader.use {
            val entry = it.entries().single()
            assertEquals("custom.txt", entry.name)
            assertContentEquals(data, it.read(entry))
        }
    }

    @Test
    fun gzipSourceIsRetainedUntilCloseAndDecodedBytesAreASnapshot() {
        val bytes = Gzip.compress(zip())
        val source = TrackingSource(bytes)
        val reader = ArchiveKodec.open(source)
        assertEquals(0, source.closes)
        bytes.fill(0)
        val entry = reader.entries().single()
        assertContentEquals(data, reader.read(entry))
        reader.close()
        reader.close()
        assertEquals(1, source.closes)
        assertFailsWith<ArchiveClosedException> { reader.entries() }
        assertFailsWith<ArchiveClosedException> { reader.read(entry) }
    }

    @Test
    fun gzipCloseFailureStillClosesTheReaderAndIsNotRetried() {
        val source = TrackingSource(Gzip.compress(zip()))
        val reader = ArchiveKodec.open(source)
        val failure = ArchiveIOException("close failed", IllegalStateException("test"))
        source.onClose = {
            assertFailsWith<ArchiveClosedException> { reader.entries() }
            reader.close()
            throw failure
        }
        assertSame(failure, assertFailsWith<ArchiveIOException> { reader.close() })
        reader.close()
        assertEquals(1, source.closes)
    }

    @Test
    fun failedGzipOpeningLeavesTheSourceWithTheCaller() {
        val valid = Gzip.compress(zip())
        val damaged = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        for (bytes in listOf(hex("1f8b0800"), damaged, Gzip.compress(data), Gzip.compress(valid))) {
            val source = TrackingSource(bytes)
            assertFailsWith<ArchiveException> { ArchiveKodec.open(source) }
            assertEquals(0, source.closes)
            source.close()
            assertEquals(1, source.closes)
        }
        val limited = TrackingSource(valid)
        assertFailsWith<DecompressionBombException> { ArchiveKodec.open(limited, ArchiveLimits { maxUncompressedBytes = 1 }) }
        assertEquals(0, limited.closes)
        limited.close()
        val failedRead = TrackingSource(valid).also { it.failAtRead = 2 }
        assertFailsWith<ArchiveIOException> { ArchiveKodec.open(failedRead) }
        assertEquals(0, failedRead.closes)
        failedRead.close()
    }

    @Test
    fun nestedGzipAndOtherWrappersDoNotOpenRecursively() {
        for ((bytes, format) in listOf(Gzip.compress(Gzip.compress(zip())) to ArchiveFormat.GZIP,
            Codecs.ZLIB.compress(zip()) to ArchiveFormat.ZLIB, Codecs.LZ4.compress(zip()) to ArchiveFormat.LZ4)) {
            for (sourceCall in listOf(false, true)) {
                val error = assertFailsWith<ArchiveUnsupportedException> {
                    if (sourceCall) ArchiveKodec.open(TrackingSource(bytes)) else ArchiveKodec.open(bytes)
                }
                assertTrue(error.message.orEmpty().contains(format.name))
            }
        }
    }

    @Test
    fun oversizedCompressedSourcesFailBeforeAllocatingTheWholeInput() {
        var reads = 0
        var closes = 0
        val source = object : RandomAccessSource {
            override val size: Long = Int.MAX_VALUE.toLong() + 1
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                reads++
                assertEquals(0L, offset)
                assertEquals(512, length)
                byteArrayOf(0x1f, 0x8b.toByte(), 8).copyInto(into, intoOffset)
                return length
            }
            override fun close() { closes++ }
        }
        val error = assertFailsWith<ArchiveFormatException> { ArchiveKodec.open(source) }
        assertTrue(error.message.orEmpty().contains("cannot fit in a ByteArray"))
        assertEquals(1, reads)
        assertEquals(0, closes)
        source.close()
    }

    private class TrackingSource(bytes: ByteArray) : RandomAccessSource {
        private val delegate = ByteArrayRandomAccessSource(bytes)
        var closes = 0
        var reads = 0
        var failAtRead = Int.MAX_VALUE
        var onClose: () -> Unit = {}
        override val size: Long get() = delegate.size
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            if (++reads == failAtRead) throw ArchiveIOException("read failed", IllegalStateException("test"))
            return delegate.read(offset, into, intoOffset, length)
        }
        override fun close() { closes++; onClose() }
    }
}
