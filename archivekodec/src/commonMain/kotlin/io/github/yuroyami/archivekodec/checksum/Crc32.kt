package io.github.yuroyami.archivekodec.checksum

import kotlin.jvm.JvmStatic

/**
 * CRC-32 (IEEE 802.3 / zlib / gzip / PNG): reflected polynomial `0xEDB88320`,
 * init `0xFFFFFFFF`, final XOR `0xFFFFFFFF`. Matches `zlib`'s `crc32()`.
 *
 * Check value: `CRC32("123456789") == 0xCBF43926`.
 */
public class Crc32 : Checksum {
    private var crc = -1 // 0xFFFFFFFF

    @Throws(IllegalArgumentException::class)
    override fun update(data: ByteArray, offset: Int, length: Int) {
        val end = checkedChecksumEnd(data.size, offset, length)
        crc = TABLES.update(data, offset, end, crc)
    }

    override fun value(): Long = (crc.inv().toLong() and 0xFFFFFFFFL)

    override fun reset() { crc = -1 }

    /** One-shot checksum calculation for a complete byte array. */
    public companion object {
        private const val POLY = 0xEDB88320.toInt()

        private val TABLES = Crc32Tables(POLY)

        /** One-shot convenience. */
        @JvmStatic
        public fun of(data: ByteArray): Long = Crc32().apply { update(data) }.value()
    }
}
