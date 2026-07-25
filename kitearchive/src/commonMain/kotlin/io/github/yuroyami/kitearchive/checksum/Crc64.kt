package io.github.yuroyami.kitearchive.checksum

/**
 * CRC-64/XZ (ECMA-182): reflected polynomial `0xC96C5795D7870F42`, init
 * `0xFFFFFFFFFFFFFFFF`, final XOR `0xFFFFFFFFFFFFFFFF`. This is the integrity check
 * used by the `.xz` container (and an optional 7z check).
 *
 * Check value: `CRC64("123456789") == 0x995DC9BBDF1939FA`.
 */
class Crc64 : Checksum {
    private var crc = -1L // 0xFFFFFFFFFFFFFFFF

    override fun update(data: ByteArray, offset: Int, length: Int) {
        var c = crc
        var i = offset
        val end = offset + length
        while (i < end) {
            c = (c ushr 8) xor TABLE[((c xor data[i].toLong()) and 0xFF).toInt()]
            i++
        }
        crc = c
    }

    override fun value(): Long = crc.inv()

    override fun reset() { crc = -1L }

    companion object {
        private val POLY = 0xC96C5795D7870F42uL.toLong()

        private val TABLE = LongArray(256) { n ->
            var c = n.toLong()
            repeat(8) { c = if (c and 1L != 0L) POLY xor (c ushr 1) else c ushr 1 }
            c
        }

        fun of(data: ByteArray): Long = Crc64().apply { update(data) }.value()
    }
}
