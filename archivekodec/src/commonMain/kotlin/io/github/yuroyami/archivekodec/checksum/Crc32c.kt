package io.github.yuroyami.archivekodec.checksum

/**
 * CRC-32C (Castagnoli): reflected polynomial `0x82F63B78`, init `0xFFFFFFFF`,
 * final XOR `0xFFFFFFFF`. Used by btrfs/ext4/iSCSI/SCTP and as an optional 7z
 * check (NOT by ZIP, which uses [Crc32]).
 *
 * Check value: `CRC32C("123456789") == 0xE3069283`.
 */
public class Crc32c : Checksum {
    private var crc = -1

    @Throws(IllegalArgumentException::class)
    override fun update(data: ByteArray, offset: Int, length: Int) {
        val end = checkedChecksumEnd(data.size, offset, length)
        crc = TABLES.update(data, offset, end, crc)
    }

    override fun value(): Long = (crc.inv().toLong() and 0xFFFFFFFFL)

    override fun reset() { crc = -1 }

    /** One-shot checksum calculation for a complete byte array. */
    public companion object {
        private const val POLY = 0x82F63B78.toInt()

        private val TABLES = Crc32Tables(POLY)

        /** Calculate the unsigned 32-bit checksum as a Long of all [data]. */
        public fun of(data: ByteArray): Long = Crc32c().apply { update(data) }.value()
    }
}
