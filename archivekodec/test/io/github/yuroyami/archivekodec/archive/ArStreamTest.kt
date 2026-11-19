package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.ar.ArStreamReader
import io.github.yuroyami.archivekodec.archive.ar.ArStreamWriter
import io.github.yuroyami.archivekodec.archive.ar.ArWriter
import io.github.yuroyami.archivekodec.codec.ByteSource
import io.github.yuroyami.archivekodec.codec.StreamParity
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.ArchivePathException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** ArStreamWriter and ArStreamReader against ArWriter, ArReader and GNU ar. */
class ArStreamTest {
    // GNU ar 2.42: `ar rcsD lib.a a_rather_long_object_name.o short.txt e.txt`: a symbol index,
    // a `//` long-name table and members of odd and even sizes.
    private val gnu = Base64.decode(
        "ITxhcmNoPgovICAgICAgICAgICAgICAgMCAgICAgICAgICAgMCAgICAgMCAgICAgMCAgICAgICAxMCAgICAgICAgYAoAAAABAAAA" +
        "qGYALy8gICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgMzAgICAgICAgIGAKYV9yYXRoZXJfbG9u" +
        "Z19vYmplY3RfbmFtZS5vLwoKLzAgICAgICAgICAgICAgIDAgICAgICAgICAgIDAgICAgIDAgICAgIDY0NCAgICAgMTI0MCAgICAg" +
        "IGAKf0VMRgIBAQAAAAAAAAAAAAEAPgABAAAAAAAAAAAAAAAAAAAAAAAAANgBAAAAAAAAAAAAAEAAAAAAAEAADAALAPMPHvpVSInl" +
        "uAEAAABdwwBHQ0M6IChVYnVudHUgMTMuMy4wLTZ1YnVudHUyfjI0LjA0LjEpIDEzLjMuMAAAAAAEAAAAEAAAAAUAAABHTlUAAgAA" +
        "wAQAAAADAAAAAAAAABQAAAAAAAAAAXpSAAF4EAEbDAcIkAEAABwAAAAcAAAAAAAAAA8AAAAARQ4QhgJDDQZGDAcIAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAQAAAAQA8f8AAAAAAAAAAAAAAAAAAAAAAAAAAAMAAQAAAAAAAAAAAAAAAAAAAAAAHQAAABIAAQAA" +
        "AAAAAAAAAA8AAAAAAAAAAGFfcmF0aGVyX2xvbmdfb2JqZWN0X25hbWUuYwBmAAAgAAAAAAAAAAIAAAACAAAAAAAAAAAAAAAALnN5" +
        "bXRhYgAuc3RydGFiAC5zaHN0cnRhYgAudGV4dAAuZGF0YQAuYnNzAC5jb21tZW50AC5ub3RlLkdOVS1zdGFjawAubm90ZS5nbnUu" +
        "cHJvcGVydHkALnJlbGEuZWhfZnJhbWUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAbAAAAAQAAAAYAAAAAAAAAAAAAAAAAAABAAAAAAAAAAA8AAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAA" +
        "AAAAIQAAAAEAAAADAAAAAAAAAAAAAAAAAAAATwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAACcAAAAIAAAA" +
        "AwAAAAAAAAAAAAAAAAAAAE8AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAsAAAAAQAAADAAAAAAAAAAAAAA" +
        "AAAAAABPAAAAAAAAAC4AAAAAAAAAAAAAAAAAAAABAAAAAAAAAAEAAAAAAAAANQAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAfQAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAEUAAAAHAAAAAgAAAAAAAAAAAAAAAAAAAIAAAAAAAAAAIAAAAAAAAAAA" +
        "AAAAAAAAAAgAAAAAAAAAAAAAAAAAAABdAAAAAQAAAAIAAAAAAAAAAAAAAAAAAACgAAAAAAAAADgAAAAAAAAAAAAAAAAAAAAIAAAA" +
        "AAAAAAAAAAAAAAAAWAAAAAQAAABAAAAAAAAAAAAAAAAAAAAAWAEAAAAAAAAYAAAAAAAAAAkAAAAHAAAACAAAAAAAAAAYAAAAAAAA" +
        "AAEAAAACAAAAAAAAAAAAAAAAAAAAAAAAANgAAAAAAAAAYAAAAAAAAAAKAAAAAwAAAAgAAAAAAAAAGAAAAAAAAAAJAAAAAwAAAAAA" +
        "AAAAAAAAAAAAAAAAAAA4AQAAAAAAAB8AAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAEQAAAAMAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAcAEAAAAAAABnAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAHNob3J0LnR4dC8gICAgICAwICAgICAgICAgICAwICAg" +
        "ICAwICAgICA2NDQgICAgIDUgICAgICAgICBgCm9kZAohCmUudHh0LyAgICAgICAgICAwICAgICAgICAgICAwICAgICAwICAgICA2" +
        "NDQgICAgIDUgICAgICAgICBgCmV2ZW4KCg=="
    )

    /** Every member as name, mode, time and data, or the failure's class name. */
    private fun listRandom(archive: ByteArray): String = try {
        ArReader(ByteArrayRandomAccessSource(archive)).use { reader ->
            reader.entries().joinToString("|") { e -> "${e.name} ${e.unixMode} ${e.lastModifiedEpochSeconds} " + reader.read(e).decodeToString() }
        }
    } catch (e: ArchiveException) { e::class.simpleName!! }

    private fun listStreamed(archive: ByteArray, step: Int): String = try {
        val reader = ArStreamReader(StreamParity.trickle(archive, step))
        val out = ArrayList<String>()
        while (true) {
            val e = reader.next() ?: break
            val data = ByteArrayBuilder(64)
            reader.read({ b, o, n -> data.append(b, o, n) })
            out += "${e.name} ${e.unixMode} ${e.lastModifiedEpochSeconds} " + data.takeByteArray().decodeToString()
        }
        assertNull(reader.next())
        out.joinToString("|")
    } catch (e: ArchiveException) { e::class.simpleName!! }

    private fun assertSame(archive: ByteArray, what: String) {
        val expected = listRandom(archive)
        for (step in listOf(1, 61, 4096)) assertEquals(expected, listStreamed(archive, step), "$what, step $step")
    }

    /**
     * Same listing, or a failure on both sides. ArReader checks the whole listing before the
     * first entry, so for a damaged archive it may report a later member's error first.
     */
    private fun assertBothFailOrSame(archive: ByteArray, what: String) {
        val expected = listRandom(archive)
        val failed = expected.endsWith("Exception")
        for (step in listOf(1, 4096)) {
            val actual = listStreamed(archive, step)
            if (failed) assertEquals(true, actual.endsWith("Exception"), "$what, step $step: $actual") else assertEquals(expected, actual, "$what, step $step")
        }
    }

    private val specs = listOf(
        ArWriter.FileSpec("a.txt", "odd!!".encodeToByteArray()),
        ArWriter.FileSpec("a name longer than sixteen bytes.txt", "even".encodeToByteArray()) { modificationTime = 1_700_000_000; mode = 0x8180 },
        ArWriter.FileSpec("empty", ByteArray(0)),
        ArWriter.FileSpec("with space", StreamParity.text(5000, 3)),
    )

    @Test
    fun theStreamReaderListsWhatArReaderLists() {
        assertEquals(listOf("a_rather_long_object_name.o", "short.txt", "e.txt"),
            ArReader(ByteArrayRandomAccessSource(gnu)).use { r -> r.entries().map { it.name } })
        assertSame(gnu, "GNU ar")
        for (style in ArWriter.NameStyle.entries) assertSame(ArWriter.write(specs, style), "$style")
        assertSame(ArWriter.write(emptyList()), "empty")
    }

    @Test
    fun theStreamWriterWritesWhatBothReadersRead() {
        val out = ByteArrayBuilder(1024)
        val writer = ArStreamWriter { b, o, n -> out.append(b, o, n) }
        for (spec in specs) writer.putFile(spec.name, spec.payload.size.toLong(), ByteSource.of(spec.payload), spec.mode, spec.modificationTime)
        writer.finish()
        val archive = out.takeByteArray()
        // The same bytes as ArWriter's BSD style, which is what a streaming writer can produce.
        assertContentEquals(ArWriter.write(specs, ArWriter.NameStyle.BSD), archive)
        assertSame(archive, "stream writer")
        val empty = ByteArrayBuilder(8)
        ArStreamWriter { b, o, n -> empty.append(b, o, n) }.finish()
        assertContentEquals(ArWriter.write(emptyList()), empty.takeByteArray())
        assertFailsWith<ArchiveFormatException> { ArStreamWriter { _, _, _ -> }.putFile("x", 3, ByteSource.of(ByteArray(2))) }
        assertFailsWith<ArchiveFormatException> { ArStreamWriter { _, _, _ -> }.putFile("x", 1, ByteSource.of(ByteArray(2))) }
        assertFailsWith<ArchiveFormatException> { ArStreamWriter { _, _, _ -> }.putFile("dir/x", 0, ByteSource.of(ByteArray(0))) }
    }

    @Test
    fun truncationsCorruptionsAndUnsafeNamesFailLikeArReader() {
        val archive = ArWriter.write(specs.take(3), ArWriter.NameStyle.GNU)
        for (cut in archive.indices) assertSame(archive.copyOf(cut), "cut at $cut")
        for (at in archive.indices) {
            for (value in listOf(0x20, 0x41, 0x2F)) {
                assertBothFailOrSame(archive.copyOf().also { it[at] = value.toByte() }, "byte $at = $value")
            }
        }
        val thin = "!<thin>\n".encodeToByteArray()
        assertFailsWith<ArchiveUnsupportedException> { ArStreamReader(ByteSource.of(thin)).next() }
        val unsafe = ArWriter.write(specs.take(1)).also { "../".encodeToByteArray().copyInto(it, 8) }
        assertFailsWith<ArchivePathException> { ArStreamReader(ByteSource.of(unsafe)).next() }
        assertEquals("../xt", ArStreamReader(ByteSource.of(unsafe), ArchiveLimits { rejectUnsafeNames = false }).next()!!.name)
    }
}
