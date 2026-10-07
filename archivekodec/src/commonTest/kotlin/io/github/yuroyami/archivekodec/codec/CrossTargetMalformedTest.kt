package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.codec.snappy.Snappy
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrossTargetMalformedTest {
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

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
