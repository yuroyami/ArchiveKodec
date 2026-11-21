package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipStreamWriter
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.StreamParity
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** ZipStreamWriter against ZipReader and ZipWriter. */
class ZipStreamTest {
    private fun stream(comment: String? = null, write: ZipStreamWriter.() -> Unit): ByteArray {
        val out = ByteArrayBuilder(1024)
        val writer = ZipStreamWriter { b, o, n -> out.append(b, o, n) }
        writer.write()
        if (comment == null) writer.finish() else writer.finish(comment)
        return out.takeByteArray()
    }

    private val specs = listOf(
        ZipWriter.FileSpec("text.txt", StreamParity.text(300_000, 1)) { lastModifiedEpochSeconds = 1_700_000_001; unixMode = 0x81A4; comment = "deflated" },
        ZipWriter.FileSpec("stored.bin", StreamParity.text(70_000, 2), ZipMethod.STORED) { unixMode = 0x81ED },
        ZipWriter.FileSpec("empty", ByteArray(0)),
        ZipWriter.FileSpec("empty-stored", ByteArray(0), ZipMethod.STORED) { comment = "nothing" },
        ZipWriter.FileSpec("dir/", ByteArray(0), ZipMethod.STORED) { unixMode = 0x41ED; lastModifiedEpochSeconds = 1_000_000_000 },
        ZipWriter.FileSpec("dir/héllo wörld ✓ 日本.txt", "non-ASCII".encodeToByteArray()) { comment = "commentaire é" },
        ZipWriter.FileSpec("fast.txt", StreamParity.text(5000, 3)) { deflateOptions = DeflateOptions { level = 1 } },
        ZipWriter.FileSpec("big.txt", StreamParity.text(2_500_000, 4)),
    )

    /** Write [specs] through the stream writer, reading each from a source of [step] bytes per read. */
    private fun streamSpecs(specs: List<ZipWriter.FileSpec>, step: Int, comment: String? = null): ByteArray = stream(comment) {
        for (s in specs) {
            if (s.name.endsWith('/')) putDirectory(s.name, s.lastModifiedEpochSeconds, s.unixMode, s.comment)
            else putFile(s.name, StreamParity.trickle(s.data, step), s.method, s.lastModifiedEpochSeconds, s.unixMode, s.comment, s.deflateOptions)
        }
    }

    /** Each entry's listed metadata, the fields both writers set the same way. */
    private fun listing(zip: ByteArray): List<String> = ZipReader(ByteArrayRandomAccessSource(zip)).use { reader ->
        reader.entries().map { e ->
            "${e.name} size=${e.size} crc=${e.crc32} time=${e.lastModifiedEpochSeconds} mode=${e.unixMode} " +
                "comment=${e.comment} method=${e.zipMethod} dir=${e.isDirectory}"
        }
    }

    @Test
    fun entriesRoundTripThroughZipReader() {
        for (step in listOf(1, 4093, 65_536)) {
            val selected = if (step == 1) specs.filter { it.data.size < 100_000 } else specs
            val zip = streamSpecs(selected, step)
            ZipReader(ByteArrayRandomAccessSource(zip)).use { reader ->
                val entries = reader.entries()
                assertEquals(selected.map { it.name }, entries.map { it.name })
                for ((spec, entry) in selected.zip(entries)) {
                    assertEquals(spec.data.size.toLong(), entry.size, spec.name)
                    assertEquals(Crc32.of(spec.data), entry.crc32, spec.name)
                    assertEquals(spec.method.code, entry.zipMethod, spec.name)
                    assertEquals(spec.comment, entry.comment, spec.name)
                    assertEquals(spec.unixMode, entry.unixMode, spec.name)
                    assertEquals(spec.lastModifiedEpochSeconds ?: 315_532_800L, entry.lastModifiedEpochSeconds, spec.name)
                    assertEquals(spec.name.endsWith('/'), entry.isDirectory, spec.name)
                    assertContentEquals(spec.data, reader.read(entry), spec.name)
                    assertTrue(reader.verify(entry), spec.name)
                }
            }
        }
    }

    @Test
    fun bothWritersListTheSameEntries() {
        assertEquals(listing(ZipWriter.write(specs)), listing(streamSpecs(specs, 65_536)))
        // Without data to stream, the two write the same bytes: empty files and directories need no descriptor.
        val dataless = specs.filter { it.data.isEmpty() }
        assertContentEquals(ZipWriter.write(dataless, "archive é"), streamSpecs(dataless, 1, "archive é"))
        assertContentEquals(ZipWriter.write(emptyList()), stream {})
    }

    @Test
    fun filesWithDataUseADataDescriptor() {
        val data = "hello, descriptor".encodeToByteArray()
        for (method in ZipMethod.entries) {
            val zip = stream { putFile("a.txt", ByteSource.of(data), method) }
            assertEquals(0x0808, readU16Le(zip, 6), "$method flags")
            assertEquals(0L, readU32Le(zip, 14), "$method local CRC-32")
            assertEquals(0L, readU32Le(zip, 18), "$method local compressed size")
            assertEquals(0L, readU32Le(zip, 22), "$method local size")
            val cd = readU32Le(zip, zip.size - 6).toInt()
            val compressed = readU32Le(zip, cd + 20)
            val descriptor = 30 + 5 + compressed.toInt() // "a.txt", no extra field without a time
            assertEquals(0x08074b50L, readU32Le(zip, descriptor), "$method descriptor signature")
            assertEquals(Crc32.of(data), readU32Le(zip, descriptor + 4))
            assertEquals(compressed, readU32Le(zip, descriptor + 8))
            assertEquals(data.size.toLong(), readU32Le(zip, descriptor + 12))
            assertEquals(cd, descriptor + 16, "$method: the central directory follows the 16-byte descriptor")
            assertEquals(Crc32.of(data), readU32Le(zip, cd + 16))
            assertEquals(20, readU16Le(zip, cd + 6), "$method version needed")
        }
    }

    @Test
    fun moreThan65535EntriesUseZip64EndRecords() {
        val count = 65_540
        val zip = stream("many") {
            for (i in 0 until count) {
                if (i % 1000 == 0) putDirectory("d$i") else putFile("f$i", ByteSource.of(byteArrayOf(i.toByte(), (i shr 8).toByte())), ZipMethod.STORED)
            }
        }
        val eocd = zip.size - 22 - 4
        assertEquals(0x06054b50L, readU32Le(zip, eocd))
        assertEquals(0xFFFF, readU16Le(zip, eocd + 10))
        assertEquals(0x07064b50L, readU32Le(zip, eocd - 20))
        ZipReader(ByteArrayRandomAccessSource(zip), ArchiveLimits { maxEntries = count }).use { reader ->
            val entries = reader.entries()
            assertEquals(count, entries.size)
            assertEquals("d0/", entries[0].name)
            assertEquals("f65539", entries.last().name)
            assertEquals(byteArrayOf(0x03, 0x00).toList(), reader.read(entries.last()).toList())
            assertEquals(byteArrayOf(0x01, 0x10).toList(), reader.read(reader.entry("f4097")!!).toList())
        }
    }

    @Test
    fun badNamesAndOtherArgumentsAreRefusedBeforeAnythingIsWritten() {
        val out = ByteArrayBuilder(64)
        val writer = ZipStreamWriter { b, o, n -> out.append(b, o, n) }
        val data = ByteSource.of(ByteArray(1))
        for (name in listOf("/abs", "a\\b", "../up", "a/../b", "C:x", "ctl\u0001", "bad\uD800", "x".repeat(65_536))) {
            assertFailsWith<ArchiveFormatException>(name.take(10)) { writer.putFile(name, data) }
            assertFailsWith<ArchiveFormatException>(name.take(10)) { writer.putDirectory(name) }
            assertFailsWith<ArchiveFormatException>(name.take(10)) { ZipWriter.write(listOf(ZipWriter.FileSpec(name, ByteArray(1)))) }
        }
        assertFailsWith<IllegalArgumentException> { writer.putFile("dir/", data) }
        assertFailsWith<IllegalArgumentException> { writer.putFile("a", data, unixMode = 0x10000) }
        assertFailsWith<IllegalArgumentException> { writer.putFile("a", data, comment = "x".repeat(65_536)) }
        assertFailsWith<IllegalArgumentException> { writer.putDirectory("d", comment = "\uD800") }
        assertFailsWith<IllegalArgumentException> { writer.putFile("a", data, deflateOptions = DeflateOptions { dictionary = ByteArray(4) }) }
        assertFailsWith<IllegalArgumentException> { writer.finish("x".repeat(65_536)) }
        assertEquals(0, out.size)
        // A source that breaks its contract is refused rather than looped on.
        assertFailsWith<ArchiveFormatException> { writer.putFile("zero", ByteSource { _, _, _ -> 0 }) }
    }

    @Test
    fun putsAfterFinishFailAndASecondFinishDoesNothing() {
        val out = ByteArrayBuilder(64)
        val writer = ZipStreamWriter { b, o, n -> out.append(b, o, n) }
        writer.putFile("a", ByteSource.of("a".encodeToByteArray()))
        writer.finish("first")
        val size = out.size
        assertFailsWith<IllegalStateException> { writer.putFile("b", ByteSource.of(ByteArray(1))) }
        assertFailsWith<IllegalStateException> { writer.putDirectory("c") }
        writer.finish()
        writer.finish("second")
        assertEquals(size, out.size)
        val zip = out.takeByteArray()
        assertEquals("first", zip.copyOfRange(zip.size - 5, zip.size).decodeToString())
        ZipReader(ByteArrayRandomAccessSource(zip)).use { r -> assertEquals("a", r.read(r.entries().single()).decodeToString()) }
        assertNull(ZipReader(ByteArrayRandomAccessSource(zip)).use { it.entry("b") })
    }

    /** A sink that keeps what it takes and throws once it has taken [limit] bytes. */
    private class FailingSink(private val limit: Long = Long.MAX_VALUE) : io.github.yuroyami.archivekodec.codec.ByteSink {
        val out = ByteArrayBuilder(1024)
        var failAfter = limit
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (out.size + length > failAfter) throw ArchiveException("sink full")
            out.append(bytes, offset, length)
        }
    }

    /** After [failure], every call rethrows it and the sink takes nothing more. */
    private fun assertStopped(writer: ZipStreamWriter, sink: FailingSink, failure: Throwable) {
        val size = sink.out.size
        sink.failAfter = Long.MAX_VALUE
        assertSame(failure, assertFailsWith<Throwable> { writer.putFile("next", ByteSource.of(ByteArray(3))) })
        assertSame(failure, assertFailsWith<Throwable> { writer.putFile("empty", ByteSource.of(ByteArray(0))) })
        assertSame(failure, assertFailsWith<Throwable> { writer.putDirectory("dir") })
        assertSame(failure, assertFailsWith<Throwable> { writer.finish() })
        assertSame(failure, assertFailsWith<Throwable> { writer.finish("comment") })
        assertEquals(size, sink.out.size)
    }

    @Test
    fun aSourceThatFailsPartWayStopsTheWriter() {
        val sink = FailingSink()
        val writer = ZipStreamWriter(sink)
        writer.putFile("ok", ByteSource.of("ok".encodeToByteArray()))
        var reads = 0
        val broken = ArchiveException("source broke")
        val source = ByteSource { into, offset, length ->
            if (++reads == 2) throw broken
            into.fill(7, offset, offset + minOf(length, 100)); minOf(length, 100)
        }
        assertSame(broken, assertFailsWith<ArchiveException> { writer.putFile("partial", source) })
        assertStopped(writer, sink, broken)
    }

    @Test
    fun aSinkThatFailsPartWayThroughAnEntryStopsTheWriter() {
        for (method in ZipMethod.entries) {
            val sink = FailingSink(limit = 100)
            val writer = ZipStreamWriter(sink)
            val failure = assertFailsWith<ArchiveException> { writer.putFile("big", ByteSource.of(StreamParity.text(200_000, 5)), method) }
            assertEquals("sink full", failure.message)
            assertStopped(writer, sink, failure)
        }
        val sink = FailingSink(limit = 10)
        val writer = ZipStreamWriter(sink)
        val failure = assertFailsWith<ArchiveException> { writer.putDirectory("a directory name") }
        assertStopped(writer, sink, failure)
    }

    @Test
    fun aSinkThatFailsDuringFinishStopsTheWriter() {
        val sink = FailingSink()
        val writer = ZipStreamWriter(sink)
        writer.putFile("a", ByteSource.of("abc".encodeToByteArray()))
        sink.failAfter = sink.out.size + 10L
        val failure = assertFailsWith<ArchiveException> { writer.finish() }
        // A second finish does not return quietly as if the archive were complete.
        assertStopped(writer, sink, failure)
    }

    @Test
    fun aSourceThatFailsOnItsFirstReadLeavesTheWriterUsable() {
        val sink = FailingSink()
        val writer = ZipStreamWriter(sink)
        writer.putFile("first", ByteSource.of("1".encodeToByteArray()))
        val size = sink.out.size
        assertFailsWith<ArchiveException> { writer.putFile("never", ByteSource { _, _, _ -> throw ArchiveException("no data") }) }
        assertEquals(size, sink.out.size)
        writer.putFile("second", ByteSource.of("2".encodeToByteArray()))
        writer.finish()
        val zip = sink.out.takeByteArray()
        ZipReader(ByteArrayRandomAccessSource(zip)).use { r ->
            assertEquals(listOf("first", "second"), r.entries().map { it.name })
            assertEquals(listOf("1", "2"), r.entries().map { r.read(it).decodeToString() })
        }
    }
}
