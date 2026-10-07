package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipFilenameTest {
    @Test
    fun namesBeyondUnsigned16BitByteLengthAreRejectedBeforeWriting() {
        val names = listOf("x".repeat(65536), "é".repeat(32768), "中".repeat(21846), "😀".repeat(16384))
        for (codec in listOf(CodecId.STORE, CodecId.DEFLATE)) {
            for (name in names) {
                val error = assertFailsWith<ArchiveFormatException> {
                    ZipWriter.write(listOf(ZipWriter.FileSpec(name, byteArrayOf(65), codec)))
                }
                assertEquals(name, error.entryName)
                assertTrue(error.message!!.contains("65535"))
            }
        }
    }

    @Test
    fun exactByteBoundaryIsSupportedForAsciiAndMultibyteNames() {
        for (name in listOf("x".repeat(65535), "é".repeat(32767) + "x", "中".repeat(21845), "😀".repeat(16383) + "abc")) {
            assertEquals(65535, name.encodeToByteArray().size)
            val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec(name, byteArrayOf(65), CodecId.STORE)))
            val central = ZipFormat.u32(bytes, bytes.size - 6).toInt()
            assertEquals(65535, ZipFormat.u16(bytes, 26))
            assertEquals(65535, ZipFormat.u16(bytes, central + 28))
            val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
            val entry = reader.entries().single()
            assertEquals(name, entry.name)
            assertContentEquals(byteArrayOf(65), reader.read(entry))
        }
    }

    @Test
    fun utf8IsDeclaredInBothHeadersForEveryName() {
        for (name in listOf("ascii.txt", "é.txt", "目录/文件.txt", "😀.txt")) {
            val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec(name, byteArrayOf(), CodecId.STORE)))
            val central = ZipFormat.u32(bytes, bytes.size - 6).toInt()
            assertEquals(0x0800, ZipFormat.u16(bytes, 6))
            assertEquals(0x0800, ZipFormat.u16(bytes, central + 8))
            assertContentEquals(name.encodeToByteArray(), bytes.copyOfRange(30, 30 + ZipFormat.u16(bytes, 26)))
        }
    }

    @Test
    fun aLaterInvalidNameIsRejected() {
        val error = assertFailsWith<ArchiveFormatException> {
            ZipWriter.write(listOf(
                ZipWriter.FileSpec("first", ByteArray(1024) { 65 }, CodecId.DEFLATE),
                ZipWriter.FileSpec("x".repeat(65536), byteArrayOf(), CodecId.STORE),
            ))
        }
        assertEquals(65536, error.entryName!!.length)
    }
}
