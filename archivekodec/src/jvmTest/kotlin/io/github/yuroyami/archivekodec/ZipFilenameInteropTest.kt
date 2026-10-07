package io.github.yuroyami.archivekodec

import com.sun.management.ThreadMXBean
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import java.lang.management.ManagementFactory
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipFilenameInteropTest {
    private val legacy = Charset.forName("CP437")

    @Test
    fun jdkReadsUtf8NamesFromBothHeadersWithLegacyFallbackConfigured() {
        val names = listOf("ascii.txt", "é.txt", "目录/文件.txt", "😀.txt")
        for (codec in listOf(CodecId.STORE, CodecId.DEFLATE)) {
            val data = ByteArray(256) { it.toByte() }
            val bytes = ZipWriter.write(names.map { ZipWriter.FileSpec(it, data, codec) })
            ZipInputStream(bytes.inputStream(), legacy).use { input ->
                for (name in names) {
                    assertEquals(name, input.nextEntry.name)
                    assertContentEquals(data, input.readAllBytes())
                }
                assertNull(input.nextEntry)
            }
            val file = Files.createTempFile("archivekodec-unicode-", ".zip")
            try {
                Files.write(file, bytes)
                ZipFile(file.toFile(), legacy).use { zip ->
                    assertEquals(names, zip.entries().toList().map { it.name })
                    for (name in names) zip.getInputStream(zip.getEntry(name)).use {
                        assertContentEquals(data, it.readAllBytes())
                    }
                }
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    @Test
    fun jdkReadsMaximumByteLengthNames() {
        val names = listOf("x".repeat(65535), "é".repeat(32767) + "x", "中".repeat(21845), "😀".repeat(16383) + "abc")
        val data = byteArrayOf(65)
        for (name in names) {
            val bytes = ZipWriter.write(listOf(ZipWriter.FileSpec(name, data, CodecId.STORE)))
            ZipInputStream(bytes.inputStream(), legacy).use { input ->
                assertEquals(name, input.nextEntry.name)
                assertContentEquals(data, input.readAllBytes())
                assertNull(input.nextEntry)
            }
            val file = Files.createTempFile("archivekodec-max-name-", ".zip")
            try {
                Files.write(file, bytes)
                ZipFile(file.toFile(), legacy).use { zip ->
                    assertEquals(name, zip.entries().nextElement().name)
                    zip.getInputStream(zip.getEntry(name)).use { assertContentEquals(data, it.readAllBytes()) }
                }
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    @Test
    fun preflightRejectsBeforeEncodingHugeNamesOrCompressingEarlierData() {
        val files = listOf(
            ZipWriter.FileSpec("first", ByteArray(4 * 1024 * 1024) { 65 }, CodecId.DEFLATE),
            ZipWriter.FileSpec("x".repeat(1024 * 1024), byteArrayOf(), CodecId.STORE),
        )
        repeat(3) { assertFailsWith<ArchiveFormatException> { ZipWriter.write(files) } }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val error = assertFailsWith<ArchiveFormatException> { ZipWriter.write(files) }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertEquals(files.last().name, error.entryName)
        assertTrue(allocated < 128 * 1024, "Filename rejection allocated $allocated bytes")
    }

    @Test
    fun invalidPathsAndUnicodeAreRejectedBeforeEarlierCompression() {
        val data = ByteArray(4 * 1024 * 1024) { 65 }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        for (name in listOf("a\\b", "/absolute", "./C:/drive", "RAM:device", "a/../b", "a\u0000b", "a\u007fb", "a\uD800b")) {
            val files = listOf(
                ZipWriter.FileSpec("first", data, CodecId.DEFLATE),
                ZipWriter.FileSpec(name, byteArrayOf(), CodecId.STORE),
            )
            repeat(3) { assertFailsWith<ArchiveFormatException> { ZipWriter.write(files) } }
            val before = bean.getThreadAllocatedBytes(thread)
            val error = assertFailsWith<ArchiveFormatException> { ZipWriter.write(files) }
            val allocated = bean.getThreadAllocatedBytes(thread) - before
            assertEquals(name, error.entryName)
            assertTrue(allocated < 128 * 1024, "Filename rejection allocated $allocated bytes")
        }
    }
}
