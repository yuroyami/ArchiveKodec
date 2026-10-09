package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.knownCodecIds
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.util.readI64Le
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArchiveFacadeTest {
    private val foreign = ForeignFacadeFixtures

    @Test
    fun opensForeignContainersThroughBothOverloads() {
        val archives = listOf(
            Triple(ArchiveFormat.TAR, foreign.v7, listOf("empty-dir/", "sample.txt")),
            Triple(ArchiveFormat.TAR, foreign.ustar, listOf("empty-dir/", foreign.longPath)),
            // PAX metadata interpretation is separate; extension records must not disrupt ordinary members.
            Triple(ArchiveFormat.TAR, foreign.pax, listOf("empty-dir/", "sample.txt")),
            Triple(ArchiveFormat.TAR, foreign.gnu, listOf("empty-dir/", foreign.longName)),
            Triple(ArchiveFormat.TAR, foreign.gnuBinaryTime, listOf("sample.txt")),
            Triple(ArchiveFormat.AR, foreign.ar, listOf("sample.txt", foreign.longName)),
            Triple(ArchiveFormat.CPIO, foreign.newc, listOf("sample.txt")),
            Triple(ArchiveFormat.CPIO, foreign.odc, listOf("sample.txt")),
            Triple(ArchiveFormat.ZIP, foreign.zip64, listOf("sample.txt")),
        )
        for ((format, bytes, names) in archives) for (explicitNames in listOf(false, true)) {
            val source = ByteArrayRandomAccessSource(bytes)
            val opened = if (explicitNames) ArchiveKodec.open(source, ArchiveNameDecoder.UTF8) else ArchiveKodec.open(source)
            opened.use { reader ->
                when (format) {
                    ArchiveFormat.TAR -> assertIs<TarReader>(reader)
                    ArchiveFormat.AR -> assertIs<ArReader>(reader)
                    ArchiveFormat.CPIO -> assertIs<CpioReader>(reader)
                    ArchiveFormat.ZIP -> assertIs<ZipReader>(reader)
                    else -> error("Unexpected fixture format $format")
                }
                assertEquals(format, reader.format)
                val entries = reader.entries()
                assertEquals(names, entries.map { it.name })
                for (entry in entries) {
                    val directory = entry.name == "empty-dir/"
                    val expected = if (directory) byteArrayOf() else foreign.payload
                    assertEquals(directory, entry.isDirectory, entry.name)
                    assertEquals(expected.size.toLong(), entry.size, entry.name)
                    assertContentEquals(expected, reader.read(entry), entry.name)
                }
                if (bytes === foreign.gnuBinaryTime) {
                    assertEquals(8_589_934_592L, entries.single().lastModifiedEpochSeconds)
                }
            }
        }
    }

    @Test
    fun foreignFixturesRetainFormatSpecificRecords() {
        assertTrue(foreign.v7.copyOfRange(257, 265).all { it == 0.toByte() })
        assertContentEquals(
            "prefix-".repeat(18).encodeToByteArray(),
            foreign.ustar.copyOfRange(512 + 345, 512 + 345 + 126),
        )
        assertEquals('x'.code.toByte(), foreign.pax[156])
        assertEquals('L'.code.toByte(), foreign.gnu[512 + 156])
        assertTrue(foreign.gnuBinaryTime[136].toInt() and 0x80 != 0)
        assertContentEquals("//".encodeToByteArray(), foreign.ar.copyOfRange(8, 10))
        val central = foreign.zip64CentralOffset
        assertEquals(ZipFormat.CEN, readU32Le(foreign.zip64, central))
        assertEquals(0xFFFFFFFFL, readU32Le(foreign.zip64, central + 24))
        val extra = central + 46 + readU16Le(foreign.zip64, central + 28)
        assertEquals(1, readU16Le(foreign.zip64, extra))
        assertEquals(8, readU16Le(foreign.zip64, extra + 2))
        assertEquals(foreign.payload.size.toLong(), readI64Le(foreign.zip64, extra + 4))
    }

    @Test
    fun sniffsEveryRecognizedFormatAndRefusesUnsupportedContainers() {
        val supported = listOf(
            ArchiveFormat.ZIP to foreign.zip64, ArchiveFormat.TAR to foreign.v7,
            ArchiveFormat.AR to foreign.ar, ArchiveFormat.CPIO to foreign.newc,
        )
        val unsupported = listOf(
            ArchiveFormat.ZLIB to hex("789c"),
            ArchiveFormat.BZIP2 to hex("425a6839177245385090"), ArchiveFormat.XZ to hex("fd377a585a00"),
            ArchiveFormat.ZSTD to hex("28b52ffd"), ArchiveFormat.LZ4 to hex("04224d18"),
            ArchiveFormat.SEVEN_ZIP to hex("377abcaf271c"), ArchiveFormat.RAR to hex("526172211a0700"),
        )
        for ((format, bytes) in supported + unsupported + (ArchiveFormat.GZIP to hex("1f8b0800"))) {
            assertEquals(format, ArchiveKodec.sniff(bytes))
            assertEquals(format, ArchiveKodec.sniff(ByteArrayRandomAccessSource(bytes)))
        }
        for ((format, bytes) in unsupported) for (explicitNames in listOf(false, true)) {
            val source = ByteArrayRandomAccessSource(bytes)
            try {
                val error = assertFailsWith<ArchiveUnsupportedException> {
                    if (explicitNames) ArchiveKodec.open(source, ArchiveNameDecoder.UTF8) else ArchiveKodec.open(source)
                }
                assertEquals(0L, error.offset)
                assertTrue(error.message.orEmpty().contains(format.toString()))
            } finally {
                source.close()
            }
        }
        for (explicitNames in listOf(false, true)) {
            val source = ByteArrayRandomAccessSource(hex("010203"))
            try {
                assertEquals(ArchiveFormat.UNKNOWN, ArchiveKodec.sniff(source))
                assertFailsWith<ArchiveFormatException> {
                    if (explicitNames) ArchiveKodec.open(source, ArchiveNameDecoder.UTF8) else ArchiveKodec.open(source)
                }
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun registryAndConveniencePropertiesExposeExactlyTheAvailableCodecs() {
        val expected = mapOf(
            CodecId.STORE to Codecs.STORE, CodecId.DEFLATE to Codecs.DEFLATE,
            CodecId.DEFLATE64 to Codecs.DEFLATE64, CodecId.BZIP2 to Codecs.BZIP2, CodecId.GZIP to Codecs.GZIP, CodecId.ZLIB to Codecs.ZLIB,
            CodecId.LZ4 to Codecs.LZ4, CodecId.SNAPPY to Codecs.SNAPPY,
            CodecId.SNAPPY_FRAMED to Codecs.SNAPPY_FRAMED,
        )
        assertEquals(expected.size, ArchiveKodec.codecs.size)
        assertEquals(expected.keys, ArchiveKodec.codecs.toSet())
        for (id in knownCodecIds) {
            val codec = ArchiveKodec.codec(id)
            if (id in expected) {
                assertSame(expected.getValue(id), codec, id.toString())
                assertEquals(id, codec!!.id)
            } else {
                assertNull(codec, id.toString())
            }
        }
        assertSame(Codecs.STORE, ArchiveKodec.store)
        assertSame(Codecs.DEFLATE, ArchiveKodec.deflate)
        assertSame(Codecs.DEFLATE64, ArchiveKodec.deflate64)
        assertSame(Codecs.BZIP2, ArchiveKodec.bzip2)
        assertSame(Codecs.GZIP, ArchiveKodec.gzip)
        assertSame(Codecs.ZLIB, ArchiveKodec.zlib)
        assertSame(Codecs.LZ4, ArchiveKodec.lz4)
        assertSame(Codecs.SNAPPY, ArchiveKodec.snappy)
        assertSame(Codecs.SNAPPY_FRAMED, ArchiveKodec.snappyFramed)
    }

}
