package io.github.yuroyami.archivekodec.checksum

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
        var c = crc
        var i = offset
        while (i < end) {
            c = (c ushr 8) xor TABLE[(c xor data[i].toInt()) and 0xFF]
            i++
        }
        crc = c
    }

    override fun value(): Long = (crc.inv().toLong() and 0xFFFFFFFFL)

    override fun reset() { crc = -1 }

    public companion object {
        private const val POLY = 0xEDB88320.toInt()

        private val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) POLY xor (c ushr 1) else c ushr 1 }
            c
        }

        /** One-shot convenience. */
        public fun of(data: ByteArray): Long = Crc32().apply { update(data) }.value()
    }
}
