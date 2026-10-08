package io.github.yuroyami.archivekodec.wrapper

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.hex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class WrapperIntegrityTest {
    private val data = "hello".encodeToByteArray()

    @Test
    fun rejectsEveryCorruptedTrailerByte() {
        val gz = Gzip.compress(data)
        for (i in gz.size - 8 until gz.size) {
            val broken = gz.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<ArchiveFormatException> { Gzip.decompress(broken) }
        }
        val zl = Zlib.compress(data)
        for (i in zl.size - 4 until zl.size) {
            val broken = zl.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<ArchiveFormatException> { Zlib.decompress(broken) }
            assertFailsWith<ArchiveFormatException> { Gzip.decompress(broken) }
        }
    }

    @Test
    fun rejectsMissingAndPartialTrailers() {
        val gz = Gzip.compress(data)
        for (n in 1..8) assertFailsWith<ArchiveFormatException> { Gzip.decompress(gz.copyOf(gz.size - n)) }
        val zl = Zlib.compress(data)
        for (n in 1..4) assertFailsWith<ArchiveFormatException> { Zlib.decompress(zl.copyOf(zl.size - n)) }
    }

    @Test
    fun concatenatesAllMembersIncludingEmptyMembers() {
        val second = " world".encodeToByteArray()
        val input = Gzip.compress(data) + Gzip.compress(byteArrayOf()) + Gzip.compress(second)
        assertContentEquals(data + second, Gzip.decompress(input))
    }

    @Test
    fun acceptsZeroPaddingAndRejectsNonzeroTrailingData() {
        val gz = Gzip.compress(data)
        assertContentEquals(data + data, Gzip.decompress(gz + byteArrayOf(0, 0) + gz + byteArrayOf(0)))
        assertFailsWith<ArchiveFormatException> { Gzip.decompress(gz + byteArrayOf(1)) }
        assertFailsWith<ArchiveFormatException> { Zlib.decompress(Zlib.compress(data) + byteArrayOf(0)) }
    }

    @Test
    fun rejectsCorruptOrTruncatedLaterMembers() {
        val first = Gzip.compress(data)
        val second = Gzip.compress(data)
        val broken = second.copyOf().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte() }
        assertFailsWith<ArchiveFormatException> { Gzip.decompress(first + broken) }
        for (n in 1 until second.size) {
            assertFailsWith<io.github.yuroyami.archivekodec.ArchiveException> {
                Gzip.decompress(first + second.copyOf(n))
            }
        }
    }

    @Test
    fun validatesHeaderCrcWithEveryOptionalFieldCombination() {
        for (flags in 0..15) {
            val member = optionalHeader(flags shl 1)
            assertContentEquals(data, Gzip.decompress(member))
            if (flags and 1 != 0) {
                val headerEnd = member.size - (Gzip.compress(data).size - 10)
                val bad = member.copyOf().also { it[headerEnd - 1] = (it[headerEnd - 1].toInt() xor 1).toByte() }
                assertFailsWith<ArchiveFormatException> { Gzip.decompress(bad) }
            }
        }
    }

    @Test
    fun rejectsPublishedBadHeaderCrcVector() {
        val good = hex("1f8b08020000000000ff90c9cb48cdc9c9070086a6103605000000")
        assertContentEquals(data, Gzip.decompress(good))
        val bad = good.copyOf().also { it[10] = 0x12; it[11] = 0x34 }
        assertFailsWith<ArchiveFormatException> { Gzip.decompress(bad) }
    }

    @Test
    fun rejectsInvalidFixedHeadersAndUnterminatedOptionalFields() {
        val original = Gzip.compress(data)
        for (index in listOf(2, 3)) {
            val bad = original.copyOf().also { it[index] = if (index == 2) 7 else 0x20 }
            assertFailsWith<ArchiveFormatException> { Gzip.decompress(bad) }
        }
        for (flags in listOf(2, 4, 8, 16, 30)) {
            val member = optionalHeader(flags)
            val headerEnd = member.size - (original.size - 10)
            for (n in 0 until headerEnd) {
                assertFailsWith<ArchiveFormatException> { Gzip.decompress(member.copyOf(n)) }
            }
        }
    }

    private fun optionalHeader(flags: Int): ByteArray {
        var header = Gzip.compress(data).copyOf(10).also { it[3] = flags.toByte() }
        if (flags and 4 != 0) header += byteArrayOf(3, 0, 1, 2, 3)
        if (flags and 8 != 0) header += "name".encodeToByteArray() + byteArrayOf(0)
        if (flags and 16 != 0) header += "comment".encodeToByteArray() + byteArrayOf(0)
        if (flags and 2 != 0) {
            val crc = Crc32.of(header).toInt()
            header += byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
        }
        return header + Gzip.compress(data).let { it.copyOfRange(10, it.size) }
    }

}
