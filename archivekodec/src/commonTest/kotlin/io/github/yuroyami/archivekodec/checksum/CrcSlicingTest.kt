package io.github.yuroyami.archivekodec.checksum

import kotlin.test.Test
import kotlin.test.assertEquals

class CrcSlicingTest {
    @Test
    fun crc32MatchesBitwiseReferenceAtWordBoundaries() =
        verify(::Crc32) { bitwise32(it, 0xEDB88320.toInt()) }

    @Test
    fun crc32cMatchesBitwiseReferenceAtWordBoundaries() =
        verify(::Crc32c) { bitwise32(it, 0x82F63B78.toInt()) }

    @Test
    fun crc64MatchesBitwiseReferenceAtWordBoundaries() =
        verify(::Crc64, ::bitwise64)

    private fun verify(create: () -> Checksum, reference: (ByteArray) -> Long) {
        val data = ByteArray(80) { (it * 73 + 255).toByte() }
        val lengths = (0..33).toList() + listOf(63, 64, 65)
        for (prefix in listOf(byteArrayOf(), byteArrayOf(-1, 13, -128))) {
            for (offset in listOf(0, 1, 3, 7, 8, 15)) for (length in lengths) {
                val checksum = create().also { it.update(prefix); it.update(data, offset, length) }
                val expected = reference(prefix + data.copyOfRange(offset, offset + length))
                assertEquals(expected, checksum.value(), "prefix ${prefix.size} offset $offset length $length")
            }
        }
        val checksum = create()
        var offset = 0
        for (chunk in listOf(1, 7, 8, 9, 16, 17, 22)) {
            checksum.update(data, offset, chunk)
            offset += chunk
            assertEquals(reference(data.copyOfRange(0, offset)), checksum.value(), "stream prefix $offset")
        }
        assertEquals(data.size, offset)
    }

    // Table-free references advance the reflected polynomial one bit at a time.
    private fun bitwise32(data: ByteArray, polynomial: Int): Long {
        var c = -1
        for (byte in data) {
            c = c xor (byte.toInt() and 0xFF)
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor polynomial else c ushr 1 }
        }
        return c.inv().toLong() and 0xFFFFFFFFL
    }

    private fun bitwise64(data: ByteArray): Long {
        var c = -1L
        for (byte in data) {
            c = c xor (byte.toLong() and 0xFF)
            repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0xC96C5795D7870F42uL.toLong() else c ushr 1 }
        }
        return c.inv()
    }
}
