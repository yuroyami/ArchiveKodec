package io.github.yuroyami.archivekodec.wrapper

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.hex
import kotlin.test.*

class GzipHeaderTest {
    private val payload = "payload".encodeToByteArray()
    private val python = hex("1f8b080800f1536502ff7265706f72742e747874002b48acccc94f4c0100156a2c4207000000")

    @Test fun readsTheCpythonFilenameTimeOsAndCompressionHint() {
        val header = assertNotNull(Gzip.readHeader(python))
        assertEquals("report.txt", header.name)
        assertEquals(1_700_000_000L, header.mtimeEpochSeconds)
        assertEquals(255, header.os)
        assertEquals(2, header.extraFlags)
        assertNull(header.comment)
        assertNull(header.extra)
        assertFalse(header.isText)
        assertFalse(header.hasHeaderCrc)
        assertContentEquals(payload, Gzip.decompress(python))
    }

    @Test fun defaultHeaderAndRegisteredCodecPreserveReproducibleOutput() {
        val defaults = GzipHeader.DEFAULT
        assertEquals(GzipHeader(), defaults)
        assertEquals(GzipHeader.Builder().build(), defaults)
        assertEquals(0L, defaults.mtimeEpochSeconds)
        assertEquals(255, defaults.os)
        assertEquals(0, defaults.extraFlags)
        assertFalse(defaults.isText)
        assertFalse(defaults.hasHeaderCrc)
        assertNull(defaults.name)
        assertNull(defaults.comment)
        assertNull(defaults.extra)
        val result = Gzip.compress(payload)
        assertContentEquals(hex("1f8b08000000000000ff2b48acccc94f4c0100156a2c4207000000"), result)
        assertContentEquals(hex("1f8b08000000000000ff"), result.copyOf(10))
        assertContentEquals(result, Gzip.compress(payload, GzipHeader()))
        assertContentEquals(result, ArchiveKodec.gzip.compress(payload))
        assertEquals(defaults, Gzip.readHeader(result))
    }

    @Test fun writesAndReadsEveryHeaderFlagCombination() {
        for (flags in 0..31) {
            val header = GzipHeader {
                isText = flags and 1 != 0
                hasHeaderCrc = flags and 2 != 0
                if (flags and 4 != 0) extra = byteArrayOf(65, 66, 2, 0, -1, -128)
                if (flags and 8 != 0) name = "résumé.txt"
                if (flags and 16 != 0) comment = "café\ncomment"
                mtimeEpochSeconds = 0xffffffffL
                os = 3
                extraFlags = 4
            }
            val bytes = Gzip.compress(payload, header)
            assertEquals(flags, bytes[3].toInt() and 255)
            assertContentEquals(hex("ffffffff0403"), bytes.copyOfRange(4, 10))
            assertEquals(header, Gzip.readHeader(bytes))
            assertContentEquals(payload, Gzip.decompress(bytes))
        }
    }

    @Test fun writesLatin1BytesRatherThanUtf8AndPreservesAllNonzeroCharacters() {
        val text = buildString { for (code in 1..255) append(code.toChar()) }
        val header = GzipHeader { name = text; comment = text }
        val bytes = Gzip.compress(byteArrayOf(), header)
        val expected = ByteArray(256) { (it + 1).toByte() }
        assertContentEquals(expected, bytes.copyOfRange(10, 266))
        assertContentEquals(expected, bytes.copyOfRange(266, 522))
        assertEquals(header, Gzip.readHeader(bytes))
        assertContentEquals(byteArrayOf(), Gzip.decompress(bytes))
    }

    @Test fun emptyOptionalFieldsRemainDistinctFromAbsentFields() {
        val header = GzipHeader { name = ""; comment = ""; extra = byteArrayOf() }
        val bytes = Gzip.compress(payload, header)
        assertEquals(28, bytes[3].toInt())
        assertContentEquals(ByteArray(4), bytes.copyOfRange(10, 14))
        assertEquals(header, Gzip.readHeader(bytes))
        assertNotEquals(GzipHeader.DEFAULT, header)
        assertNotEquals(GzipHeader(), GzipHeader { extra = byteArrayOf() })
    }

    @Test fun extraFieldLengthUsesTheCompleteUnsigned16BitRange() {
        for (length in listOf(0, 1, 255, 256, 32768, 65535)) {
            val extra = ByteArray(length) { it.toByte() }
            val header = GzipHeader { this.extra = extra; hasHeaderCrc = true }
            val bytes = Gzip.compress(payload, header)
            assertEquals(length, (bytes[10].toInt() and 255) or ((bytes[11].toInt() and 255) shl 8))
            assertContentEquals(extra, assertNotNull(Gzip.readHeader(bytes)).extra)
            assertContentEquals(payload, Gzip.decompress(bytes))
        }
        assertFailsWith<IllegalArgumentException> { GzipHeader { extra = ByteArray(65536) } }
    }

    @Test fun headerCrcIsTheLow16BitsOfCrc32AndIsCheckedBeforeReturningMetadata() {
        val header = GzipHeader { name = "a"; comment = "b"; extra = byteArrayOf(7, 8, 9); hasHeaderCrc = true }
        val bytes = Gzip.compress(payload, header)
        val crcOffset = 10 + 2 + 3 + 2 + 2
        val expected = Crc32.of(bytes.copyOf(crcOffset)).toInt() and 65535
        assertEquals(expected, (bytes[crcOffset].toInt() and 255) or ((bytes[crcOffset + 1].toInt() and 255) shl 8))
        for (index in listOf(4, 8, 9, 12, crcOffset, crcOffset + 1)) {
            val bad = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals(crcOffset.toLong(), assertFailsWith<ArchiveFormatException> { Gzip.readHeader(bad) }.offset)
            assertEquals(crcOffset.toLong(), assertFailsWith<ArchiveFormatException> { Gzip.decompress(bad) }.offset)
        }
    }

    @Test fun inspectionRequiresOnlyACompleteHeaderAndDoesNotValidateTheBody() {
        val header = GzipHeader { name = "a"; hasHeaderCrc = true }
        val bytes = Gzip.compress(payload, header)
        val headerEnd = 14
        assertEquals(header, Gzip.readHeader(bytes.copyOf(headerEnd)))
        assertEquals(header, Gzip.readHeader(bytes.copyOf(headerEnd) + byteArrayOf(-1, -1)))
        val badTrailer = bytes.copyOf().also { it[it.lastIndex] = 1 }
        assertEquals(header, Gzip.readHeader(badTrailer))
        assertFailsWith<ArchiveFormatException> { Gzip.decompress(badTrailer) }
    }

    @Test fun inspectsOnlyTheFirstMemberWhileDecompressionStillValidatesAllMembers() {
        val firstHeader = GzipHeader { name = "first"; mtimeEpochSeconds = 1 }
        val secondHeader = GzipHeader { name = "second"; comment = "later"; hasHeaderCrc = true }
        val first = Gzip.compress(payload, firstHeader)
        val second = Gzip.compress(byteArrayOf(7), secondHeader)
        assertEquals(firstHeader, Gzip.readHeader(first + second))
        assertEquals(firstHeader, Gzip.readHeader(first + byteArrayOf(1)))
        assertContentEquals(payload + byteArrayOf(7), Gzip.decompress(first + second))
        val broken = second.copyOf().also { it[4] = 1 }
        assertEquals(firstHeader, Gzip.readHeader(first + broken))
        val error = assertFailsWith<ArchiveFormatException> { Gzip.decompress(first + broken) }
        assertEquals(first.size + 23L, error.offset)
    }

    @Test fun distinguishesNonGzipFromRecognizedMalformedOrTruncatedHeaders() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0x1f), byteArrayOf(0x1f, 0), ByteArray(10), Zlib.compress(payload))) {
            assertNull(Gzip.readHeader(bytes))
        }
        val header = GzipHeader { name = "a"; comment = "b"; extra = byteArrayOf(1, 2); hasHeaderCrc = true }
        val bytes = Gzip.compress(payload, header)
        for (length in 2 until 20) assertFailsWith<ArchiveFormatException>("length $length") { Gzip.readHeader(bytes.copyOf(length)) }
        for (index in listOf(2, 3)) {
            val bad = bytes.copyOf().also { it[index] = if (index == 2) 7 else 0x20 }
            assertEquals(2L, assertFailsWith<ArchiveFormatException> { Gzip.readHeader(bad) }.offset)
        }
        assertFailsWith<ArchiveFormatException> { Gzip.readHeader(hex("1f8b08040000000000ffffff")) }
    }

    @Test fun validatesEveryConstructionPathWithoutConsumingFailedBuilders() {
        val invalid = listOf<GzipHeader.Builder.() -> Unit>(
            { name = "nul\u0000name" }, { comment = "nul\u0000comment" },
            { name = "snow-雪" }, { comment = "\u0100" }, { name = "\uD800" },
            { mtimeEpochSeconds = -1 }, { mtimeEpochSeconds = 0x100000000L },
            { os = -1 }, { os = 256 }, { extraFlags = -1 }, { extraFlags = 256 },
            { extra = ByteArray(65536) },
        )
        for (configure in invalid) {
            assertFailsWith<IllegalArgumentException> { GzipHeader(configure) }
            assertFailsWith<IllegalArgumentException> { GzipHeader.Builder().apply(configure).build() }
            assertFailsWith<IllegalArgumentException> { GzipHeader.DEFAULT.copy(configure) }
        }
        val builder = GzipHeader.Builder().apply { os = -1 }
        assertFailsWith<IllegalArgumentException> { builder.build() }
        builder.os = 0
        assertEquals(0, builder.build().os)
    }

    @Test fun headersOwnExtraSnapshotsAcrossBuildersCopiesGettersAndParsing() {
        val input = byteArrayOf(7, 8)
        val builder = GzipHeader.Builder().apply { extra = input; name = "a" }
        val original = builder.build()
        val hash = original.hashCode()
        input.fill(0)
        builder.name = "b"
        builder.extra = byteArrayOf(9)
        original.extra!!.fill(0)
        assertEquals("a", original.name)
        assertContentEquals(byteArrayOf(7, 8), original.extra)
        assertEquals(hash, original.hashCode())
        lateinit var copyBuilder: GzipHeader.Builder
        val copied = original.copy { extra!![0] = 3; copyBuilder = this }
        copyBuilder.extra!!.fill(0)
        assertContentEquals(byteArrayOf(7, 8), original.extra)
        assertContentEquals(byteArrayOf(3, 8), copied.extra)
        val bytes = Gzip.compress(payload, original)
        val parsed = assertNotNull(Gzip.readHeader(bytes))
        bytes.fill(0)
        assertEquals(original, parsed)
        parsed.extra!!.fill(0)
        assertEquals(original, parsed)
    }

    @Test fun valueMethodsIncludeAllFieldsAndDescriptionsDoNotDumpMetadata() {
        val original = GzipHeader { name = "private-name"; comment = "private-comment"; extra = byteArrayOf(1) }
        val copied = original.copy()
        assertNotSame(original, copied)
        assertEquals(original, copied)
        assertEquals(original.hashCode(), copied.hashCode())
        val changes = listOf<GzipHeader.Builder.() -> Unit>(
            { name = null }, { comment = null }, { mtimeEpochSeconds = 1 }, { os = 0 },
            { extraFlags = 255 }, { isText = true }, { hasHeaderCrc = true }, { extra = byteArrayOf(2) }, { extra = null },
        )
        for (configure in changes) assertNotEquals(original, original.copy(configure))
        assertFalse(original.equals(null))
        assertFalse(original.equals("header"))
        val description = original.toString()
        assertTrue(description.length < 240)
        assertFalse(description.contains("private-name"))
        assertFalse(description.contains("private-comment"))
    }
}
