package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.tar.TarWriter
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.codec.deflate.InflateOptions
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.ArchivePathException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** CRC-32 policy, prefix reads, lookup by name, descriptor-sized entries and the mixed name decoder. */
class ZipReadingOptionsTest {

    private val hello = "hello, archive".encodeToByteArray()
    private val text = buildString { for (i in 0 until 60_000) append("line $i of a long chapter\n") }.encodeToByteArray()

    // ---- CRC-32 policy and verify ----

    @Test
    fun aWrongCrcThrowsByDefaultAndReturnsTheBytesWhenLenient() {
        val zip = RawZip.build(
            RawZip.Entry("s.txt", hello, crc = Crc32.of(hello) xor 1),
            RawZip.Entry("d.txt", text, method = 8, crc = Crc32.of(text) xor 2),
            RawZip.Entry("ok.txt", hello, method = 8),
        )
        val strict = ZipReader(ByteArrayRandomAccessSource(zip))
        val (s, d, ok) = strict.entries()
        assertFailsWith<ArchiveFormatException> { strict.read(s) }
        assertFailsWith<ArchiveFormatException> { strict.read(d) }
        assertFailsWith<ArchiveFormatException> { strict.read(d, ByteSink { _, _, _ -> }) }
        assertFalse(strict.verify(s))
        assertFalse(strict.verify(d))
        assertTrue(strict.verify(ok))

        val told = mutableListOf<String>()
        val options = ArchiveOpenOptions {
            crcPolicy = CrcPolicy.LENIENT
            crcMismatchListener = CrcMismatchListener { entry, expected, actual ->
                told += "${entry.name} ${expected xor actual}"
            }
        }
        val lenient = ZipReader(ByteArrayRandomAccessSource(zip), options)
        val (s2, d2) = lenient.entries()
        assertContentEquals(hello, lenient.read(s2))
        assertContentEquals(text, lenient.read(d2))
        var streamed = 0L
        lenient.read(d2, ByteSink { _, _, length -> streamed += length })
        assertEquals(text.size.toLong(), streamed)
        assertEquals(listOf("s.txt 1", "d.txt 2", "d.txt 2"), told)
        assertFalse(lenient.verify(d2), "verify reports a mismatch whatever the policy")
    }

    // ---- Prefix reads ----

    @Test
    fun aPrefixInflatesOnlyWhatItNeeds() {
        val big = ByteArray(8 shl 20) { (it * 7 + it / 4096).toByte() }
        val zip = RawZip.build(RawZip.Entry("big.bin", big, method = 8), RawZip.Entry("small.txt", hello, method = 8))
        val counting = CountingSource(zip)
        val reader = ZipReader(counting)
        val (bigEntry, small) = reader.entries()
        counting.bytesRead = 0
        assertContentEquals(big.copyOf(64), reader.readPrefix(bigEntry, 64))
        assertTrue(counting.bytesRead < 8192, "read ${counting.bytesRead} bytes for a 64-byte prefix")
        assertContentEquals(big.copyOf(100_000), reader.readPrefix(bigEntry, 100_000))
        assertContentEquals(hello, reader.readPrefix(small, 64))
        assertEquals(0, reader.readPrefix(small, 0).size)
        assertFailsWith<IllegalArgumentException> { reader.readPrefix(small, -1) }
    }

    @Test
    fun aStoredPrefixReadsItsBytes() {
        val zip = RawZip.build(RawZip.Entry("s.bin", text))
        val reader = ZipReader(ByteArrayRandomAccessSource(zip))
        val entry = reader.entries().single()
        assertContentEquals(text.copyOf(10), reader.readPrefix(entry, 10))
        assertContentEquals(text, reader.readPrefix(entry, text.size))
    }

    @Test
    fun otherReadersReturnTheStartOfAMember() {
        val tar = TarWriter.write(listOf(TarWriter.FileSpec("a.txt", text), TarWriter.FileSpec("b.txt", hello)))
        val reader = TarReader(ByteArrayRandomAccessSource(tar))
        val (a, b) = reader.entries()
        assertContentEquals(text.copyOf(33), reader.readPrefix(a, 33))
        assertContentEquals(hello, reader.readPrefix(b, 1000))
    }

    @Test
    fun theInflateStopReturnsTheStartOfAStream() {
        val stream = Deflate.encode(ByteArray(50 shl 20))
        val stopped = Inflate.inflate(stream, InflateOptions { stopAfterBytes = 100 })
        assertEquals(100, stopped.output.size)
        assertTrue(stopped.isStopped)
        assertFalse(stopped.isComplete)
        assertNull(stopped.error)
        assertTrue(stopped.inputConsumed < stream.size)
        val whole = Inflate.inflate(Deflate.encode(text), InflateOptions { stopAfterBytes = text.size.toLong() })
        assertContentEquals(text, whole.output)
        assertFalse(whole.isStopped)
        assertTrue(whole.isComplete)
        assertEquals(0, Inflate.inflate(stream, InflateOptions { stopAfterBytes = 0 }).output.size)
        assertFailsWith<IllegalArgumentException> { InflateOptions { stopAfterBytes = -2 } }
    }

    // ---- Lookup by name ----

    @Test
    fun aLookupReturnsTheLastOfTwoEntriesWithOneName() {
        val zip = RawZip.build(RawZip.Entry("a.txt", "first".encodeToByteArray()), RawZip.Entry("a.txt", "second".encodeToByteArray()))
        val reader = ZipReader(ByteArrayRandomAccessSource(zip))
        assertEquals(2, reader.entries().size)
        assertSame(reader.entries()[1], reader.entry("a.txt"))
        assertEquals("second", reader.read(reader.entry("a.txt")!!).decodeToString())
        assertNull(reader.entry("A.TXT"))
        assertNull(reader.entry("missing"))
        val rejecting = ZipReader(ByteArrayRandomAccessSource(zip), ArchiveLimits { rejectDuplicateNames = true })
        assertFailsWith<ArchivePathException> { rejecting.entries() }

        val tar = TarWriter.write(listOf(TarWriter.FileSpec("x", hello), TarWriter.FileSpec("x", text)))
        val tarReader = TarReader(ByteArrayRandomAccessSource(tar))
        assertSame(tarReader.entries()[1], tarReader.entry("x"))
    }

    // ---- Entries sized only by their data descriptor ----

    @Test
    fun descriptorEntriesReadWithAndWithoutTheSignature() {
        val zip = RawZip.build(
            RawZip.Entry("stored.txt", text, descriptor = RawZip.Descriptor.SIGNED, hideSizes = true),
            RawZip.Entry("deflated.txt", text, method = 8, descriptor = RawZip.Descriptor.BARE, hideSizes = true),
            RawZip.Entry("after.txt", hello),
        )
        val reader = ZipReader(ByteArrayRandomAccessSource(zip))
        val (stored, deflated, after) = reader.entries()
        assertEquals(-1, stored.size)
        assertEquals(-1, stored.compressedSize)
        assertNull(stored.crc32)
        assertContentEquals(text, reader.read(stored))
        assertContentEquals(text, reader.read(deflated))
        assertContentEquals(hello, reader.read(after))
        assertTrue(reader.verify(stored))
        assertContentEquals(text.copyOf(50), reader.readPrefix(stored, 50))
        assertContentEquals(text.copyOf(50), reader.readPrefix(deflated, 50))
        var streamed = 0L
        reader.read(deflated, ByteSink { _, _, length -> streamed += length })
        assertEquals(text.size.toLong(), streamed)
    }

    @Test
    fun aShortDescriptorEntryReadsWholeThroughAPrefix() {
        val zip = RawZip.build(
            RawZip.Entry("s", hello, descriptor = RawZip.Descriptor.SIGNED, hideSizes = true),
            RawZip.Entry("d", hello, method = 8, descriptor = RawZip.Descriptor.SIGNED, hideSizes = true),
        )
        val reader = ZipReader(ByteArrayRandomAccessSource(zip))
        val (s, d) = reader.entries()
        assertContentEquals(hello, reader.readPrefix(s, 1000))
        assertContentEquals(hello, reader.readPrefix(d, 1000))
    }

    @Test
    fun aDamagedDescriptorEntryFailsItsCrc() {
        val zip = RawZip.build(
            RawZip.Entry("stored.txt", text, descriptor = RawZip.Descriptor.SIGNED, hideSizes = true, crc = Crc32.of(text) xor 4),
            RawZip.Entry("deflated.txt", text, method = 8, descriptor = RawZip.Descriptor.BARE, hideSizes = true, crc = Crc32.of(text) xor 4),
        )
        val reader = ZipReader(ByteArrayRandomAccessSource(zip))
        val (stored, deflated) = reader.entries()
        assertTrue(assertFailsWith<ArchiveFormatException> { reader.read(stored) }.message!!.contains("CRC-32"))
        assertTrue(assertFailsWith<ArchiveFormatException> { reader.read(deflated) }.message!!.contains("CRC-32"))
        assertFalse(reader.verify(stored))
        val lenient = ZipReader(ByteArrayRandomAccessSource(zip), ArchiveOpenOptions { crcPolicy = CrcPolicy.LENIENT })
        assertContentEquals(text, lenient.read(lenient.entries()[0]))
    }

    @Test
    fun aDecoyDescriptorInsideStoredDataIsSkipped() {
        // At offset 16, a signed descriptor whose sizes say 16 but whose CRC-32 is wrong.
        val data = ByteArray(16) { 'x'.code.toByte() } +
            byteArrayOf(0x50, 0x4B, 0x07, 0x08, 1, 2, 3, 4, 16, 0, 0, 0, 16, 0, 0, 0) + "tail of the data".encodeToByteArray()
        val zip = RawZip.build(RawZip.Entry("s", data, descriptor = RawZip.Descriptor.SIGNED, hideSizes = true))
        val reader = ZipReader(ByteArrayRandomAccessSource(zip))
        assertContentEquals(data, reader.read(reader.entries().single()))
    }

    @Test
    fun aZip64DescriptorHasEightByteSizes() {
        val zip = RawZip.build(RawZip.Entry("big", text, method = 8, descriptor = RawZip.Descriptor.SIGNED, hideSizes = true, zip64 = true))
        val reader = ZipReader(ByteArrayRandomAccessSource(zip))
        assertContentEquals(text, reader.read(reader.entries().single()))
    }

    @Test
    fun aDescriptorEntryReadsNothingPastTheNextLocalHeader() {
        val zip = RawZip.build(
            RawZip.Entry("s", text, descriptor = RawZip.Descriptor.SIGNED, hideSizes = true),
            RawZip.Entry("d", text, method = 8, descriptor = RawZip.Descriptor.BARE, hideSizes = true),
            RawZip.Entry("last", hello),
        )
        val counting = CountingSource(zip)
        val reader = ZipReader(counting)
        val (s, d, _) = reader.entries()
        val offsets = RawZip.localOffsets(zip)
        counting.highest = 0
        reader.read(s)
        assertTrue(counting.highest <= offsets[1], "read to ${counting.highest}, next header at ${offsets[1]}")
        counting.highest = 0
        reader.read(d)
        assertTrue(counting.highest <= offsets[2], "read to ${counting.highest}, next header at ${offsets[2]}")
    }

    @Test
    fun aStoredScanStopsAtTheOutputLimit() {
        val zip = RawZip.build(RawZip.Entry("s", text, descriptor = RawZip.Descriptor.SIGNED, hideSizes = true))
        val reader = ZipReader(ByteArrayRandomAccessSource(zip))
        assertFailsWith<io.github.yuroyami.archivekodec.security.DecompressionBombException> {
            reader.read(reader.entries().single(), ArchiveLimits { maxUncompressedBytes = 1000 })
        }
    }

    // ---- Names ----

    @Test
    fun theMixedDecoderKeepsUtf8AndFallsBackToCp437() {
        val decoder = ArchiveNameDecoder.UTF8_OR_CP437
        assertEquals("café", decoder.decode("café".encodeToByteArray()))
        assertEquals("é", decoder.decode(byteArrayOf(0x82.toByte())))
        for (d in listOf(ArchiveNameDecoder.UTF8, ArchiveNameDecoder.CP437, decoder)) assertEquals("plain/name.txt", d.decode("plain/name.txt".encodeToByteArray()))

        val zip = RawZip.build(
            RawZip.Entry("café.xhtml".encodeToByteArray(), hello),
            RawZip.Entry(byteArrayOf(0x82.toByte(), 0x2E, 0x74), hello),
            RawZip.Entry("ü.txt".encodeToByteArray(), hello, flags = 0x800),
        )
        val names = ZipReader(ByteArrayRandomAccessSource(zip), ArchiveOpenOptions { nameDecoder = ArchiveNameDecoder.UTF8_OR_CP437 })
            .entries().map { it.name }
        assertEquals(listOf("café.xhtml", "é.t", "ü.txt"), names)
        assertEquals("caf├⌐.xhtml", ZipReader(ByteArrayRandomAccessSource(zip)).entries()[0].name, "CP437 stays the default")
        val opened = ArchiveKodec.open(zip, FormatRegistry.DEFAULT, options = ArchiveOpenOptions { nameDecoder = ArchiveNameDecoder.UTF8_OR_CP437 })
        assertEquals("café.xhtml", opened.entries()[0].name)
    }

    // ---- End record ----

    @Test
    fun anEndRecordInsideTheCommentIsNotTakenForTheRealOne() {
        val plain = RawZip.build(RawZip.Entry("a.txt", hello))
        // A 22-byte comment that frames like an empty end record: a signature, zeros, comment length 0.
        val zip = plain.copyOf(plain.size + 22)
        zip[plain.size - 2] = 22
        zip[zip.size - 22] = 0x50; zip[zip.size - 21] = 0x4B; zip[zip.size - 20] = 0x05; zip[zip.size - 19] = 0x06
        val reader = ZipReader(ByteArrayRandomAccessSource(zip))
        assertContentEquals(hello, reader.read(reader.entries().single()))
        // An empty archive is only its end record, and still opens.
        assertTrue(ZipReader(ByteArrayRandomAccessSource(zip.copyOfRange(zip.size - 22, zip.size))).entries().isEmpty())
    }

    @Test
    fun anArchiveWithoutACommentListsWithoutReadingItsData() {
        val big = ByteArray(200_000) { (it * 7).toByte() }
        val zip = RawZip.build(RawZip.Entry("a.txt", hello), RawZip.Entry("big.bin", big))
        // Every read inside the big entry's data fails, as a damaged disk would.
        val dataStart = 30L + 5 + hello.size + 30 + 7
        val source = object : RandomAccessSource {
            override val size: Long get() = zip.size.toLong()
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                if (offset < dataStart + big.size && offset + length > dataStart) throw ArchiveFormatException("disk gone at $offset", offset)
                val n = minOf(length.toLong(), zip.size - offset).toInt()
                zip.copyInto(into, intoOffset, offset.toInt(), offset.toInt() + n)
                return n
            }
        }
        val reader = ZipReader(source)
        assertEquals(listOf("a.txt", "big.bin"), reader.entries().map { it.name })
        assertContentEquals(hello, reader.read(reader.entries()[0]))
        assertFailsWith<ArchiveFormatException> { reader.read(reader.entries()[1]) }
    }

    /** A source that counts the bytes read and the highest offset reached. */
    private class CountingSource(private val bytes: ByteArray) : RandomAccessSource {
        var bytesRead = 0L
        var highest = 0L
        override val size: Long get() = bytes.size.toLong()
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            val n = minOf(length.toLong(), bytes.size - offset).toInt()
            if (n <= 0) return -1
            bytes.copyInto(into, intoOffset, offset.toInt(), offset.toInt() + n)
            bytesRead += n
            highest = maxOf(highest, offset + n)
            return n
        }
    }
}

/** Hand-built ZIP files, for header shapes the writer does not make. */
internal object RawZip {
    enum class Descriptor { SIGNED, BARE }

    class Entry(
        val name: ByteArray,
        val data: ByteArray,
        val method: Int = 0,
        val flags: Int = 0,
        val descriptor: Descriptor? = null,
        val hideSizes: Boolean = false,
        val crc: Long = Crc32.of(data),
        val zip64: Boolean = false,
    ) {
        constructor(
            name: String, data: ByteArray, method: Int = 0, flags: Int = 0, descriptor: Descriptor? = null,
            hideSizes: Boolean = false, crc: Long = Crc32.of(data), zip64: Boolean = false,
        ) : this(name.encodeToByteArray(), data, method, flags, descriptor, hideSizes, crc, zip64)
    }

    fun build(vararg entries: Entry): ByteArray {
        val out = mutableListOf<Byte>()
        fun le(value: Long, bytes: Int) { for (i in 0 until bytes) out += (value ushr (8 * i)).toByte() }
        val central = mutableListOf<Byte>()
        fun cle(value: Long, bytes: Int) { for (i in 0 until bytes) central += (value ushr (8 * i)).toByte() }
        for (e in entries) {
            val offset = out.size.toLong()
            val payload = if (e.method == 8) Deflate.encode(e.data) else e.data
            val flags = e.flags or (if (e.descriptor != null) 0x08 else 0)
            val crc = if (e.hideSizes) 0L else e.crc
            val csize = if (e.hideSizes) 0L else payload.size.toLong()
            val usize = if (e.hideSizes) 0L else e.data.size.toLong()
            val extra = if (e.zip64) byteArrayOf(1, 0, 16, 0) + ByteArray(16) else ByteArray(0)
            le(0x04034b50, 4); le(20, 2); le(flags.toLong(), 2); le(e.method.toLong(), 2); le(0, 2); le(0x21, 2)
            le(crc, 4); le(csize, 4); le(usize, 4); le(e.name.size.toLong(), 2); le(extra.size.toLong(), 2)
            out += e.name.toList(); out += extra.toList(); out += payload.toList()
            if (e.descriptor != null) {
                if (e.descriptor == Descriptor.SIGNED) le(0x08074b50, 4)
                le(e.crc, 4)
                le(payload.size.toLong(), if (e.zip64) 8 else 4)
                le(e.data.size.toLong(), if (e.zip64) 8 else 4)
            }
            cle(0x02014b50, 4); cle(20, 2); cle(20, 2); cle(flags.toLong(), 2); cle(e.method.toLong(), 2); cle(0, 2); cle(0x21, 2)
            cle(crc, 4); cle(csize, 4); cle(usize, 4); cle(e.name.size.toLong(), 2); cle(0, 2); cle(0, 2)
            cle(0, 2); cle(0, 2); cle(0, 4); cle(offset, 4)
            central += e.name.toList()
        }
        val cdOffset = out.size.toLong()
        out += central
        le(0x06054b50, 4); le(0, 2); le(0, 2); le(entries.size.toLong(), 2); le(entries.size.toLong(), 2)
        le(central.size.toLong(), 4); le(cdOffset, 4); le(0, 2)
        return out.toByteArray()
    }

    /** The local header offsets from the central directory of a file [build] made. */
    fun localOffsets(zip: ByteArray): List<Long> {
        fun u32(at: Int) = (0 until 4).fold(0L) { v, i -> v or ((zip[at + i].toLong() and 0xFF) shl (8 * i)) }
        fun u16(at: Int) = (zip[at].toInt() and 0xFF) or ((zip[at + 1].toInt() and 0xFF) shl 8)
        val end = zip.size - 22
        var p = u32(end + 16).toInt()
        val offsets = mutableListOf<Long>()
        repeat(u16(end + 10)) {
            offsets += u32(p + 42)
            p += 46 + u16(p + 28) + u16(p + 30) + u16(p + 32)
        }
        return offsets
    }
}
