package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipFilenameTest {
    @Test
    fun namesBeyondUnsigned16BitByteLengthAreRejectedBeforeWriting() {
        val names = listOf("x".repeat(65536), "é".repeat(32768), "中".repeat(21846), "😀".repeat(16384))
        for (method in listOf(ZipMethod.STORED, ZipMethod.DEFLATED)) {
            for (name in names) {
                val error = assertFailsWith<ArchiveFormatException> {
                    ZipWriter.write(listOf(ZipWriter.FileSpec(name, byteArrayOf(65), method)))
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
            val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec(name, byteArrayOf(65), ZipMethod.STORED)))
            val central = readU32Le(bytes, bytes.size - 6).toInt()
            assertEquals(65535, readU16Le(bytes, 26))
            assertEquals(65535, readU16Le(bytes, central + 28))
            val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
            val entry = reader.entries().single()
            assertEquals(name, entry.name)
            assertContentEquals(byteArrayOf(65), reader.read(entry))
        }
    }

    @Test
    fun utf8IsDeclaredInBothHeadersForEveryName() {
        for (name in listOf("ascii.txt", "é.txt", "目录/文件.txt", "😀.txt")) {
            val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec(name, byteArrayOf(), ZipMethod.STORED)))
            val central = readU32Le(bytes, bytes.size - 6).toInt()
            assertEquals(0x0800, readU16Le(bytes, 6))
            assertEquals(0x0800, readU16Le(bytes, central + 8))
            assertContentEquals(name.encodeToByteArray(), bytes.copyOfRange(30, 30 + readU16Le(bytes, 26)))
        }
    }

    @Test
    fun aLaterInvalidNameIsRejected() {
        val error = assertFailsWith<ArchiveFormatException> {
            ZipWriter.write(listOf(
                ZipWriter.FileSpec("first", ByteArray(1024) { 65 }, ZipMethod.DEFLATED),
                ZipWriter.FileSpec("x".repeat(65536), byteArrayOf(), ZipMethod.STORED),
            ))
        }
        assertEquals(65536, error.entryName!!.length)
    }
}
