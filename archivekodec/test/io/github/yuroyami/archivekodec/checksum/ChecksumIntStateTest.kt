package io.github.yuroyami.archivekodec.checksum

import kotlin.test.Test
import kotlin.test.assertEquals

class ChecksumIntStateTest {
    @Test
    fun adlerWorstStartingSumsStayWithinSignedIntAtBlockBoundary() {
        // CPython zlib.adler32: this prefix leaves both reduced sums at 65520.
        val prefix = ByteArray(63473) + ByteArray(256) { -1 } + byteArrayOf(239.toByte())
        val expected = listOf(3853 to 0x1E37FEC4L, 3854 to 0x1E09FFC3L,
            3855 to 0x1EDA00D1L, 7708 to 0x9670FF96L)
        for ((size, value) in expected) {
            val checksum = Adler32().also { it.update(prefix) }
            assertEquals(0xFFF0FFF0L, checksum.value())
            checksum.update(ByteArray(size) { -1 })
            assertEquals(value, checksum.value(), "length $size")
        }
    }

    @Test
    fun adlerAllHighBytesAcrossWholeAndPartitionedUpdates() {
        // Expected values from CPython zlib.adler32, around the signed-Int limit.
        val expected = listOf(3853 to 0x3C52FEC6L, 3854 to 0x3C26FFC5L,
            3855 to 0x3CF900D3L, 7708 to 0xD2A9FF98L, 100000 to 0x149A302CL)
        for ((size, value) in expected) {
            val data = ByteArray(size) { -1 }
            assertEquals(value, Adler32.of(data), "length $size")
            for (chunk in listOf(1, 3853, 3854, 3855)) {
                val checksum = Adler32()
                var offset = 0
                while (offset < size) {
                    val length = minOf(chunk, size - offset)
                    checksum.update(data, offset, length)
                    offset += length
                }
                assertEquals(value, checksum.value(), "length $size chunk $chunk")
            }
        }
    }

    @Test
    fun crc64MatchesLiblzmaForHighBitsSlicesAndIncrementalUpdates() {
        // liblzma lzma_crc64(data, size, 0), reflected CRC-64/XZ.
        val expected = listOf(
            0 to 0x0000000000000000uL, 1 to 0x1FADA17364673F59uL,
            7 to 0x371DBAB45642A6D4uL, 8 to 0x20C5B4FAD307C0C5uL,
            9 to 0xA67E8FBC6ED72F78uL, 255 to 0xB2E7BA96D9234CDFuL,
            256 to 0xA6ECADB202DD0BF4uL, 257 to 0xFC835DF3E55CC136uL,
            3853 to 0xE91ECDE724B06B3EuL, 3854 to 0x27C20D9E0566046CuL,
            3855 to 0x128E7EA88CB527B7uL, 7708 to 0xB3E9834D4087BD39uL,
            65536 to 0x406E11B7C15AA7EAuL, 100000 to 0x8FD67201CAF4D07CuL,
        )
        for ((size, value) in expected) {
            val data = ByteArray(size) { (it * 31 + it / 251).toByte() }
            assertEquals(value.toLong(), Crc64.of(data), "length $size")
            val padded = byteArrayOf(91, -1, 17) + data + byteArrayOf(-128, 51)
            val checksum = Crc64()
            var offset = 3
            while (offset < size + 3) {
                val length = minOf(257, size + 3 - offset)
                checksum.update(padded, offset, length)
                // Observing a streaming checksum must not finalize its state.
                checksum.value()
                offset += length
            }
            assertEquals(value.toLong(), checksum.value(), "sliced length $size")
            checksum.reset()
            assertEquals(0L, checksum.value())
            checksum.update(data)
            assertEquals(value.toLong(), checksum.value(), "reset length $size")
        }
    }
}
