package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveSizeException
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.rar.RarReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.checksum.Crc32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An entry of exactly 2^31 bytes passes the default 2 GiB output limit but cannot fit one
 * ByteArray: every reader refuses it with [ArchiveSizeException], before reading or allocating
 * it. The sources report the full size but hold only the archive's structure.
 */
class EntryTooLargeForAnArrayTest {
    private val big = 1L shl 31

    /** A source of [size] bytes: [parts] at their offsets, zeros everywhere else. */
    private class Virtual(override val size: Long, private vararg val parts: Pair<Long, ByteArray>) : RandomAccessSource {
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            if (offset >= size) return -1
            val n = minOf(length.toLong(), size - offset).toInt()
            into.fill(0, intoOffset, intoOffset + n)
            for ((at, bytes) in parts) {
                val from = maxOf(at, offset)
                val to = minOf(at + bytes.size, offset + n)
                for (p in from until to) into[intoOffset + (p - offset).toInt()] = bytes[(p - at).toInt()]
            }
            return n
        }
    }

    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { (value ushr (8 * it)).toByte() }

    private fun assertTooLarge(reader: ArchiveReader) = reader.use {
        val entry = it.entries().single { e -> !e.isDirectory }
        assertEquals(big, entry.size)
        val error = assertFailsWith<ArchiveSizeException> { it.read(entry) }
        assertTrue("$big" in error.message!!, error.message)
        assertEquals(entry.name, error.entryName)
        kotlin.test.assertNotNull(error.offset)
    }

    @Test
    fun tar() {
        val h = ByteArray(512)
        fun put(offset: Int, text: String) = text.encodeToByteArray().copyInto(h, offset)
        put(0, "big"); put(100, "0000644"); put(124, big.toString(8).padStart(11, '0'))
        put(136, "00000000000"); h[156] = '0'.code.toByte(); put(257, "ustar"); put(263, "00")
        put(148, "        ")
        put(148, h.sumOf { it.toInt() and 0xFF }.toString(8).padStart(6, '0') + "\u0000 ")
        assertTooLarge(TarReader(Virtual(512 + big + 1024, 0L to h)))
    }

    @Test
    fun ar() {
        val header = "!<arch>\n" + "big/".padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) +
            "100644".padEnd(8) + big.toString().padEnd(10) + "`\n"
        assertTooLarge(ArReader(Virtual(68 + big, 0L to header.encodeToByteArray())))
    }

    @Test
    fun cpio() {
        fun newc(name: String, size: Long): ByteArray {
            val nameBytes = name.encodeToByteArray() + 0
            val fields = longArrayOf(1, 0x81A4, 0, 0, 1, 0, size, 0, 0, 0, 0, nameBytes.size.toLong(), 0)
            val head = ("070701" + fields.joinToString("") { it.toString(16).padStart(8, '0') }).encodeToByteArray() + nameBytes
            return head + ByteArray((4 - head.size % 4) % 4)
        }
        val first = newc("big", big)
        val trailer = newc("TRAILER!!!", 0)
        val end = first.size + big
        assertTooLarge(CpioReader(Virtual(end + trailer.size, 0L to first, end to trailer)))
    }

    @Test
    fun rarStored() {
        val name = "big".encodeToByteArray()
        val h = ByteArray(32 + name.size)
        h[2] = 0x74
        le(0x8000, 2).copyInto(h, 3); le(h.size.toLong(), 2).copyInto(h, 5)
        le(big, 4).copyInto(h, 7); le(big, 4).copyInto(h, 11)
        h[15] = 3; h[24] = 29; h[25] = 0x30
        le(name.size.toLong(), 2).copyInto(h, 26); le(0x81A4, 4).copyInto(h, 28)
        name.copyInto(h, 32)
        val crc = Crc32().apply { update(h, 2, h.size - 2) }.value()
        h[0] = crc.toByte(); h[1] = (crc ushr 8).toByte()
        val archive = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00) + h
        assertTooLarge(RarReader(Virtual(archive.size + big, 0L to archive)))
    }

    @Test
    fun zipStored() {
        val name = "big".encodeToByteArray()
        val local = le(0x04034b50, 4) + le(20, 2) + le(0, 2) + le(0, 2) + le(0, 4) + le(0, 4) +
            le(big, 4) + le(big, 4) + le(name.size.toLong(), 2) + le(0, 2) + name
        val cdOffset = local.size + big
        val central = le(0x02014b50, 4) + le(20, 2) + le(20, 2) + le(0, 2) + le(0, 2) + le(0, 4) + le(0, 4) +
            le(big, 4) + le(big, 4) + le(name.size.toLong(), 2) + le(0, 2) + le(0, 2) + le(0, 2) + le(0, 2) +
            le(0, 4) + le(0, 4) + name
        val end = le(0x06054b50, 4) + le(0, 2) + le(0, 2) + le(1, 2) + le(1, 2) + le(central.size.toLong(), 4) + le(cdOffset, 4) + le(0, 2)
        assertTooLarge(ZipReader(Virtual(cdOffset + central.size + end.size, 0L to local, cdOffset to central + end)))
    }

    @Test
    fun aStructureTooLargeForAnArrayStaysAFormatError() {
        assertFailsWith<ArchiveFormatException> { Virtual(big + 10).readFullyChecked(0, big) }
    }
}
