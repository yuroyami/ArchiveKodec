package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ar.ArStreamReader
import io.github.yuroyami.archivekodec.archive.ar.ArStreamWriter
import io.github.yuroyami.archivekodec.archive.cpio.CpioStreamReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioStreamWriter
import io.github.yuroyami.archivekodec.archive.tar.TarStreamReader
import io.github.yuroyami.archivekodec.archive.tar.TarStreamSink
import io.github.yuroyami.archivekodec.archive.tar.TarStreamVisitor
import io.github.yuroyami.archivekodec.archive.tar.TarStreamWriter
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.security.ArchivePathException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The forward-only stream readers and writers are fail-stop: after a failure part-way they
 * cannot know where the stream stands, so every later call rethrows the first failure. Each
 * archive here is built by hand; the refused member's data is itself a valid header.
 */
class StreamFailStopTest {
    private fun transient() = ArchiveIOException("transient failure", IllegalStateException("flaky"))

    /** [bytes], at most [step] bytes per read, throwing once when a read starts at [failAt]. */
    private fun failingOnceAt(bytes: ByteArray, failAt: Int, step: Int = 4096): ByteSource {
        var position = 0
        var failed = false
        return ByteSource { into, offset, length ->
            if (!failed && position == failAt) {
                failed = true
                throw transient()
            }
            if (position == bytes.size) -1 else {
                val limit = if (!failed && position < failAt) failAt - position else Int.MAX_VALUE
                val n = minOf(length, step, bytes.size - position, limit)
                bytes.copyInto(into, offset, position, position + n)
                position += n
                n
            }
        }
    }

    // ---- TAR ----

    private fun pad(data: ByteArray) = data + ByteArray((512 - data.size % 512) % 512)

    private fun tarHeader(name: String, size: Int): ByteArray = ByteArray(512).also { h ->
        fun put(offset: Int, text: String) = text.encodeToByteArray().copyInto(h, offset)
        put(0, name); put(100, "0000644"); put(124, size.toString(8).padStart(11, '0'))
        put(136, "00000000000"); h[156] = '0'.code.toByte(); put(257, "ustar"); put(263, "00")
        put(148, "        ")
        put(148, h.sumOf { it.toInt() and 0xFF }.toString(8).padStart(6, '0') + "\u0000 ")
    }

    private fun tarFile(name: String, data: ByteArray) = tarHeader(name, data.size) + pad(data)

    /** A member "../evil" whose data is a whole member, "smuggled.txt". */
    private val evilTar = tarFile("../evil", tarFile("smuggled.txt", "owned".encodeToByteArray())) + ByteArray(1024)

    @Test
    fun aRefusedTarEntryStopsTheReader() {
        val reader = TarStreamReader(ByteSource.of(evilTar))
        val first = assertFailsWith<ArchivePathException> { reader.next() }
        assertSame(first, assertFailsWith<ArchivePathException> { reader.next() })
        assertSame(first, assertFailsWith<ArchivePathException> { reader.read({ _, _, _ -> }) })
        assertSame(first, assertFailsWith<ArchivePathException> { reader.data().read(ByteArray(8), 0, 8) })
    }

    @Test
    fun aRefusedTarEntryStopsThePushSink() {
        val seen = ArrayList<String>()
        val sink = TarStreamSink(object : TarStreamVisitor {
            override fun onEntry(entry: ArchiveEntry) { seen += entry.name }
            override fun onData(entry: ArchiveEntry, bytes: ByteArray, offset: Int, length: Int) {}
        })
        val first = assertFailsWith<ArchivePathException> { sink.write(evilTar, 0, 512) }
        assertSame(first, assertFailsWith<ArchivePathException> { sink.write(evilTar, 512, evilTar.size - 512) })
        assertSame(first, assertFailsWith<ArchivePathException> { sink.finish() })
        assertEquals(emptyList(), seen)
    }

    @Test
    fun aTarSkipCutShortStopsTheReader() {
        val tar = tarFile("a.bin", ByteArray(1000)) + tarFile("b.txt", "hello".encodeToByteArray()) + ByteArray(1024)
        val reader = TarStreamReader(failingOnceAt(tar, 512 + 600))
        assertEquals("a.bin", reader.next()!!.name)
        val first = assertFailsWith<ArchiveIOException> { reader.next() }
        assertSame(first, assertFailsWith<ArchiveIOException> { reader.next() })
    }

    // ---- ar ----

    private fun arHeader(name: String, size: Int): ByteArray =
        (name.padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "100644".padEnd(8) + size.toString().padEnd(10) + "`\n").encodeToByteArray()

    private fun arMember(name: String, data: ByteArray) = arHeader(name, data.size) + data + (if (data.size % 2 == 1) byteArrayOf(10) else ByteArray(0))

    private val arMagic = "!<arch>\n".encodeToByteArray()

    @Test
    fun aRefusedArMemberStopsTheReader() {
        val ar = arMagic + arMember("../evil/", arMember("smuggled/", "owned".encodeToByteArray()))
        val reader = ArStreamReader(ByteSource.of(ar))
        val first = assertFailsWith<ArchivePathException> { reader.next() }
        assertSame(first, assertFailsWith<ArchivePathException> { reader.next() })
        assertSame(first, assertFailsWith<ArchivePathException> { reader.data().read(ByteArray(8), 0, 8) })
    }

    @Test
    fun aRefusedArMagicStaysRefused() {
        val member = arMember("a.txt/", "hello".encodeToByteArray())
        val thin = ArStreamReader(ByteSource.of("!<thin>\n".encodeToByteArray() + member))
        val refused = assertFailsWith<ArchiveUnsupportedException> { thin.next() }
        assertSame(refused, assertFailsWith<ArchiveUnsupportedException> { thin.next() })
        val bad = ArStreamReader(ByteSource.of("!<arcz>\n".encodeToByteArray() + member))
        val invalid = assertFailsWith<ArchiveFormatException> { bad.next() }
        assertSame(invalid, assertFailsWith<ArchiveFormatException> { bad.next() })
    }

    @Test
    fun anArSkipCutShortStopsTheReader() {
        val ar = arMagic + arMember("a.bin/", ByteArray(1000)) + arMember("b.txt/", "hello".encodeToByteArray())
        val reader = ArStreamReader(failingOnceAt(ar, 8 + 60 + 400))
        assertEquals("a.bin", reader.next()!!.name)
        val first = assertFailsWith<ArchiveIOException> { reader.next() }
        assertSame(first, assertFailsWith<ArchiveIOException> { reader.next() })
    }

    @Test
    fun aHugeArNameTableIsRefusedWithoutAllocatingIt() {
        // 68 bytes declaring a 2,000,000,000-byte `//` table.
        val claim = arMagic + arHeader("//", 2_000_000_000)
        assertEquals(68, claim.size)
        assertFailsWith<ArchiveFormatException> { ArStreamReader(ByteSource.of(claim)).next() }
        // A table that is present but larger than 16 MiB is refused too; one below it reads.
        val big = ByteArray((16 shl 20) + 2) { 10 }
        assertFailsWith<ArchiveFormatException> { ArStreamReader(ByteSource.of(arMagic + arMember("//", big))).next() }
        val table = "a_rather_long_member_name.txt/\n".encodeToByteArray()
        val ok = arMagic + arMember("//", table) + arMember("/0", "hi".encodeToByteArray())
        assertEquals("a_rather_long_member_name.txt", ArStreamReader(ByteSource.of(ok)).next()!!.name)
    }

    // ---- cpio ----

    private fun pad4(bytes: ByteArray) = bytes + ByteArray((4 - bytes.size % 4) % 4)

    private fun newc(name: String, data: ByteArray): ByteArray {
        val nameBytes = name.encodeToByteArray() + 0
        val fields = longArrayOf(1, 0x81A4, 0, 0, 1, 0, data.size.toLong(), 0, 0, 0, 0, nameBytes.size.toLong(), 0)
        val head = ("070701" + fields.joinToString("") { it.toString(16).padStart(8, '0') }).encodeToByteArray()
        return pad4(head + nameBytes) + pad4(data)
    }

    private val trailer = newc("TRAILER!!!", ByteArray(0))

    @Test
    fun aRefusedCpioRecordStopsTheReader() {
        val cpio = newc("../evil", newc("smuggled", "owned".encodeToByteArray())) + trailer
        val reader = CpioStreamReader(ByteSource.of(cpio))
        val first = assertFailsWith<ArchivePathException> { reader.next() }
        assertSame(first, assertFailsWith<ArchivePathException> { reader.next() })
        assertSame(first, assertFailsWith<ArchivePathException> { reader.read({ _, _, _ -> }) })
    }

    @Test
    fun aCpioPaddingSkipCutShortStopsTheReader() {
        // "a" has a 1-byte body at 112, then 3 bytes of padding; fail in the middle of them.
        val cpio = newc("a", byteArrayOf(1)) + newc("b", "hello".encodeToByteArray()) + trailer
        val reader = CpioStreamReader(failingOnceAt(cpio, 114, step = 1))
        assertEquals("a", reader.next()!!.name)
        val first = assertFailsWith<ArchiveIOException> { reader.next() }
        assertSame(first, assertFailsWith<ArchiveIOException> { reader.next() })
    }

    // ---- Size limits ----

    @Test
    fun everyWayToTakeAnEntrysDataAnswersToTheSizeLimit() {
        val tight = io.github.yuroyami.archivekodec.security.ArchiveLimits { maxUncompressedBytes = 1000 }
        val bomb = io.github.yuroyami.archivekodec.security.DecompressionBombException::class
        val tar = tarFile("big.bin", ByteArray(2000)) + ByteArray(1024)
        TarStreamReader(ByteSource.of(tar), tight).also { it.next() }.let { r ->
            assertFailsWith(bomb) { r.data().read(ByteArray(10), 0, 10) }
        }
        val seen = ArrayList<String>()
        val sink = TarStreamSink(object : TarStreamVisitor {
            override fun onEntry(entry: ArchiveEntry) { seen += entry.name }
            override fun onData(entry: ArchiveEntry, bytes: ByteArray, offset: Int, length: Int) { seen += "data" }
        }, tight)
        assertFailsWith(bomb) { sink.write(tar, 0, tar.size) }
        assertEquals(emptyList(), seen)
        val ar = arMagic + arMember("big.bin/", ByteArray(2000))
        ArStreamReader(ByteSource.of(ar), tight).also { it.next() }.let { r ->
            assertFailsWith(bomb) { r.data().read(ByteArray(10), 0, 10) }
        }
        val cpio = newc("big.bin", ByteArray(2000)) + trailer
        CpioStreamReader(ByteSource.of(cpio), tight).also { it.next() }.let { r ->
            assertFailsWith(bomb) { r.data().read(ByteArray(10), 0, 10) }
        }
        // Within the limit, the same entries stream.
        val roomy = io.github.yuroyami.archivekodec.security.ArchiveLimits { maxUncompressedBytes = 2000 }
        TarStreamReader(ByteSource.of(tar), roomy).also { it.next() }.let { r -> assertEquals(10, r.data().read(ByteArray(10), 0, 10)) }
    }

    // ---- Writers ----

    private class Collector : ByteSink {
        val out = ByteArrayBuilder(1024)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.append(bytes, offset, length)
    }

    /** One stream writer, driven the same way for each format. */
    private class Writer(val name: String, val sink: ByteSink, val put: (String, Long, ByteSource) -> Unit, val finish: () -> Unit)

    private fun writers(sink: ByteSink): List<Writer> = listOf(
        TarStreamWriter(sink).let { w -> Writer("TAR", sink, { n, s, d -> w.putFile(n, s, d) }, w::finish) },
        ArStreamWriter(sink).let { w -> Writer("ar", sink, { n, s, d -> w.putFile(n, s, d) }, w::finish) },
        CpioStreamWriter(sink).let { w -> Writer("cpio", sink, { n, s, d -> w.putFile(n, s, d) }, w::finish) },
    )

    /** A sink that throws once it has taken [limit] bytes; [count] says how many reached it. */
    private class FailingSink(private val limit: Int) : ByteSink {
        var count = 0
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (count + length > limit) throw ArchiveIOException("sink full", IllegalStateException("full"))
            count += length
        }
    }

    @Test
    fun aSourceThatReturnsZeroOrTooMuchIsRefused() {
        for (result in listOf(0, 100_000)) {
            val stuck = ByteSource { _, _, _ -> result }
            for (writer in writers(Collector())) {
                val error = assertFailsWith<ArchiveFormatException>("${writer.name} $result") { writer.put("x.bin", 10, stuck) }
                assertEquals("x.bin", error.entryName, writer.name)
            }
        }
    }

    @Test
    fun aSourceFailingMidEntryStopsTheWriter() {
        for (index in 0 until 3) {
            val sink = Collector()
            val writer = writers(sink)[index]
            writer.put("a.txt", 5, ByteSource.of("hello".encodeToByteArray()))
            var reads = 0
            val flaky = ByteSource { into, offset, _ -> if (reads++ == 0) { into[offset] = 1; 1 } else throw transient() }
            val first = assertFailsWith<ArchiveIOException>(writer.name) { writer.put("b.bin", 10, flaky) }
            val written = sink.out.size
            assertSame(first, assertFailsWith<ArchiveIOException>(writer.name) { writer.put("c.txt", 2, ByteSource.of(ByteArray(2))) })
            assertSame(first, assertFailsWith<ArchiveIOException>(writer.name) { writer.finish() })
            assertSame(first, assertFailsWith<ArchiveIOException>(writer.name) { writer.finish() })
            assertEquals(written, sink.out.size, writer.name)
        }
    }

    @Test
    fun aShortSourceStopsTheWriter() {
        for (index in 0 until 3) {
            val sink = Collector()
            val writer = writers(sink)[index]
            val first = assertFailsWith<ArchiveFormatException>(writer.name) { writer.put("short.bin", 10, ByteSource.of(ByteArray(4))) }
            val written = sink.out.size
            assertSame(first, assertFailsWith<ArchiveFormatException>(writer.name) { writer.finish() })
            assertEquals(written, sink.out.size, writer.name)
        }
    }

    @Test
    fun aSinkFailingMidEntryStopsTheWriter() {
        for (index in 0 until 3) {
            // The sink takes the first member and part of the second's data, then throws.
            val sink = FailingSink(2000)
            val writer = writers(sink)[index]
            writer.put("a.txt", 5, ByteSource.of("hello".encodeToByteArray()))
            val first = assertFailsWith<ArchiveIOException>(writer.name) { writer.put("b.bin", 5000, ByteSource.of(ByteArray(5000))) }
            val count = sink.count
            assertSame(first, assertFailsWith<ArchiveIOException>(writer.name) { writer.put("c.txt", 2, ByteSource.of(ByteArray(2))) })
            assertSame(first, assertFailsWith<ArchiveIOException>(writer.name) { writer.finish() })
            assertEquals(count, sink.count, writer.name)
        }
    }

    @Test
    fun aFailedFinishStaysFailedAndASuccessfulOneIsDone() {
        for (index in 0 until 3) {
            val failing = writers(FailingSink(0))[index]
            val first = assertFailsWith<ArchiveIOException>(failing.name) { failing.finish() }
            assertSame(first, assertFailsWith<ArchiveIOException>(failing.name) { failing.finish() })
            val sink = Collector()
            val writer = writers(sink)[index]
            writer.finish()
            val size = sink.out.size
            writer.finish()
            assertEquals(size, sink.out.size, writer.name)
        }
    }

    @Test
    fun aRefusedArgumentLeavesTheWriterUsable() {
        for (index in 0 until 3) {
            val sink = Collector()
            val writer = writers(sink)[index]
            assertFailsWith<IllegalArgumentException>(writer.name) { writer.put("a.txt", -1, ByteSource.of(ByteArray(0))) }
            assertFailsWith<ArchiveFormatException>(writer.name) { writer.put("../evil\u0000", 0, ByteSource.of(ByteArray(0))) }
            assertEquals(0, sink.out.size, writer.name)
            writer.put("a.txt", 5, ByteSource.of("hello".encodeToByteArray()))
            writer.finish()
        }
        // What the writers wrote after the refusals reads back.
        val tar = Collector()
        TarStreamWriter(tar).also { w ->
            assertFailsWith<IllegalArgumentException> { w.putFile("a.txt", -1, ByteSource.of(ByteArray(0))) }
            w.putFile("a.txt", 5, ByteSource.of("hello".encodeToByteArray()))
            w.finish()
        }
        val reader = TarStreamReader(ByteSource.of(tar.out.takeByteArray()))
        assertEquals("a.txt", reader.next()!!.name)
        assertNull(reader.next())
    }
}
