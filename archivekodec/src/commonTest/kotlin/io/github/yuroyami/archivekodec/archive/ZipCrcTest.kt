package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class ZipCrcTest {
    @Test
    fun zeroIsARealChecksumAndCannotDisableValidation() {
        val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec("bad.txt", byteArrayOf(65), CodecId.STORE)))
        val central = 30 + "bad.txt".length + 1
        bytes.fill(0, central + 16, central + 20)
        val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
        assertFailsWith<ArchiveFormatException> { reader.read(reader.entries().single()) }
    }

    @Test
    fun validZeroChecksumDataRemainsReadable() {
        // Independently verified with CPython zlib.crc32: CRC-32(9d 0a d9 6d) == 0.
        val data = byteArrayOf(0x9d.toByte(), 0x0a, 0xd9.toByte(), 0x6d)
        val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec("zero.bin", data, CodecId.STORE)))
        val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
        assertContentEquals(data, reader.read(reader.entries().single()))
    }
}
