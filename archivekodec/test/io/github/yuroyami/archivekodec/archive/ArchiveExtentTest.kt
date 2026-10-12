package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun putLe(bytes: ByteArray, offset: Int, value: Long, count: Int) {
    repeat(count) { bytes[offset + it] = (value ushr (8 * it)).toByte() }
}

internal fun zip64WithComment(commentLength: Int): ByteArray {
    val base = ZipWriter.write(listOf(ZipWriter.FileSpec("a.txt", "ABC".encodeToByteArray(), ZipMethod.STORED)))
    val eocd = base.copyOfRange(base.size - 22, base.size)
    fun u32(at: Int): Long = (0..3).fold(0L) { v, i -> v or ((eocd[at + i].toLong() and 255) shl (8 * i)) }
    val cdSize = u32(12)
    val cdOffset = u32(16)
    val z64 = ByteArray(56)
    putLe(z64, 0, 0x06064B50, 4); putLe(z64, 4, 44, 8)
    putLe(z64, 12, 45, 2); putLe(z64, 14, 45, 2)
    putLe(z64, 24, 1, 8); putLe(z64, 32, 1, 8)
    putLe(z64, 40, cdSize, 8); putLe(z64, 48, cdOffset, 8)
    val locator = ByteArray(20)
    putLe(locator, 0, 0x07064B50, 4); putLe(locator, 8, (base.size - 22).toLong(), 8); putLe(locator, 16, 1, 4)
    putLe(eocd, 8, 0xFFFF, 2); putLe(eocd, 10, 0xFFFF, 2)
    putLe(eocd, 12, 0xFFFFFFFF, 4); putLe(eocd, 16, 0xFFFFFFFF, 4)
    putLe(eocd, 20, commentLength.toLong(), 2)
    return base.copyOf(base.size - 22) + z64 + locator + eocd + ByteArray(commentLength) { 'c'.code.toByte() }
}

class ArchiveExtentTest {
    private fun checkedSource(bytes: ByteArray): RandomAccessSource = object : RandomAccessSource {
        private val source = ByteArrayRandomAccessSource(bytes)
        private var calls = 0
        override val size = bytes.size.toLong()
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            check(++calls < 20) { "parser failed to advance" }
            check(length <= bytes.size) { "declared length reached source read" }
            return source.read(offset, into, intoOffset, length)
        }
    }

    @Test
    fun centralDirectoryRangeIsCheckedBeforeAllocation() {
        for (size in listOf(0x40000000L, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL)) {
            val zip = ByteArray(22)
            putLe(zip, 0, 0x06054B50, 4)
            putLe(zip, 8, 1, 2); putLe(zip, 10, 1, 2); putLe(zip, 12, size, 4)
            assertFailsWith<ArchiveFormatException> { ZipReader(checkedSource(zip)).entries() }
        }
    }

    @Test
    fun zip64CountsAreCheckedBeforeListAllocation() {
        for (count in listOf(0x7FFFFFFFL, 0x80000000L, Long.MAX_VALUE, -1L)) {
            val zip = zip64WithComment(0)
            val z64 = zip.size - 98
            putLe(zip, z64 + 24, count, 8); putLe(zip, z64 + 32, count, 8)
            val e = assertFailsWith<ArchiveFormatException> { ZipReader(checkedSource(zip)).entries() }
            assertTrue(e.message!!.contains("count $count"), e.message)
        }
    }

    @Test
    fun zip64DirectorySizesAndOffsetsDoNotNarrowOrWrap() {
        for (value in listOf(0x80000000L, 0xFFFFFFFFL, Long.MAX_VALUE, -1L)) {
            for (field in listOf(40, 48)) {
                val zip = zip64WithComment(0)
                putLe(zip, zip.size - 98 + field, value, 8)
                assertFailsWith<ArchiveFormatException> { ZipReader(checkedSource(zip)).entries() }
            }
        }
    }

    @Test
    fun allZip64CommentLengthsRemainReadable() {
        for (length in listOf(0, 65515, 65516, 65535)) {
            val reader = ZipReader(ByteArrayRandomAccessSource(zip64WithComment(length)))
            assertEquals("a.txt", reader.entries().single().name)
            assertEquals("ABC", reader.read(reader.entries().single()).decodeToString())
        }
    }

    @Test
    fun compressedPayloadRangeIsCheckedWhileListing() {
        val zip = ZipWriter.write(listOf(ZipWriter.FileSpec("a.txt", "ABC".encodeToByteArray(), ZipMethod.STORED)))
        val central = zip.size - 22 - 51
        putLe(zip, central + 20, zip.size.toLong(), 4)
        assertFailsWith<ArchiveFormatException> { ZipReader(checkedSource(zip)).entries() }
    }

    private fun ar(size: String, name: String = "a/"): ByteArray {
        val header = ByteArray(60) { ' '.code.toByte() }
        name.encodeToByteArray().copyInto(header)
        size.encodeToByteArray().copyInto(header, 48)
        header[58] = 0x60; header[59] = 10
        return "!<arch>\n".encodeToByteArray() + header
    }

    @Test
    fun arSizesCannotRepeatAHeaderOrReadPastTheSource() {
        for (size in listOf("-60", "2147483647", "9999999999", "+1", "x")) {
            for (name in listOf("a/", "//")) {
                assertFailsWith<ArchiveFormatException> { ArReader(checkedSource(ar(size, name))).entries() }
            }
        }
    }

    @Test
    fun tarSizesCannotOverflowAdvanceOrAllocateFromTruncatedData() {
        for (type in listOf('0', 'L')) {
            for (size in listOf(
                "17777777777".encodeToByteArray() + byteArrayOf(0),
                byteArrayOf(0x80.toByte(), 0, 0, 0, 0x7F, -1, -1, -1, -1, -1, -2, 0),
                byteArrayOf(0x81.toByte()) + ByteArray(11),
                ByteArray(12) { -1 },
            )) {
                val header = ByteArray(512)
                "a".encodeToByteArray().copyInto(header)
                size.copyInto(header, 124); header[156] = type.code.toByte()
                tarChecksum(header)
                assertFailsWith<ArchiveFormatException> { TarReader(checkedSource(header + ByteArray(1024))).entries() }
            }
        }
    }

    private fun tarChecksum(header: ByteArray) {
        header.fill(' '.code.toByte(), 148, 156)
        header.sumOf { it.toInt() and 255 }.toString(8).padStart(6, '0').encodeToByteArray().copyInto(header, 148)
        header[154] = 0
    }

    @Test
    fun boundedGnuLongNamesAndBinaryFieldsRemainReadable() {
        fun header(size: Int, type: Char): ByteArray = ByteArray(512).also {
            "a".encodeToByteArray().copyInto(it)
            it[124] = 0x80.toByte(); it[135] = size.toByte()
            it.fill((-1).toByte(), 136, 148) // signed GNU mtime -1
            it[156] = type.code.toByte()
            tarChecksum(it)
        }
        val name = "long/file.txt".encodeToByteArray() + byteArrayOf(0)
        val longName = ByteArray(512).also { name.copyInto(it) }
        val payload = ByteArray(512).also { "ABC".encodeToByteArray().copyInto(it) }
        val reader = TarReader(ByteArrayRandomAccessSource(header(name.size, 'L') + longName + header(3, '0') + payload + ByteArray(1024)))
        val entry = reader.entries().single()
        assertEquals("long/file.txt", entry.name)
        assertEquals(-1L, entry.lastModifiedEpochSeconds)
        assertEquals("ABC", reader.read(entry).decodeToString())
    }

    @Test
    fun cpioNamesAndBodiesAreBoundedBeforeNarrowing() {
        for (nameSize in listOf(0L, 0x80000000L, 0xFFFFFFFFL)) {
            val header = ByteArray(110) { '0'.code.toByte() }
            "070701".encodeToByteArray().copyInto(header)
            "000081a4".encodeToByteArray().copyInto(header, 14)
            nameSize.toString(16).padStart(8, '0').encodeToByteArray().copyInto(header, 6 + 11 * 8)
            assertFailsWith<ArchiveFormatException> { CpioReader(checkedSource(header)).entries() }
        }
        val header = ByteArray(110) { '0'.code.toByte() }
        "070701".encodeToByteArray().copyInto(header)
        "000081a4".encodeToByteArray().copyInto(header, 14)
        "00000002".encodeToByteArray().copyInto(header, 6 + 11 * 8)
        "FFFFFFFF".encodeToByteArray().copyInto(header, 6 + 6 * 8)
        assertFailsWith<ArchiveFormatException> { CpioReader(checkedSource(header + byteArrayOf(97, 0))).entries() }
    }
}
