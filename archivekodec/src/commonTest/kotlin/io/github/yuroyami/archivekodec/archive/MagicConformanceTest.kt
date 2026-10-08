package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.zip.ZipFormat
import io.github.yuroyami.archivekodec.hex
import io.github.yuroyami.archivekodec.wrapper.Zlib
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MagicConformanceTest {
    // Independent v7 reference header: "f.txt", size 1, mode 0644.
    private val v7Header = hex(
        "662e74787400000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
            "000000003030303036343400303030303030300030303030303030003030303030303030303031003030303030303030" +
            "30303000303035353033002030"
    ) + ByteArray(355)


    private fun assertFormat(expected: ArchiveFormat, bytes: ByteArray) {
        assertEquals(expected, Magic.sniff(bytes), "header of ${bytes.size} bytes")
        assertEquals(expected, ArchiveKodec.sniff(bytes))
        assertEquals(expected, Magic.sniff(ByteArrayRandomAccessSource(bytes)))
        assertEquals(expected, ArchiveKodec.sniff(ByteArrayRandomAccessSource(bytes)))
    }

    private fun checksum(header: ByteArray, signed: Boolean = false) {
        header.fill(32, 148, 156)
        val value = header.sumOf { if (signed) it.toInt() else it.toInt() and 255 }
        (value.toString(8).padStart(6, '0') + "\u0000 ").encodeToByteArray().copyInto(header, 148)
    }

    private fun tarHeader(name: ByteArray, magic: String, signed: Boolean = false): ByteArray =
        v7Header.copyOf().also {
            it.fill(0, 0, 100)
            name.copyInto(it)
            magic.encodeToByteArray().copyInto(it, 257)
            checksum(it, signed)
        }

    private fun openTar(bytes: ByteArray, expectedName: String, decoder: ArchiveNameDecoder? = null) {
        val source = ByteArrayRandomAccessSource(bytes)
        val opened = if (decoder == null) ArchiveKodec.open(source) else ArchiveKodec.open(source, decoder)
        opened.use { reader ->
            assertEquals(ArchiveFormat.TAR, reader.format)
            val entry = reader.entries().single()
            assertEquals(expectedName, entry.name)
            assertEquals(1L, entry.size)
            assertContentEquals(byteArrayOf(97), reader.read(entry))
        }
    }

    @Test
    fun reportedTextAndMalformedSignaturesAreUnknown() {
        for (bytes in listOf(
            "x = 1\n".encodeToByteArray(), "BZh is a name".encodeToByteArray(),
            hex("504b05ff"), hex("504b0300"), hex("504b0700"), hex("504b06ff"),
            hex("425a6830314159265359"), hex("425a683a177245385090"),
            hex("425a6839314159265358"), hex("789d03"), hex("889c03"),
        )) assertFormat(ArchiveFormat.UNKNOWN, bytes)
    }

    @Test
    fun zipSignaturesNeedFourCompleteKnownBytes() {
        for (signature in listOf("504b0304", "504b0506", "504b0708", "504b0505", "504b0606")) {
            val bytes = hex(signature)
            for (length in 0 until 4) assertFormat(ArchiveFormat.UNKNOWN, bytes.copyOf(length))
            assertFormat(ArchiveFormat.ZIP, bytes)
        }
        for (signature in listOf("504b0305", "504b0504", "504b0707", "504b0605", "504b0102")) {
            assertFormat(ArchiveFormat.UNKNOWN, hex(signature))
        }
    }

    @Test
    fun bzip2NeedsAValidLevelAndCompleteBlockOrEndMarker() {
        // libbzip2 1.0.8: empty input and b"a" at each level have these prefixes.
        for (marker in listOf("314159265359", "177245385090")) {
            for (level in 1..9) {
                val bytes = "BZh$level".encodeToByteArray() + hex(marker)
                for (length in 0 until 10) assertFormat(ArchiveFormat.UNKNOWN, bytes.copyOf(length))
                assertFormat(ArchiveFormat.BZIP2, bytes)
                for (offset in 4 until 10) {
                    val changed = bytes.copyOf()
                    changed[offset] = (changed[offset].toInt() xor 1).toByte()
                    assertFormat(ArchiveFormat.UNKNOWN, changed)
                }
            }
        }
    }

    @Test
    fun zlibStoredFixedDynamicAndDictionaryCandidatesRemainRecognizable() {
        // CPython 3.14.7 zlib reference streams at levels 0/1/6/9. Longer values
        // retain their identifying prefix; sniffing does not validate their trailers.
        val plain = listOf(
            "7801010000ffff00000001", "7801030000000001", "789c030000000001", "78da030000000001",
            "7801010100feff6100620062", "78014b040000620062", "789c4b040000620062", "78da4b040000620062",
            "789cedcfc55615000040c127",
        )
        for (vector in plain) {
            val bytes = hex(vector)
            for (length in 0..1) assertFormat(ArchiveFormat.UNKNOWN, bytes.copyOf(length))
            for (length in 2..bytes.size) assertFormat(ArchiveFormat.ZLIB, bytes.copyOf(length))
        }

        val dictionary = listOf(
            "783f2e5905ce010000ffff00000001", "783f2e5905ce030000000001",
            "78bb2e5905ce030000000001", "78f92e5905ce030000000001",
            "783f2e5905ce01a90056ff61", "783f2e5905ce1b9c84424a66",
            "78bb2e5905ce1b9c84424a66", "78f92e5905ce1b9c84424a66",
            "78bb2e5905ceedcfc5521000", "78202e5905ce030000000001",
        )
        for (vector in dictionary) {
            val bytes = hex(vector)
            for (length in 0..6) assertFormat(ArchiveFormat.UNKNOWN, bytes.copyOf(length))
            for (length in 7..bytes.size) assertFormat(ArchiveFormat.ZLIB, bytes.copyOf(length))
        }
        // Recognizing a valid dictionary stream does not claim dictionary support.
        assertFailsWith<ArchiveUnsupportedException> { Zlib.decompress(hex("78202e5905ce030000000001")) }
    }

    @Test
    fun reservedDeflateBlockTypesAreCheckedAfterTheDictionaryId() {
        for (value in listOf(6, 7, 0xfe, 0xff)) {
            assertFormat(ArchiveFormat.UNKNOWN, hex("789c") + byteArrayOf(value.toByte()))
            assertFormat(ArchiveFormat.UNKNOWN, hex("78bb2e5905ce") + byteArrayOf(value.toByte()))
        }
        // Bits in DICTID are unrelated to the first DEFLATE block's type.
        assertFormat(ArchiveFormat.ZLIB, hex("78bb07ffffff03"))
        assertFormat(ArchiveFormat.ZLIB, hex("78bb06ffffff03"))
    }

    @Test
    fun v7AndTarNamesWithOtherFormatPrefixesOpenAndRead() {
        openTar(v7Header + byteArrayOf(97) + ByteArray(511 + 1024), "f.txt")
        val prefixes = listOf(
            hex("1f8b0801"), hex("504b0304"), hex("504b0506"), hex("504b0708"),
            hex("504b0505"), hex("504b0606"), hex("fd377a585a00"), hex("28b52ffd"),
            hex("04224d18"), hex("425a6839314159265359"), hex("377abcaf271c"),
            "!<arch>\n".encodeToByteArray(), "070701".encodeToByteArray(), "070702".encodeToByteArray(),
            "070707".encodeToByteArray(), hex("526172211a07"), hex("789c03"),
            hex("78bb2e5905ce03"), "BZh".encodeToByteArray(),
        )
        for (magic in listOf("", "ustar\u000000", "ustar  \u0000")) {
            for (prefix in prefixes) {
                val name = prefix + ".txt".encodeToByteArray()
                val header = tarHeader(name, magic)
                val firstNul = name.indexOf(0).let { if (it < 0) name.size else it }
                val expectedName = ArchiveNameDecoder.CP437.decode(name.copyOf(firstNul))
                assertFormat(ArchiveFormat.TAR, header)
                openTar(header + byteArrayOf(97) + ByteArray(511 + 1024), expectedName, ArchiveNameDecoder.CP437)
            }
        }
        for (magic in listOf("", "ustar\u000000", "ustar  \u0000")) {
            val header = tarHeader("é.txt".encodeToByteArray(), magic, signed = true)
            assertFormat(ArchiveFormat.TAR, header)
            openTar(header + byteArrayOf(97) + ByteArray(511), "é.txt")
        }
    }

    @Test
    fun tarNeedsACompleteChecksumRecordOrCompleteZeroMarker() {
        for (length in 0 until 512) {
            assertFormat(ArchiveFormat.UNKNOWN, v7Header.copyOf(length))
            assertFormat(ArchiveFormat.UNKNOWN, ByteArray(length))
        }
        for (header in listOf(
            ByteArray(512).also { "ustar".encodeToByteArray().copyInto(it, 257) },
            tarHeader("ordinary.txt".encodeToByteArray(), "ustar\u000000").also { it[500] = 1 },
            tarHeader("ordinary.txt".encodeToByteArray(), "ustar\u000000").also { it.fill(0, 148, 156) },
        )) assertFormat(ArchiveFormat.UNKNOWN, header)

        for (bytes in listOf(ByteArray(512), ByteArray(1024), ByteArray(512) + ByteArray(513) { 65 })) {
            assertFormat(ArchiveFormat.TAR, bytes)
            ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { assertTrue(it.entries().isEmpty()) }
        }
        assertFormat(ArchiveFormat.UNKNOWN, byteArrayOf())
    }

    @Test
    fun tarDetectionLeavesPayloadAndLaterHeaderValidationToTheReader() {
        assertFormat(ArchiveFormat.TAR, v7Header)
        ArchiveKodec.open(ByteArrayRandomAccessSource(v7Header)).use {
            assertEquals(512L, assertFailsWith<ArchiveFormatException> { it.entries() }.offset)
        }
        val invalidSecond = v7Header.copyOf().also { it[500] = 1 }
        val bytes = v7Header + byteArrayOf(97) + ByteArray(511) + invalidSecond
        assertFormat(ArchiveFormat.TAR, bytes)
        ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use {
            assertEquals(1024L, assertFailsWith<ArchiveFormatException> { it.entries() }.offset)
        }
    }

    private fun emptyZip(signatureLength: Int?, zip64: Boolean): ByteArray {
        val signature = signatureLength?.let { length ->
            ByteArray(6 + length).also {
                putLe(it, 0, ZipFormat.CEN_SIGNATURE, 4)
                putLe(it, 4, length.toLong(), 2)
            }
        } ?: byteArrayOf()
        val end = ByteArray(22)
        putLe(end, 0, ZipFormat.EOCD, 4)
        putLe(end, 12, signature.size.toLong(), 4)
        if (!zip64) return signature + end

        val z64 = ByteArray(56)
        putLe(z64, 0, ZipFormat.Z64_EOCD, 4)
        putLe(z64, 4, 44, 8)
        putLe(z64, 12, 45, 2)
        putLe(z64, 14, 45, 2)
        putLe(z64, 40, signature.size.toLong(), 8)
        val locator = ByteArray(20)
        putLe(locator, 0, ZipFormat.Z64_LOC, 4)
        putLe(locator, 8, signature.size.toLong(), 8)
        putLe(locator, 16, 1, 4)
        putLe(end, 8, 65535, 2)
        putLe(end, 10, 65535, 2)
        putLe(end, 12, 0xffffffff, 4)
        putLe(end, 16, 0xffffffff, 4)
        return signature + z64 + locator + end
    }

    @Test
    fun emptyClassicZipDigitalSignatureAndZip64ContainersOpen() {
        for (zip64 in listOf(false, true)) for (signatureLength in listOf(null, 0, 4)) {
            val bytes = emptyZip(signatureLength, zip64)
            assertFormat(ArchiveFormat.ZIP, bytes)
            ArchiveKodec.open(ByteArrayRandomAccessSource(bytes)).use { reader ->
                assertEquals(ArchiveFormat.ZIP, reader.format)
                assertTrue(reader.entries().isEmpty())
            }
        }
    }
}
