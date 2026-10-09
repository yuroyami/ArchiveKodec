package io.github.yuroyami.archivekodec.archive

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZipHeaderConsistencyTest {
    private val payload = "ABC".encodeToByteArray()
    private fun zip(method: ZipMethod = ZipMethod.STORED, name: String = "a.txt") =
        ZipWriter.write(listOf(ZipWriter.FileSpec(name, payload, method)))
    private fun central(bytes: ByteArray) = readU32Le(bytes, bytes.size - 6).toInt()
    private fun flags(bytes: ByteArray, value: Int) {
        putLe(bytes, 6, value.toLong(), 2)
        putLe(bytes, central(bytes) + 8, value.toLong(), 2)
    }
    private fun method(bytes: ByteArray, value: Int) {
        putLe(bytes, 8, value.toLong(), 2)
        putLe(bytes, central(bytes) + 10, value.toLong(), 2)
    }
    private fun invalidListing(bytes: ByteArray, offset: Long, name: String = "a.txt") {
        val error = assertFailsWith<ArchiveFormatException> { ZipReader(ByteArrayRandomAccessSource(bytes)).entries() }
        assertEquals(name, error.entryName)
        assertEquals(offset, error.offset)
    }

    private class PayloadSource(bytes: ByteArray) : RandomAccessSource {
        private val backing = ByteArrayRandomAccessSource(bytes)
        private val start = 30L + readU16Le(bytes, 26) + readU16Le(bytes, 28)
        private val end = start + readU32Le(bytes, centralOffset(bytes) + 20)
        var armed = false
        var payloadReads = 0
        var accesses = 0
        override val size get(): Long { accesses++; return backing.size }
        override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
            accesses++
            if (armed && offset < end && offset + length > start) payloadReads++
            return backing.read(offset, into, intoOffset, length)
        }
        companion object {
            private fun centralOffset(bytes: ByteArray) = readU32Le(bytes, bytes.size - 6).toInt()
        }
    }

    @Test
    fun listingRejectsALocalMethodContradiction() {
        for ((local, directory) in listOf(8 to 0, 0 to 8, 9 to 0, 0 to 9)) {
            val bytes = zip()
            putLe(bytes, 8, local.toLong(), 2)
            putLe(bytes, central(bytes) + 10, directory.toLong(), 2)
            invalidListing(bytes, 8)
        }
    }

    @Test
    fun listingRejectsALocalNameContradiction() {
        val bytes = zip()
        bytes[30] = 'b'.code.toByte()
        invalidListing(bytes, 30)
    }

    @Test
    fun listingRejectsALocalFlagContradiction() {
        val bytes = zip()
        putLe(bytes, 6, 0, 2)
        invalidListing(bytes, 6)
    }

    @Test
    fun namesAreComparedAsBytesEvenWhenTextDecodingCollides() {
        val bytes = zip()
        bytes[30] = 0x80.toByte()
        bytes[central(bytes) + 46] = 0x81.toByte()
        val localName = bytes.copyOfRange(30, 35).decodeToString()
        val directoryName = bytes.copyOfRange(central(bytes) + 46, central(bytes) + 51).decodeToString()
        assertEquals(localName, directoryName)
        invalidListing(bytes, 30, directoryName)
    }

    @Test
    fun contradictoryNameLengthsAreRejectedAtTheLengthField() {
        for (length in listOf(0L, 4L, 6L, 65535L)) {
            val bytes = zip()
            putLe(bytes, 26, length, 2)
            invalidListing(bytes, 26)
        }
    }

    @Test
    fun contradictoryFlagBitsAreRejectedInEitherHeader() {
        for (bit in 0..15) {
            if (bit == 13) continue // Masked-header encryption is tested as unsupported below.
            for (field in listOf(6, central(zip()) + 8)) {
                val bytes = zip()
                putLe(bytes, field, (0x800 xor (1 shl bit)).toLong(), 2)
                invalidListing(bytes, 6)
            }
        }
    }

    @Test
    fun extractionRechecksLocalFieldsBeforePayloadReadsAndCanRecover() {
        val changes = listOf(0 to 0, 6 to 1, 8 to 8, 26 to 4, 28 to 1, 30 to 'b'.code)
        for ((field, value) in changes) {
            val bytes = zip()
            val source = PayloadSource(bytes)
            val reader = ZipReader(source)
            val entry = reader.entries().single()
            source.armed = true
            val saved = bytes[field]
            bytes[field] = value.toByte()
            val error = assertFailsWith<ArchiveFormatException> { reader.read(entry) }
            assertEquals("a.txt", error.entryName)
            assertEquals(field.toLong(), error.offset)
            assertEquals(0, source.payloadReads)
            bytes[field] = saved
            assertContentEquals(payload, reader.read(entry))
            assertTrue(source.payloadReads > 0)
        }
    }

    @Test
    fun suppliedMetadataCannotDisableIntegrityChecksOrChangeRouting() {
        val source = PayloadSource(zip())
        val reader = ZipReader(source)
        val entry = reader.entries().single()
        source.armed = true
        val altered = listOf(
            entry.copy(name = "renamed"), entry.copy(size = 0), entry.copy(isDirectory = true),
            entry.copy(lastModifiedEpochSeconds = 1), entry.copy(compressedSize = 0),
            entry.copy(codec = CodecId.DEFLATE), entry.copy(isEncrypted = true),
            entry.copy(crc32 = null), entry.copy(crc32 = 0),
        )
        for (member in altered) {
            val error = assertFailsWith<ArchiveFormatException> { reader.read(member) }
            assertEquals("a.txt", error.entryName)
            assertEquals(0L, error.offset)
            assertTrue(error.message!!.contains("metadata"))
        }
        assertEquals(0, source.payloadReads)
        assertContentEquals(payload, reader.read(entry))
    }

    @Test
    fun entriesThisReaderDidNotListAreRejectedBeforeSourceReads() {
        val source = PayloadSource(zip())
        val reader = ZipReader(source)
        val entry = reader.entries().single()
        val foreign = ZipReader(ByteArrayRandomAccessSource(zip())).use { it.entries().single() }
        val handBuilt = ArchiveEntry(entry.name, entry.size, entry.isDirectory, entry.lastModifiedEpochSeconds,
            entry.compressedSize, entry.codec, entry.isEncrypted, entry.crc32)
        source.armed = true
        val accesses = source.accesses
        for (member in listOf(handBuilt, foreign, foreign.copy())) {
            assertEquals(entry, member)
            val error = assertFailsWith<ArchiveFormatException> { reader.read(member) }
            assertEquals(member.name, error.entryName)
            assertNull(error.offset)
            assertTrue(error.message!!.contains("not listed by this reader"), error.message)
        }
        assertEquals(accesses, source.accesses)
        assertEquals(0, source.payloadReads)
    }

    @Test
    fun unchangedValueCopiesAndUtf8OrEmptyNamesRemainReadable() {
        for (method in ZipMethod.entries) {
            for (name in listOf("", "a.txt", "é/文件😀.txt")) {
                val reader = ZipReader(ByteArrayRandomAccessSource(zip(method, name)))
                val entry = reader.entries().single()
                assertEquals(name, entry.name)
                assertFalse(entry.isEncrypted)
                assertEquals(if (method == ZipMethod.STORED) 0 else 8, entry.zipMethod)
                assertEquals(entry.zipMethod, entry.copy().zipMethod)
                assertContentEquals(payload, reader.read(entry.copy()))
            }
        }
    }

    @Test
    fun changingTheReturnedListCannotReplaceTheTrustedDirectoryRecord() {
        val reader = ZipReader(ByteArrayRandomAccessSource(zip()))
        val listing = reader.entries()
        val entry = listing.single()
        val changed = entry.copy(crc32 = null)
        @Suppress("UNCHECKED_CAST")
        runCatching { (listing as MutableList<ArchiveEntry>)[0] = changed }
        assertEquals(listOf(entry), reader.entries())
        assertFailsWith<ArchiveFormatException> { reader.read(changed) }
        assertContentEquals(payload, reader.read(entry))
    }

    @Test
    fun definedDeflateCompressionOptionsRemainReadable() {
        for (options in listOf(0, 2, 4, 6)) {
            val bytes = zip(ZipMethod.DEFLATED)
            flags(bytes, 0x800 or options)
            val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
            assertContentEquals(payload, reader.read(reader.entries().single()))
        }
    }

    @Test
    fun encryptedFlagsAreReportedAndRejectedBeforeCodecInvocation() {
        for (flag in listOf(0x801, 0x840, 0x841)) {
            for (number in listOf(0, 8, 9, 99)) {
                val bytes = zip()
                flags(bytes, flag)
                method(bytes, number)
                val source = PayloadSource(bytes)
                val reader = ZipReader(source)
                val entry = reader.entries().single()
                assertTrue(entry.isEncrypted, "flags $flag method $number")
                assertEquals(number, entry.zipMethod)
                assertEquals(number, entry.copy().zipMethod)
                source.armed = true
                val error = assertFailsWith<ArchiveUnsupportedException> { reader.read(entry) }
                assertEquals("a.txt", error.entryName)
                assertEquals(if (number == 99) 8L else 6L, error.offset)
                assertTrue(error.message!!.contains("encryption"))
                assertEquals(0, source.payloadReads)
            }
        }
    }

    @Test
    fun aesMethodMarkerIsEncryptedEvenWithoutTheFlag() {
        val bytes = zip()
        method(bytes, 99)
        val source = PayloadSource(bytes)
        val reader = ZipReader(source)
        val entry = reader.entries().single()
        assertTrue(entry.isEncrypted)
        assertEquals(99, entry.zipMethod)
        assertEquals(CodecId("zip-method-99"), entry.codec)
        assertNull(Codecs.byId(entry.codec))
        source.armed = true
        val error = assertFailsWith<ArchiveUnsupportedException> { reader.read(entry) }
        assertEquals("a.txt", error.entryName)
        assertEquals(8L, error.offset)
        assertTrue(error.message!!.contains("encryption"))
        assertEquals(0, source.payloadReads)
    }

    @Test
    fun realZipCryptoArchiveIsListedAsEncryptedWithoutDecodingCiphertext() {
        // Info-ZIP 3.0: zip -X -P secret -9 encrypted.zip a.txt. CPython verifies
        // the password and plaintext "hello hello hello hello hello" independently.
        val hex = "504b030414000b000800aa21475df6d25338170000001d00000005000000612e747874d4eb7e092af633b49076aa42e0fe951f240a21f5636bb5504b0708f6d25338170000001d000000504b01021e0314000b000800aa21475df6d25338170000001d000000050000000000000001000000a48100000000612e747874504b05060000000001000100330000004a0000000000"
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val source = PayloadSource(bytes)
        val reader = ZipReader(source)
        val entry = reader.entries().single()
        assertEquals("a.txt", entry.name)
        assertEquals(29L, entry.size)
        assertEquals(23L, entry.compressedSize)
        assertEquals(CodecId.DEFLATE, entry.codec)
        assertEquals(8, entry.zipMethod)
        assertTrue(entry.isEncrypted)
        source.armed = true
        val error = assertFailsWith<ArchiveUnsupportedException> { reader.read(entry) }
        assertEquals("a.txt", error.entryName)
        assertEquals(6L, error.offset)
        assertTrue(error.message!!.contains("encryption"))
        assertEquals(0, source.payloadReads)
    }

    @Test
    fun patchedDataIsExplicitlyUnsupported() {
        for (method in ZipMethod.entries) {
            val bytes = zip(method)
            flags(bytes, 0x820)
            val source = PayloadSource(bytes)
            val reader = ZipReader(source)
            val entry = reader.entries().single()
            source.armed = true
            val error = assertFailsWith<ArchiveUnsupportedException> { reader.read(entry) }
            assertEquals("a.txt", error.entryName)
            assertEquals(6L, error.offset)
            assertTrue(error.message!!.contains("patched"))
            assertEquals(0, source.payloadReads)
        }
    }

    @Test
    fun maskedHeaderEncryptionIsUnsupportedRatherThanANameMismatch() {
        for (mask in 1..3) {
            val bytes = zip()
            if (mask and 1 != 0) putLe(bytes, 6, 0x2800, 2)
            if (mask and 2 != 0) putLe(bytes, central(bytes) + 8, 0x2800, 2)
            bytes[30] = 'b'.code.toByte()
            val error = assertFailsWith<ArchiveUnsupportedException> { ZipReader(ByteArrayRandomAccessSource(bytes)).entries() }
            assertEquals("a.txt", error.entryName)
            assertEquals(6L, error.offset)
            assertTrue(error.message!!.contains("masked"))
        }
    }

    @Test
    fun otherConsistentUnsupportedMethodsReachTheDirectoryMethodError() {
        for ((number, expectedCodec) in listOf(
            9 to CodecId("zip-method-9"), 12 to CodecId.BZIP2,
            14 to CodecId.LZMA, 93 to CodecId.ZSTD, 98 to CodecId.PPMD,
            65535 to CodecId("zip-method-65535"),
        )) {
            val bytes = zip()
            method(bytes, number)
            val source = PayloadSource(bytes)
            val reader = ZipReader(source)
            val entry = reader.entries().single()
            assertFalse(entry.isEncrypted)
            assertEquals(expectedCodec, entry.codec)
            assertEquals(number, entry.zipMethod)
            assertEquals(number, entry.copy().zipMethod)
            assertNull(Codecs.byId(entry.codec))
            source.armed = true
            val error = assertFailsWith<ArchiveUnsupportedException> { reader.read(entry) }
            assertEquals("a.txt", error.entryName)
            assertEquals(8L, error.offset)
            assertTrue(error.message!!.contains("method $number"))
            assertEquals(0, source.payloadReads)
        }
    }

    @Test
    fun signedAndUnsignedClassicAndZip64DescriptorsRemainReadable() {
        for (method in ZipMethod.entries) {
            for (signed in listOf(false, true)) for (zip64 in listOf(false, true)) {
                val bytes = descriptorZip(method, signed, zip64)
                val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
                val entry = reader.entries().single()
                assertEquals(3L, entry.size)
                assertContentEquals(payload, reader.read(entry))
            }
        }
    }

    private fun descriptorZip(method: ZipMethod, signed: Boolean, zip64: Boolean): ByteArray {
        val original = zip(method)
        val central = central(original)
        val compressed = readU32Le(original, central + 20)
        val crc = readU32Le(original, central + 16)
        val localExtra = if (zip64) zip64Extra(3, output = 0, compressed = 0) else byteArrayOf()
        val local = original.copyOf(35) + localExtra + original.copyOfRange(35, central)
        putLe(local, 6, 0x808, 2)
        putLe(local, 14, 0, 4)
        putLe(local, 18, if (zip64) 0xFFFFFFFF else 0, 4)
        putLe(local, 22, if (zip64) 0xFFFFFFFF else 0, 4)
        putLe(local, 28, localExtra.size.toLong(), 2)
        val directoryExtra = if (zip64) zip64Extra(3, compressed = compressed) else byteArrayOf()
        val directory = original.copyOfRange(central, original.size - 22) + directoryExtra
        putLe(directory, 8, 0x808, 2)
        if (zip64) {
            putLe(local, 4, 45, 2)
            putLe(directory, 6, 45, 2)
            putLe(directory, 20, 0xFFFFFFFF, 4)
            putLe(directory, 24, 0xFFFFFFFF, 4)
            putLe(directory, 30, directoryExtra.size.toLong(), 2)
        }
        val prefix = if (signed) 4 else 0
        val width = if (zip64) 8 else 4
        val descriptor = ByteArray(prefix + 4 + 2 * width)
        if (signed) putLe(descriptor, 0, 0x08074B50, 4)
        putLe(descriptor, prefix, crc, 4)
        putLe(descriptor, prefix + 4, compressed, width)
        putLe(descriptor, prefix + 4 + width, 3, width)
        val end = original.copyOfRange(original.size - 22, original.size)
        putLe(end, 12, directory.size.toLong(), 4)
        putLe(end, 16, (local.size + descriptor.size).toLong(), 4)
        return local + descriptor + directory + end
    }

    @Test
    fun differentExtraFieldsTimestampsAndCommentsRemainReadable() {
        val original = zip()
        val central = central(original)
        val localExtra = byteArrayOf(0x34, 0x12, 3, 0, 1, 2, 3)
        val directoryExtra = byteArrayOf(0x34, 0x12, 1, 0, 9)
        val comment = "entry comment".encodeToByteArray()
        val local = original.copyOf(35) + localExtra + original.copyOfRange(35, central)
        putLe(local, 28, localExtra.size.toLong(), 2)
        putLe(local, 10, 1, 2)
        val directory = original.copyOfRange(central, original.size - 22) + directoryExtra + comment
        putLe(directory, 30, directoryExtra.size.toLong(), 2)
        putLe(directory, 32, comment.size.toLong(), 2)
        val end = original.copyOfRange(original.size - 22, original.size)
        putLe(end, 12, directory.size.toLong(), 4)
        putLe(end, 16, local.size.toLong(), 4)
        val bytes = zipWithComment(local + directory + end, "archive comment".encodeToByteArray())
        val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
        val entry = reader.entries().single()
        assertContentEquals(payload, reader.read(entry))
        // The local extra contents are not required to equal the directory's extra contents.
        bytes[39] = 4
        assertContentEquals(payload, reader.read(entry))
    }
}
