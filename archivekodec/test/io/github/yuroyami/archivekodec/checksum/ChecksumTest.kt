package io.github.yuroyami.archivekodec.checksum

import kotlin.test.Test
import kotlin.test.assertEquals

/** Standard "123456789" check values for each checksum family. */
class ChecksumTest {

    private val check = "123456789".encodeToByteArray()

    @Test
    fun crc32() = assertEquals(0xCBF43926L, Crc32.of(check))

    @Test
    fun crc32c() = assertEquals(0xE3069283L, Crc32c.of(check))

    @Test
    fun adler32() = assertEquals(0x091E01DEL, Adler32.of(check))

    @Test
    fun crc64Xz() = assertEquals(0x995DC9BBDF1939FAuL.toLong(), Crc64.of(check))

    @Test
    fun crc32Empty() = assertEquals(0L, Crc32.of(ByteArray(0)))

    @Test
    fun adler32Empty() = assertEquals(1L, Adler32.of(ByteArray(0)))

    @Test
    fun adler32LargeInputNoOverflow() {
        // Regression: signed-Int accumulators overflowed past one NMAX run.
        // Expected value cross-checked against CPython zlib.adler32.
        val ramp = ByteArray(100_000) { (it % 256).toByte() }
        assertEquals(0x61657A0FL, Adler32.of(ramp))
    }

    @Test
    fun incrementalMatchesOneShot() {
        val c = Crc32()
        c.update(check, 0, 4)
        c.update(check, 4, check.size - 4)
        assertEquals(Crc32.of(check), c.value())
    }
}
