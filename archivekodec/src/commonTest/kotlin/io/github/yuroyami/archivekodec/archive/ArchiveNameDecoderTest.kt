package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import kotlin.test.*

class ArchiveNameDecoderTest {
    private val raw = byteArrayOf(99, 97, 102, 0xe9.toByte())
    private val payload = byteArrayOf(42, 7, 8)
    private val latin1 = ArchiveNameDecoder { bytes -> bytes.joinToString("") { (it.toInt() and 255).toChar().toString() } }

    private fun tar(name: ByteArray, data: ByteArray = payload, prefix: ByteArray = byteArrayOf(), type: Char = '0'): ByteArray {
        val header = ByteArray(512)
        name.copyInto(header)
        "0000644".encodeToByteArray().copyInto(header, 100)
        data.size.toString(8).padStart(11, '0').encodeToByteArray().copyInto(header, 124)
        header[156] = type.code.toByte()
        "ustar\u0000".encodeToByteArray().copyInto(header, 257)
        prefix.copyInto(header, 345)
        header.fill(32, 148, 156)
        (header.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0') + "\u0000 ").encodeToByteArray().copyInto(header, 148)
        return header + data + ByteArray((512 - data.size % 512) % 512)
    }

    private fun arMember(name: ByteArray, data: ByteArray): ByteArray {
        val header = ByteArray(60) { 32 }
        name.copyInto(header)
        data.size.toString().encodeToByteArray().copyInto(header, 48)
        header[58] = 96; header[59] = 10
        return header + data + ByteArray(data.size and 1) { 10 }
    }
    private fun ar(vararg members: ByteArray) = "!<arch>\n".encodeToByteArray() + members.reduce { a, b -> a + b }

    private fun cpio(name: ByteArray, data: ByteArray = payload, magic: String = "070701"): ByteArray {
        val stored = name + byteArrayOf(0, 0, 0)
        if (magic == "070707") {
            val h = ByteArray(76) { 48 }
            magic.encodeToByteArray().copyInto(h)
            "100644".encodeToByteArray().copyInto(h, 18)
            stored.size.toString(8).padStart(6, '0').encodeToByteArray().copyInto(h, 59)
            data.size.toString(8).padStart(11, '0').encodeToByteArray().copyInto(h, 65)
            return h + stored + data
        }
        val fields = longArrayOf(1, 0x81a4, 0, 0, 1, 0, data.size.toLong(), 0, 0, 0, 0, stored.size.toLong(), 0)
        val h = (magic + fields.joinToString("") { it.toString(16).padStart(8, '0') }).encodeToByteArray()
        val named = h + stored
        return named + ByteArray((4 - named.size % 4) % 4) + data + ByteArray((4 - data.size % 4) % 4)
    }

    private fun zip(name: ByteArray): ByteArray {
        val data = ZipWriter.write(listOf(ZipWriter.FileSpec("n".repeat(name.size), payload, CodecId.STORE)))
        val central = data.size - 22 - 46 - name.size
        data[6] = 0; data[7] = 0; data[central + 8] = 0; data[central + 9] = 0
        name.copyInto(data, 30); name.copyInto(data, central + 46)
        return data
    }

    private fun containers(name: ByteArray = raw): List<ByteArray> = listOf(
        zip(name), tar(name) + ByteArray(1024),
        tar(name, prefix = "dir".encodeToByteArray()) + ByteArray(1024),
        tar("././@LongLink".encodeToByteArray(), name + ByteArray(3), type = 'L') + tar("stub".encodeToByteArray()) + ByteArray(1024),
        ar(arMember(name + byteArrayOf(47), payload)),
        ar(arMember("//".encodeToByteArray(), name + byteArrayOf(47, 10)), arMember("/0".encodeToByteArray(), payload)),
        ar(arMember("//".encodeToByteArray(), name + byteArrayOf(0)), arMember("/0".encodeToByteArray(), payload)),
        ar(arMember("#1/${name.size + 3}".encodeToByteArray(), name + ByteArray(3) + payload)),
        cpio(name), cpio(name, magic = "070702"), cpio(name, magic = "070707"),
    )

    @Test
    fun explicitDecoderWorksForEveryContainerAndNameLayout() {
        for ((index, data) in containers().withIndex()) {
            ArchiveKodec.open(ByteArrayRandomAccessSource(data), latin1).use { reader ->
                val entry = reader.entries().single()
                val expected = if (index == 2) "dir/café" else "café"
                assertEquals(expected, entry.name, "layout $index")
                assertContentEquals(if (index == 2) "dir/".encodeToByteArray() + raw else raw, entry.nameBytes)
                assertContentEquals(payload, reader.read(entry))
            }
        }
    }

    @Test
    fun utf8DefaultRejectsMalformedNonZipNamesWithSourceContext() {
        for (data in containers().drop(1)) {
            ArchiveKodec.open(ByteArrayRandomAccessSource(data)).use { reader ->
                val error = assertFailsWith<ArchiveFormatException> { reader.entries() }
                assertNotNull(error.offset)
                assertNotNull(error.cause)
            }
        }
    }

    @Test
    fun validUtf8AndSupplementaryNamesRemainExactInEveryLayout() {
        val name = "é-雪-🚀".encodeToByteArray()
        for ((index, data) in containers(name).withIndex()) {
            ArchiveKodec.open(ByteArrayRandomAccessSource(data), ArchiveNameDecoder.UTF8).use { reader ->
                val entry = reader.entries().single()
                assertEquals(if (index == 2) "dir/é-雪-🚀" else "é-雪-🚀", entry.name)
                assertContentEquals(payload, reader.read(entry))
            }
        }
    }

    @Test
    fun storedSnapshotsAndDecoderInputsCannotChangeReaderMetadata() {
        for (data in containers()) {
            var calls = 0
            val decoder = ArchiveNameDecoder { bytes ->
                calls++
                val name = latin1.decode(bytes)
                bytes.fill(0)
                name
            }
            ArchiveKodec.open(ByteArrayRandomAccessSource(data), decoder).use { reader ->
                val entry = reader.entries().single()
                val original = entry.nameBytes!!
                entry.nameBytes!!.fill(0)
                val copy = entry.copy()
                copy.nameBytes!!.fill(0)
                assertContentEquals(original, entry.nameBytes)
                assertContentEquals(original, copy.nameBytes)
                assertContentEquals(original, entry.copy(name = "changed").nameBytes)
                assertEquals(entry, copy)
                assertEquals(entry.hashCode(), copy.hashCode())
                assertEquals(listOf(entry), reader.entries())
                assertEquals(1, calls)
                assertContentEquals(payload, reader.read(copy))
            }
        }
    }

    @Test
    fun cpioTrailersAndArSymbolIndicesAreClassifiedBeforeTheCallback() {
        var calls = 0
        val decoder = ArchiveNameDecoder { calls++; "TRAILER!!!" }
        for (magic in listOf("070701", "070702", "070707")) {
            val data = cpio(raw, magic = magic) + cpio("TRAILER!!!".encodeToByteArray(), byteArrayOf(), magic) + cpio(raw, magic = magic)
            ArchiveKodec.open(ByteArrayRandomAccessSource(data), decoder).use { reader ->
                assertEquals(listOf("TRAILER!!!"), reader.entries().map { it.name })
                assertContentEquals(payload, reader.read(reader.entries().single()))
            }
        }
        assertEquals(3, calls)
        calls = 0
        val index = "__.SYMDEF".encodeToByteArray() + ByteArray(3)
        val data = ar(arMember("#1/${index.size}".encodeToByteArray(), index), arMember("file/".encodeToByteArray(), payload))
        ArReader(ByteArrayRandomAccessSource(data), ArchiveNameDecoder { calls++; "__.SYMDEF" }).use { reader ->
            assertEquals(listOf("__.SYMDEF"), reader.entries().map { it.name })
            assertContentEquals(payload, reader.read(reader.entries().single()))
        }
        assertEquals(1, calls)
    }

    @Test
    fun callbackExceptionsRemainTypedAndKeepTheirCause() {
        for (data in containers()) {
            val cause = IllegalArgumentException("bad charset")
            ArchiveKodec.open(ByteArrayRandomAccessSource(data), ArchiveNameDecoder { throw cause }).use { reader ->
                val error = assertFailsWith<ArchiveFormatException> { reader.entries() }
                assertSame(cause, error.cause)
                assertNotNull(error.offset)
            }
            ArchiveKodec.open(ByteArrayRandomAccessSource(data), ArchiveNameDecoder { throw ArchiveUnsupportedException("charset unavailable") }).use { reader ->
                val error = assertFailsWith<ArchiveUnsupportedException> { reader.entries() }
                assertNotNull(error.offset)
                assertIs<ArchiveUnsupportedException>(error.cause)
            }
        }
    }

    @Test
    fun flaggedUtf8BypassesTheLegacyCallback() {
        val decoder = ArchiveNameDecoder { error("must not decode flagged UTF-8") }
        ZipReader(ByteArrayRandomAccessSource(NameEncodingFixtures.utf8Zip), decoder).use { reader ->
            assertEquals(listOf("café.txt", "雪.txt", "🚀.txt"), reader.entries().map { it.name })
        }
    }

    @Test
    fun concreteConstructorsKeepTheirDefaultAndExplicitForms() {
        val sources = listOf(zip(raw), tar(raw), ar(arMember(raw, payload)), cpio(raw))
        val factories: List<(RandomAccessSource, ArchiveNameDecoder) -> ArchiveReader> = listOf(
            { s, d -> ZipReader(s, d) }, { s, d -> TarReader(s, d) }, { s, d -> ArReader(s, d) }, { s, d -> CpioReader(s, d) },
        )
        for ((data, factory) in sources.zip(factories)) {
            factory(ByteArrayRandomAccessSource(data), latin1).use { reader -> assertEquals("café", reader.entries().single().name) }
        }
    }

    @Test
    fun cp437UsesAll256UnicodeMappingsIncludingControls() {
        val ascii = ByteArray(128) { it.toByte() }
        assertEquals(ascii.map { (it.toInt() and 255).toChar() }.joinToString(""), ArchiveNameDecoder.CP437.decode(ascii))
        val high = ByteArray(128) { (it + 128).toByte() }
        assertEquals(NameEncodingFixtures.cp437Names.last().removePrefix("all-").removeSuffix(".bin"), ArchiveNameDecoder.CP437.decode(high))
    }

    @Test
    fun manuallyConstructedValuesKeepTheirExistingValueApi() {
        val entry = ArchiveEntry("file", 7, false)
        assertNull(entry.nameBytes)
        assertNull(entry.copy().nameBytes)
        assertEquals(entry, entry.copy())
        val (name, size, directory, modified, compressed, codec, encrypted, crc, offset) = entry
        assertEquals("file", name); assertEquals(7L, size); assertFalse(directory); assertNull(modified)
        assertEquals(7L, compressed); assertEquals(CodecId.STORE, codec); assertFalse(encrypted); assertNull(crc); assertEquals(-1L, offset)
    }
}
