package io.github.yuroyami.archivekodec.checksum

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
        while (i < end) {
            val index = (lo xor data[i].toInt()) and 0xFF
            lo = ((lo ushr 8) or (hi shl 24)) xor TABLE_LOW[index]
            hi = (hi ushr 8) xor TABLE_HIGH[index]
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

        private val TABLE_LOW = IntArray(256)
        private val TABLE_HIGH = IntArray(256)

        init {
            // Construct the shared tables once; updates only use their Int halves.
            for (n in 0 until 256) {
                var c = n.toLong()
                repeat(8) { c = if (c and 1L != 0L) POLY xor (c ushr 1) else c ushr 1 }
                TABLE_LOW[n] = c.toInt()
                TABLE_HIGH[n] = (c ushr 32).toInt()
            }
        }

        /** Calculate the 64 checksum bits as a signed Long of all [data]. */
        public fun of(data: ByteArray): Long = Crc64().apply { update(data) }.value()
    }
}
