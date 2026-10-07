package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Lz4ChecksumTest {
    private class Frame(val name: String, encoded: String) {
        val bytes = ByteArray(encoded.length / 2) { encoded.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val flags get() = bytes[4].toInt() and 255
        val headerChecksum get() = 6 + (if (flags and 8 != 0) 8 else 0) + (if (flags and 1 != 0) 4 else 0)
        fun output(): ByteArray = when (name.substringBefore('-')) {
            "empty" -> ByteArray(0)
            "stored" -> ByteArray(16) { it.toByte() }
            "compressed" -> "hello world, hello world\n".repeat(40).encodeToByteArray()
            "multi" -> ByteArray(70000) { 65 }
            else -> error("unknown fixture $name")
        }
        fun blockExtents(): List<IntRange> {
            val result = mutableListOf<IntRange>()
            var at = headerChecksum + 1
            while (true) {
                val raw = u32(bytes, at)
                at += 4
                if (raw == 0L) return result
                val size = (raw and 0x7FFFFFFF).toInt()
                result.add(at until at + size)
                at += size + (if (flags and 16 != 0) 4 else 0)
            }
        }
    }

    // lz4 v1.10.0: -B4 -BI, with optional -BX, --no-frame-crc and --content-size.
    // Empty-block and DictID variants were also decoded by the reference CLI;
    // their extra checksum fields were computed with the independently verified upstream XXH32.
    private val frames = listOf(
        Frame("empty-000", "04224d1860408200000000"),
        Frame("empty-001", "04224d1860408200000000"),
        Frame("empty-010", "04224d186440a700000000055dcc02"),
        Frame("empty-011", "04224d186440a700000000055dcc02"),
        Frame("empty-100", "04224d187040ad00000000"),
        Frame("empty-101", "04224d187040ad00000000"),
        Frame("empty-110", "04224d187440bd00000000055dcc02"),
        Frame("empty-111", "04224d187440bd00000000055dcc02"),
        Frame("stored-000", "04224d1860408210000080000102030405060708090a0b0c0d0e0f00000000"),
        Frame("stored-001", "04224d18684010000000000000006510000080000102030405060708090a0b0c0d0e0f00000000"),
        Frame("stored-010", "04224d186440a710000080000102030405060708090a0b0c0d0e0f00000000f43728b7"),
        Frame("stored-011", "04224d186c4010000000000000008310000080000102030405060708090a0b0c0d0e0f00000000f43728b7"),
        Frame("stored-100", "04224d187040ad10000080000102030405060708090a0b0c0d0e0ff43728b700000000"),
        Frame("stored-101", "04224d18784010000000000000001a10000080000102030405060708090a0b0c0d0e0ff43728b700000000"),
        Frame("stored-110", "04224d187440bd10000080000102030405060708090a0b0c0d0e0ff43728b700000000f43728b7"),
        Frame("stored-111", "04224d187c4010000000000000006910000080000102030405060708090a0b0c0d0e0ff43728b700000000f43728b7"),
        Frame("compressed-000", "04224d1860408221000000d768656c6c6f20776f726c642c200d00170a0c000f1900ffffffaf506f726c640a00000000"),
        Frame("compressed-001", "04224d186840e803000000000000f121000000d768656c6c6f20776f726c642c200d00170a0c000f1900ffffffaf506f726c640a00000000"),
        Frame("compressed-010", "04224d186440a721000000d768656c6c6f20776f726c642c200d00170a0c000f1900ffffffaf506f726c640a000000008e799139"),
        Frame("compressed-011", "04224d186c40e8030000000000009621000000d768656c6c6f20776f726c642c200d00170a0c000f1900ffffffaf506f726c640a000000008e799139"),
        Frame("compressed-100", "04224d187040ad21000000d768656c6c6f20776f726c642c200d00170a0c000f1900ffffffaf506f726c640a507ea66d00000000"),
        Frame("compressed-101", "04224d187840e8030000000000005c21000000d768656c6c6f20776f726c642c200d00170a0c000f1900ffffffaf506f726c640a507ea66d00000000"),
        Frame("compressed-110", "04224d187440bd21000000d768656c6c6f20776f726c642c200d00170a0c000f1900ffffffaf506f726c640a507ea66d000000008e799139"),
        Frame("compressed-111", "04224d187c40e8030000000000003d21000000d768656c6c6f20776f726c642c200d00170a0c000f1900ffffffaf506f726c640a507ea66d000000008e799139"),
        Frame("multi-000", "04224d186040820b0100001f410100ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe75041414141411c0000001f410100ffffffffffffffffffffffffffffffffff6850414141414100000000"),
        Frame("multi-001", "04224d1868407011010000000000610b0100001f410100ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe75041414141411c0000001f410100ffffffffffffffffffffffffffffffffff6850414141414100000000"),
        Frame("multi-010", "04224d186440a70b0100001f410100ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe75041414141411c0000001f410100ffffffffffffffffffffffffffffffffff685041414141410000000048aab257"),
        Frame("multi-011", "04224d186c407011010000000000370b0100001f410100ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe75041414141411c0000001f410100ffffffffffffffffffffffffffffffffff685041414141410000000048aab257"),
        Frame("multi-100", "04224d187040ad0b0100001f410100ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe750414141414159405f321c0000001f410100ffffffffffffffffffffffffffffffffff685041414141412d88a91d00000000"),
        Frame("multi-101", "04224d18784070110100000000000c0b0100001f410100ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe750414141414159405f321c0000001f410100ffffffffffffffffffffffffffffffffff685041414141412d88a91d00000000"),
        Frame("multi-110", "04224d187440bd0b0100001f410100ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe750414141414159405f321c0000001f410100ffffffffffffffffffffffffffffffffff685041414141412d88a91d0000000048aab257"),
        Frame("multi-111", "04224d187c407011010000000000ca0b0100001f410100ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe750414141414159405f321c0000001f410100ffffffffffffffffffffffffffffffffff685041414141412d88a91d0000000048aab257"),
        Frame("empty-block", "04224d187440bd00000080055dcc0200000000055dcc02"),
        Frame("stored-100-dict", "04224d18714078563412c310000080000102030405060708090a0b0c0d0e0ff43728b700000000"),
        Frame("stored-111-dict", "04224d187d40100000000000000078563412a710000080000102030405060708090a0b0c0d0e0ff43728b700000000f43728b7"),
    )

    private fun mismatch(bytes: ByteArray, stage: String, offset: Int, limits: ArchiveLimits = ArchiveLimits.DEFAULT) {
        val error = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(bytes, limits) }
        assertTrue(error.message!!.contains("$stage checksum mismatch"), error.toString())
        assertEquals(offset.toLong(), error.offset)
    }

    @Test
    fun referenceFramesCoverChecksumCombinationsAndBlockKinds() {
        for (frame in frames) assertContentEquals(frame.output(), Lz4.frameDecompress(frame.bytes), frame.name)
    }

    @Test
    fun descriptorAndOptionalFieldsAreProtectedByHeaderChecksum() {
        for (frame in frames) {
            for (at in listOf(4, 5, frame.headerChecksum) + (6 until frame.headerChecksum)) {
                val changed = frame.bytes.copyOf()
                val mask = when (at) { 4 -> 0x20; 5 -> 0x10; else -> 1 }
                changed[at] = (changed[at].toInt() xor mask).toByte()
                mismatch(changed, "header", frame.headerChecksum)
            }
        }
    }

    @Test
    fun everyBlockChecksumCoversStoredBytesIncludingEmptyBlocks() {
        for (frame in frames.filter { it.flags and 16 != 0 }) {
            for (extent in frame.blockExtents()) {
                val checksum = extent.last + 1
                for (byte in 0..3) {
                    val changed = frame.bytes.copyOf()
                    changed[checksum + byte] = (changed[checksum + byte].toInt() xor 1).toByte()
                    mismatch(changed, "block", checksum)
                }
                val zero = frame.bytes.copyOf()
                zero.fill(0, checksum, checksum + 4)
                mismatch(zero, "block", checksum)
                if (!extent.isEmpty()) {
                    val payload = frame.bytes.copyOf()
                    payload[extent.first] = (payload[extent.first].toInt() xor 0x80).toByte()
                    mismatch(payload, "block", checksum)
                }
            }
        }
    }

    @Test
    fun contentChecksumCoversCompleteDecodedOutputIncludingEmptyFrames() {
        for (frame in frames.filter { it.flags and 4 != 0 }) {
            val checksum = frame.bytes.size - 4
            for (byte in 0..3) {
                val changed = frame.bytes.copyOf()
                changed[checksum + byte] = (changed[checksum + byte].toInt() xor 1).toByte()
                mismatch(changed, "content", checksum)
            }
            val zero = frame.bytes.copyOf()
            zero.fill(0, checksum, checksum + 4)
            mismatch(zero, "content", checksum)
        }
    }

    @Test
    fun contentChecksumRejectsChangedStoredPayloadWithoutBlockChecksum() {
        val frame = frames.single { it.name == "stored-010" }
        val changed = frame.bytes.copyOf()
        val payload = frame.blockExtents().single().first
        changed[payload] = (changed[payload].toInt() xor 0x80).toByte()
        mismatch(changed, "content", changed.size - 4)
    }

    @Test
    fun blockChecksumIsVerifiedBeforeDecodeOrOutputGrowth() {
        for (name in listOf("stored-100", "compressed-100")) {
            val frame = frames.single { it.name == name }
            val extent = frame.blockExtents().single()
            val changed = frame.bytes.copyOf()
            changed[extent.first] = (changed[extent.first].toInt() xor 0x80).toByte()
            val limits = ArchiveLimits(maxUncompressedBytes = 0)
            mismatch(changed, "block", extent.last + 1, limits)
            assertFailsWith<DecompressionBombException> { Lz4.frameDecompress(frame.bytes, limits) }
        }
    }

    @Test
    fun truncatedChecksumFieldsAreFormatErrors() {
        for (frame in frames) {
            val headerError = assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(frame.bytes.copyOf(frame.headerChecksum)) }
            assertEquals(frame.headerChecksum.toLong(), headerError.offset)
            if (frame.flags and 16 != 0) {
                for (extent in frame.blockExtents()) {
                    for (available in 0..3) {
                        val blockError = assertFailsWith<ArchiveFormatException> {
                            Lz4.frameDecompress(frame.bytes.copyOf(extent.last + 1 + available))
                        }
                        assertEquals((extent.last + 1).toLong(), blockError.offset)
                    }
                }
            }
            if (frame.flags and 4 != 0) {
                for (missing in 1..4) {
                    val contentError = assertFailsWith<ArchiveFormatException> {
                        Lz4.frameDecompress(frame.bytes.copyOf(frame.bytes.size - missing))
                    }
                    assertEquals((frame.bytes.size - 4).toLong(), contentError.offset)
                }
            }
        }
    }

    companion object {
        private fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { value, i ->
            value or ((bytes[offset + i].toLong() and 255) shl (8 * i))
        }
    }
}
