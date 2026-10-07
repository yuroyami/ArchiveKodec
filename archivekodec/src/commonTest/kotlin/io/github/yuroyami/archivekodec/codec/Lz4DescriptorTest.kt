package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.checksum.XxHash32
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Lz4DescriptorTest {
    private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val first = hex("04224d186440a70600008066697273742d00000000e9193015")
    // liblz4 1.10.0: exact 84-byte restoration requires the external 54-byte dictionary.
    private val undeclared = hex("04224d186c4054000000000000009f140000000f3600234120616e6452000c710050616761696e00000000d2bb8d00")
    private val declared = hex("04224d186d40540000000000000098badcfee2140000000f3600234120616e6452000c710050616761696e00000000d2bb8d00")

    private fun checksum(bytes: ByteArray): ByteArray = bytes.also {
        val flags = it[4].toInt() and 255
        val at = 6 + (if (flags and 8 != 0) 8 else 0) + (if (flags and 1 != 0) 4 else 0)
        it[at] = (XxHash32.hash(it, 4, at - 4) ushr 8).toByte()
    }

    @Test
    fun wrongVersionsFailWithCorrectlyRecomputedHeaderChecksums() {
        for (version in listOf(0, 2, 3)) {
            val frame = checksum(first.copyOf().also { it[4] = ((it[4].toInt() and 0x3f) or (version shl 6)).toByte() })
            val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(frame) }
            assertEquals(4L, error.offset)
            assertTrue(error.message!!.contains("version"), error.toString())
        }
    }

    @Test
    fun reservedFlgBitFailsBeforeLimitsOrPayloadDecoding() {
        val frame = checksum(first.copyOf().also { it[4] = (it[4].toInt() or 2).toByte() })
        for (limits in listOf(ArchiveLimits.DEFAULT, ArchiveLimits(maxUncompressedBytes = 0))) {
            val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(frame, limits) }
            assertEquals(4L, error.offset)
            assertTrue(error.message!!.contains("reserved FLG"), error.toString())
        }
    }

    @Test
    fun everyReservedBdBitFailsWithAValidChecksum() {
        for (maximum in 4..7) for (mask in listOf(1, 2, 4, 8, 128, 0x8f)) {
            val frame = checksum(first.copyOf().also { it[5] = ((maximum shl 4) or mask).toByte() })
            val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(frame) }
            assertEquals(5L, error.offset)
            assertTrue(error.message!!.contains("reserved BD"), error.toString())
        }
    }

    @Test
    fun validVersionsAndMaximumDescriptorsRemainReadable() {
        for (maximum in 4..7) {
            val frame = checksum(first.copyOf().also { it[5] = (maximum shl 4).toByte() })
            assertContentEquals("first-".encodeToByteArray(), Lz4.frameDecompress(frame))
        }
    }

    @Test
    fun declaredDictionaryFailureNamesTheUnsignedIdAndMatchOffset() {
        val error = assertFailsWith<ArchiveUnsupportedException> { Lz4.frameDecompress(declared) }
        assertEquals(24L, error.offset)
        assertTrue(error.message!!.contains("dictionary ID 4275878552"), error.toString())
        assertTrue(error.message!!.contains("history"), error.toString())
    }

    @Test
    fun undeclaredDictionaryFailurePreservesTheAmbiguousFormatCategory() {
        for ((frame, offset) in listOf(undeclared to 20L,
            hex("04224d186440a7140000000f3600234120616e6452000c710050616761696e00000000d2bb8d00") to 12L)) {
            val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(frame) }
            assertEquals(offset, error.offset)
            assertTrue(error.message!!.contains("dictionary"), error.toString())
            assertTrue(error.message!!.contains("may be required"), error.toString())
        }
    }

    @Test
    fun rawBlockAndSlicedBlockFailuresRetainTheirSourcePosition() {
        val block = undeclared.copyOfRange(19, 39)
        for (prefix in listOf(0, 5, 100)) {
            val source = ByteArray(prefix) + block + ByteArray(3)
            val error = assertFailsWith<ArchiveFormatException> { Lz4.blockDecompress(source, prefix, prefix + block.size) }
            assertEquals((prefix + 1).toLong(), error.offset)
            assertTrue(error.message!!.contains("dictionary"), error.toString())
        }
    }

    @Test
    fun dictionaryMetadataDoesNotRejectSelfContainedBlocks() {
        val header = declared.copyOf(19).also { it[4] = 0x61; it.copyInto(it, 6, 14, 18) }.copyOf(11)
        checksum(header)
        assertContentEquals(byteArrayOf(), Lz4.frameDecompress(header + ByteArray(4)))
        val stored = header + hex("0600008066697273742d00000000")
        assertContentEquals("first-".encodeToByteArray(), Lz4.frameDecompress(stored))
        val compressed = header + hex("070000006066697273742d00000000")
        assertContentEquals("first-".encodeToByteArray(), Lz4.frameDecompress(compressed))
    }

    @Test
    fun corruptOrTruncatedDictionaryDescriptorsStayFormatFailures() {
        val corrupt = declared.copyOf().also { it[18] = (it[18].toInt() xor 1).toByte() }
        val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(corrupt) }
        assertEquals(18L, error.offset)
        assertTrue(error.message!!.contains("header checksum"), error.toString())
        for (length in 6..18) assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(declared.copyOf(length)) }
    }

    @Test
    fun zeroOffsetRemainsMalformedEvenWithAnAdvertisedDictionary() {
        val frame = declared.copyOf().also { it[24] = 0; it[25] = 0 }
        val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(frame) }
        assertEquals(24L, error.offset)
        assertTrue(error.message!!.contains("invalid match offset 0"), error.toString())
        assertTrue(!error.message!!.contains("dictionary"), error.toString())
    }
}
