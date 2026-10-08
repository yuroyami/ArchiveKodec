package io.github.yuroyami.archivekodec.checksum

import io.github.yuroyami.archivekodec.util.readI32Le

/**
 * CRC-64/XZ (ECMA-182): reflected polynomial `0xC96C5795D7870F42`, init
 * `0xFFFFFFFFFFFFFFFF`, final XOR `0xFFFFFFFFFFFFFFFF`. This is the integrity check
 * used by the `.xz` container (and an optional 7z check).
 *
 * Check value: `CRC64("123456789") == 0x995DC9BBDF1939FA`.
 */
public class Crc64 : Checksum {
    // Keep the per-byte arithmetic native on Kotlin/JS as well as other targets.
    private var low = -1
    private var high = -1

    @Throws(IllegalArgumentException::class)
    override fun update(data: ByteArray, offset: Int, length: Int) {
        val end = checkedChecksumEnd(data.size, offset, length)
        var lo = low
        var hi = high
        var i = offset
        val lowTables = TABLE_LOW
        val highTables = TABLE_HIGH
        // Process eight bytes together to shorten the dependency chain.
        while (end - i >= 8) {
            val a = lo xor readI32Le(data, i)
            val b = hi xor readI32Le(data, i + 4)
            lo = lowTables[7][a and 0xFF] xor lowTables[6][(a ushr 8) and 0xFF] xor
                lowTables[5][(a ushr 16) and 0xFF] xor lowTables[4][a ushr 24] xor
                lowTables[3][b and 0xFF] xor lowTables[2][(b ushr 8) and 0xFF] xor
                lowTables[1][(b ushr 16) and 0xFF] xor lowTables[0][b ushr 24]
            hi = highTables[7][a and 0xFF] xor highTables[6][(a ushr 8) and 0xFF] xor
                highTables[5][(a ushr 16) and 0xFF] xor highTables[4][a ushr 24] xor
                highTables[3][b and 0xFF] xor highTables[2][(b ushr 8) and 0xFF] xor
                highTables[1][(b ushr 16) and 0xFF] xor highTables[0][b ushr 24]
            i += 8
        }
        val lowTable = lowTables[0]
        val highTable = highTables[0]
        while (i < end) {
            val index = (lo xor data[i].toInt()) and 0xFF
            lo = ((lo ushr 8) or (hi shl 24)) xor lowTable[index]
            hi = (hi ushr 8) xor highTable[index]
            i++
        }
        low = lo
        high = hi
    }

    override fun value(): Long =
        (high.inv().toLong() shl 32) or (low.inv().toLong() and 0xFFFFFFFFL)

    override fun reset() { low = -1; high = -1 }

    /** One-shot checksum calculation for a complete byte array. */
    public companion object {
        private val POLY = 0xC96C5795D7870F42uL.toLong()

        private val TABLE_LOW = Array(8) { IntArray(256) }
        private val TABLE_HIGH = Array(8) { IntArray(256) }

        init {
            // Construct the shared tables once; updates only use their Int halves.
            val bytes = LongArray(256) { n ->
                var c = n.toLong()
                repeat(8) { c = if (c and 1L != 0L) POLY xor (c ushr 1) else c ushr 1 }
                c
            }
            for (n in bytes.indices) {
                var c = bytes[n]
                for (slice in 0 until 8) {
                    TABLE_LOW[slice][n] = c.toInt()
                    TABLE_HIGH[slice][n] = (c ushr 32).toInt()
                    c = (c ushr 8) xor bytes[c.toInt() and 0xFF]
                }
            }
        }

        /** Calculate the 64 checksum bits as a signed Long of all [data]. */
        public fun of(data: ByteArray): Long = Crc64().apply { update(data) }.value()
    }
}
