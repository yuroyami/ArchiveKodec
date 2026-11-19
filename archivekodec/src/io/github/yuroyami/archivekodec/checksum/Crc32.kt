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
    // The JVM's intrinsic CRC-32 where there is one; the tables elsewhere.
    private val platform = platformCrc32()

    @Throws(IllegalArgumentException::class)
    override fun update(data: ByteArray, offset: Int, length: Int) {
        val end = checkedChecksumEnd(data.size, offset, length)
        if (platform != null) platform.update(data, offset, length) else crc = TABLES.update(data, offset, end, crc)
    }

    override fun value(): Long = platform?.value() ?: (crc.inv().toLong() and 0xFFFFFFFFL)

    override fun reset() {
        platform?.reset()
        crc = -1
    }

    /** One-shot checksum calculation for a complete byte array. */
    public companion object {
        private const val POLY = 0xEDB88320.toInt()

        private val TABLES = Crc32Tables(POLY)

        /** One-shot convenience. */
        @JvmStatic
        public fun of(data: ByteArray): Long = Crc32().apply { update(data) }.value()
    }
}
