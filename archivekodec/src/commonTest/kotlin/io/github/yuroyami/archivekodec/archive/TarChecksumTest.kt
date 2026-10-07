package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TarChecksumTest {
    // CPython 3.14.7 TarInfo("é.txt").tobuf(USTAR_FORMAT), size 3, mode 0644.
    private val referenceHeader = hex(
        "c3a92e747874000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "000000003030303036343400303030303030300030303030303030003030303030303030303033003030303030303030" +
        "303030003030373333320020300000000000000000000000000000000000000000000000000000000000000000000000" +
        "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
        "00000000000000000000000000000000007573746172003030"
    ) + ByteArray(247)

    private fun checksum(header: ByteArray, signed: Boolean = false) {
        header.fill(32, 148, 156)
        val sum = header.sumOf { if (signed) it.toInt() else it.toInt() and 255 }
        (sum.toString(8).padStart(6, '0') + "\u0000 ").encodeToByteArray().copyInto(header, 148)
    }

    private fun number(header: ByteArray, value: Long, offset: Int, length: Int) {
        header.fill(0, offset, offset + length)
        val octal = value.toString(8)
        if (value >= 0 && octal.length < length) {
            octal.padStart(length - 1, '0').encodeToByteArray().copyInto(header, offset)
        } else {
            header.fill(if (value < 0) -1 else 0, offset, offset + length)
            repeat(8) { header[offset + length - 1 - it] = (value ushr (it * 8)).toByte() }
            header[offset] = if (value < 0) -1 else 0x80.toByte()
        }
    }

    private fun header(name: String = "a.txt", size: Long = 0, type: Char = '0'): ByteArray = referenceHeader.copyOf().also {
        it.fill(0, 0, 100)
        name.encodeToByteArray().copyInto(it)
        number(it, size, 124, 12)
        it[156] = type.code.toByte()
        checksum(it)
    }

    private fun reader(bytes: ByteArray) = TarReader(ByteArrayRandomAccessSource(bytes))
    private fun invalid(bytes: ByteArray, offset: Long = 0, message: String = "checksum") {
        val error = assertFailsWith<ArchiveFormatException> { reader(bytes).entries() }
        assertEquals(offset, error.offset)
        assertNull(error.entryName)
        assertTrue(error.message!!.contains(message), error.toString())
    }

    @Test
    fun independentUnsignedAndLegacySignedHeadersRemainReadable() {
        assertEquals("007332\u0000 ", referenceHeader.decodeToString(148, 156))
        for (signed in listOf(false, true)) {
            val h = referenceHeader.copyOf()
            if (signed) "006332\u0000 ".encodeToByteArray().copyInto(h, 148)
            val archive = reader(h + "ABC".encodeToByteArray() + ByteArray(509 + 1024))
            val entry = archive.entries().single()
            assertEquals("é.txt", entry.name)
            assertEquals(3L, entry.size)
            assertContentEquals("ABC".encodeToByteArray(), archive.read(entry))
        }
    }

    @Test
    fun everyByteOutsideTheChecksumFieldIsCovered() {
        val h = header()
        for (offset in h.indices.filter { it !in 148..155 }) {
            val changed = h.copyOf()
            changed[offset] = (changed[offset].toInt() xor 1).toByte()
            invalid(changed + ByteArray(1024))
        }
    }

    @Test
    fun invalidChecksumFieldsCannotHideBehindAValidPrefix() {
        val h = header()
        val digits = h.decodeToString(148, 154)
        val fields = listOf(
            "        ".encodeToByteArray(), ByteArray(8), "77777777".encodeToByteArray(),
            "00000000".encodeToByteArray(), (digits + "\u0000A").encodeToByteArray(),
            (digits + "\u00000").encodeToByteArray(), (digits + " 8").encodeToByteArray(),
            (digits.take(3) + " " + digits.takeLast(3) + "\u0000").encodeToByteArray(),
            byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 1, 0),
        )
        for (field in fields) {
            assertEquals(8, field.size)
            invalid(h.copyOf().also { field.copyInto(it, 148) } + ByteArray(1024))
        }
    }

    @Test
    fun octalFieldsAcceptSpaceNulAndFullWidthPadding() {
        val h = header()
        val digits = h.decodeToString(148, 154).trimStart('0')
        for (field in listOf(
            digits.padStart(6, '0') + "\u0000 ", digits.padStart(7, '0') + "\u0000",
            digits.padStart(8, '0'), " " + digits.padStart(6, '0') + " ",
            "  " + digits.padStart(5, '0') + "\u0000",
        )) {
            assertEquals(8, field.length)
            val changed = h.copyOf().also { field.encodeToByteArray().copyInto(it, 148) }
            assertEquals("a.txt", reader(changed + ByteArray(1024)).entries().single().name)
        }
    }

    @Test
    fun arbitraryBlocksAndTextAreRejected() {
        val arbitrary = ByteArray(512) { 65 }.also { it[156] = 48 }
        val text = "this is plain text, not a tar archive.\n".repeat(80).encodeToByteArray()
        val random = ByteArray(2048) { (it * 71 + 33).toByte() }
        val magicOnly = ByteArray(512).also { "ustar".encodeToByteArray().copyInto(it, 257) }
        for (bytes in listOf(arbitrary + ByteArray(1024), text, random, magicOnly)) invalid(bytes)
    }

    @Test
    fun checksumPrecedesNumericFieldsAndPayloadReads() {
        val h = header(type = 'L', size = Long.MAX_VALUE)
        h[148] = 48; h[149] = 48; h[150] = 48; h[151] = 48; h[152] = 48; h[153] = 48
        var reads = 0
        val source = object : RandomAccessSource {
            override val size = Long.MAX_VALUE
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                assertEquals(0L, offset)
                assertEquals(512, length)
                reads++
                h.copyInto(into, intoOffset)
                return length
            }
        }
        val error = assertFailsWith<ArchiveFormatException> { TarReader(source).entries() }
        assertEquals(0L, error.offset)
        assertTrue(error.message!!.contains("checksum"))
        assertEquals(1, reads)
    }

    @Test
    fun ignoredRecordTypesAlsoRequireValidHeaders() {
        for (type in listOf('0', '5', 'L', 'x', 'g', '2', '3', '4', '6', 'Z')) {
            val h = header(type = type).also { it[500] = 1 }
            invalid(h + ByteArray(1024))
        }
    }

    @Test
    fun aLaterBadHeaderDoesNotReturnOrCacheTheValidPrefix() {
        val first = header("first")
        val second = header("second")
        val bytes = first + second + ByteArray(1024)
        bytes[512 + 500] = 1
        val archive = reader(bytes)
        repeat(2) {
            val error = assertFailsWith<ArchiveFormatException> { archive.entries() }
            assertEquals(512L, error.offset)
        }
        second.copyInto(bytes, 512)
        assertEquals(listOf("first", "second"), archive.entries().map { it.name })
    }

    @Test
    fun partialHeaderRecordsAreRejectedAtTheirStart() {
        for (length in listOf(1, 2, 147, 148, 511)) {
            invalid(ByteArray(length), message = "truncated header")
            invalid(ByteArray(length) { 65 }, message = "truncated header")
            invalid(header() + ByteArray(length), offset = 512, message = "truncated header")
        }
        val negativeSize = object : RandomAccessSource {
            override val size = -1L
            override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int = error("invalid source must not be read")
        }
        val error = assertFailsWith<ArchiveFormatException> { TarReader(negativeSize).entries() }
        assertEquals(0L, error.offset)
    }

    @Test
    fun completeRecordsDoNotRequireAnEofTrailer() {
        for (empty in listOf(byteArrayOf(), ByteArray(512), ByteArray(1024))) {
            assertTrue(reader(empty).entries().isEmpty())
        }
        val records = header("first") + header("second")
        for (trailer in listOf(byteArrayOf(), ByteArray(512), ByteArray(1024))) {
            assertEquals(listOf("first", "second"), reader(records + trailer).entries().map { it.name })
        }
    }

    @Test
    fun checksumOffsetsRemainLongOnSparseSources() {
        for (nextAt in listOf(8L * 1024 * 1024 * 1024, (Long.MAX_VALUE / 512 - 3) * 512)) {
            val first = header("first", nextAt - 512)
            val second = header("bad").also { it[500] = 1 }
            var reads = 0
            val source = object : RandomAccessSource {
                override val size = nextAt + 512
                override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
                    assertEquals(512, length)
                    (if (offset == 0L) first else {
                        assertEquals(nextAt, offset)
                        second
                    }).copyInto(into, intoOffset)
                    reads++
                    return length
                }
            }
            val error = assertFailsWith<ArchiveFormatException> { TarReader(source).entries() }
            assertEquals(nextAt, error.offset)
            assertTrue(error.message!!.contains("checksum"))
            assertEquals(2, reads)
        }
    }

    @Test
    fun legacySignedChecksumWorksWithBinaryNumericFields() {
        val h = header(size = 3)
        number(h, 3, 124, 12)
        h[124] = 0x80.toByte() // explicit positive base-256 size
        h.fill(0, 125, 135)
        h[135] = 3
        number(h, -1, 136, 12)
        checksum(h, signed = true)
        val archive = reader(h + "ABC".encodeToByteArray() + ByteArray(509))
        val entry = archive.entries().single()
        assertEquals(-1L, entry.lastModifiedEpochSeconds)
        assertContentEquals("ABC".encodeToByteArray(), archive.read(entry))
    }

    @Test
    fun validV7HeadersNeedNoUstarMagic() {
        val h = header().also { it.fill(0, 257, 265); checksum(it) }
        assertEquals("a.txt", reader(h).entries().single().name)
    }

    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
