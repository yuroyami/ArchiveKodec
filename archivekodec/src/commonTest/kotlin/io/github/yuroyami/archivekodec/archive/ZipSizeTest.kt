package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipSizeTest {
    private fun zip(codec: CodecId, data: ByteArray = "ABCDE".encodeToByteArray(), name: String = "size.txt") =
        ZipWriter.write(listOf(ZipWriter.FileSpec(name, data, codec)))

    private fun central(bytes: ByteArray) = ZipFormat.u32(bytes, bytes.size - 6).toInt()

    private fun declareSize(bytes: ByteArray, size: Long) {
        putLe(bytes, 22, size, 4)
        putLe(bytes, central(bytes) + 24, size, 4)
    }

    @Test
    fun storedAndDeflatedSizesMustEqualActualOutputEvenWithCorrectCrc() {
        for (codec in listOf(CodecId.STORE, CodecId.DEFLATE)) {
            for (size in listOf(0L, 1L, 4L, 6L)) {
                val bytes = zip(codec)
                declareSize(bytes, size)
                val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
                val entry = reader.entries().single()
                assertEquals(size, entry.size)
                val error = assertFailsWith<ArchiveFormatException>("$codec declared $size") { reader.read(entry) }
                assertEquals(entry.name, error.entryName)
                assertTrue(error.offset!! >= 30 + entry.name.length, error.toString())
                assertTrue(error.message!!.contains("size"), error.toString())
            }
        }
    }

    @Test
    fun storedSizeMismatchIsRejectedBeforePayloadRead() {
        val bytes = zip(CodecId.STORE)
        declareSize(bytes, 1)
        var armed = false
        var payloadReads = 0
        val dataStart = 30L + "size.txt".length
        val source = object : RandomAccessSource {
            private val backing = ByteArrayRandomAccessSource(bytes)
            override val size = backing.size
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                if (armed && offset >= dataStart && offset < dataStart + 5) payloadReads++
                return backing.read(offset, into, intoOffset, length)
            }
        }
        val reader = ZipReader(source)
        val entry = reader.entries().single()
        armed = true
        assertFailsWith<ArchiveFormatException> { reader.read(entry) }
        assertEquals(0, payloadReads)
    }

    @Test
    fun actualInflateWritesStillEnforceCallerBudgets() {
        val bytes = zip(CodecId.DEFLATE, ByteArray(4096) { 65 })
        declareSize(bytes, 1)
        val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
        val entry = reader.entries().single()
        assertTrue(entry.compressedSize < 258)
        for (limits in listOf(ArchiveLimits(maxCompressionRatio = 1), ArchiveLimits(maxUncompressedBytes = 5))) {
            val error = assertFailsWith<DecompressionBombException> { reader.read(entry, limits) }
            assertEquals(entry.name, error.entryName)
            assertTrue(error.offset != null)
        }
    }

    @Test
    fun foreignDataDescriptorAllowsZeroLocalSizes() {
        // CPython ZipFile writing to an unseekable sink: bit 3, zero local sizes, signed descriptor.
        val bytes = hex("504b0304140008000800f010475d0000000000000000000000000e00000064657363726970746f722e747874737472761c45c42100504b0708ae92f3e2090000002c010000504b01021403140008000800f010475dae92f3e2090000002c0100000e000000000000000000000080010000000064657363726970746f722e747874504b050600000000010001003c000000450000000000")
        assertEquals(0L, ZipFormat.u32(bytes, 18))
        assertEquals(0L, ZipFormat.u32(bytes, 22))
        val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
        assertContentEquals("ABC".repeat(100).encodeToByteArray(), reader.read(reader.entries().single()))
    }

    @Test
    fun zip64DeclaredSizeIsComparedWithoutNarrowingOrPreallocation() {
        for (codec in listOf(CodecId.STORE, CodecId.DEFLATE)) {
            for (declared in listOf(0L, 1L, 5L, 6L, 0x1_00000000L)) {
                val bytes = zip(codec)
                val start = central(bytes)
                val record = bytes.copyOfRange(start, bytes.size - 22)
                putLe(record, 6, 45, 2)
                putLe(record, 24, 0xFFFFFFFF, 4)
                putLe(record, 30, 12, 2)
                val extra = ByteArray(12)
                putLe(extra, 0, 1, 2); putLe(extra, 2, 8, 2); putLe(extra, 4, declared, 8)
                val end = bytes.copyOfRange(bytes.size - 22, bytes.size)
                putLe(end, 12, (record.size + extra.size).toLong(), 4)
                putLe(bytes, 4, 45, 2)
                val extended = bytes.copyOf(start) + record + extra + end
                val reader = ZipReader(ByteArrayRandomAccessSource(extended))
                val entry = reader.entries().single()
                assertEquals(declared, entry.size)
                if (declared == 5L) {
                    assertContentEquals("ABCDE".encodeToByteArray(), reader.read(entry, ArchiveLimits.UNBOUNDED))
                } else {
                    assertFailsWith<ArchiveFormatException>("$codec ZIP64 size $declared") {
                        reader.read(entry, ArchiveLimits.UNBOUNDED)
                    }
                }
            }
        }
    }

    @Test
    fun directoryNameCannotBypassPayloadIntegrity() {
        for (codec in listOf(CodecId.STORE, CodecId.DEFLATE)) {
            for (data in listOf(ByteArray(0), "ABC".encodeToByteArray())) {
                val reader = ZipReader(ByteArrayRandomAccessSource(zip(codec, data, "dir/")))
                val entry = reader.entries().single()
                assertTrue(entry.isDirectory)
                assertContentEquals(data, reader.read(entry))
            }
            val bytes = zip(codec, ByteArray(0), "dir/")
            declareSize(bytes, 1)
            val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
            assertFailsWith<ArchiveFormatException> { reader.read(reader.entries().single()) }
        }
    }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
