package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.codec.snappy.Snappy
import io.github.yuroyami.archivekodec.hex
import io.github.yuroyami.archivekodec.wrapper.Zlib
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrossTargetMalformedTest {

    @Test
    fun invalidArLz4AndZlibHeadersUseTypedErrors() {
        ArReader(ByteArrayRandomAccessSource("!<arch>\n".encodeToByteArray())).use { assertTrue(it.entries().isEmpty()) }
        assertFailsWith<ArchiveFormatException> {
            ArReader(ByteArrayRandomAccessSource("!<arcb>\n".encodeToByteArray())).use { it.entries() }
        }
        assertContentEquals(byteArrayOf(), Lz4.frameDecompress(hex("04224d1860707300000000")))
        assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(hex("05224d1860707300000000")) }
        val zlib = hex("789c4bcb2f4acd4ccf53c82d2d492cc9cccf532848acccc94f4ce14a1b5a12002c064c79")
        assertContentEquals("foreign mutation payload\n".repeat(8).encodeToByteArray(), Zlib.decompress(zlib))
        // Method and window mutations retain correct FCHECK, and the rest of
        // the stream remains valid, so each individual header check is required.
        for (header in listOf("7918", "881c", "7800")) {
            assertFailsWith<ArchiveFormatException> { Zlib.decompress(hex(header) + zlib.copyOfRange(2, zlib.size)) }
        }
    }

    @Test
    fun lz4HeaderWithoutAnEndMarkerIsTruncated() {
        val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(hex("04224d18607073")) }
        assertTrue(error.offset != null)
    }

    @Test
    fun snappyTagsCannotReadOrWriteOutsideTheirBounds() {
        for (bytes in listOf(hex("0104010203"), hex("08004101"))) {
            val error = assertFailsWith<ArchiveFormatException> { Snappy.decompress(bytes) }
            assertTrue(error.offset != null)
        }
    }

    @Test
    fun zip64SentinelsRequireCompleteExtraFields() {
        val bytes = hex("504b030414000000000000002100480383a3030000000300000005000000612e747874414243504b0102140014000000000000002100480383a3ffffffffffffffff050004000000000000000000800100000000612e74787401000000504b0506000000000100010037000000260000000000")
        val error = assertFailsWith<ArchiveFormatException> { ZipReader(ByteArrayRandomAccessSource(bytes)).entries() }
        assertTrue(error.offset != null)
    }
}
