package io.github.yuroyami.archivekodec.checksum

/**
 * Adler-32 (RFC 1950): the zlib-stream trailer checksum. Two 16-bit rolling sums
 * modulo 65521, combined as `(b shl 16) or a`. Faster but weaker than CRC-32.
 *
 * Check value: `ADLER32("123456789") == 0x091E01DE`.
 */
class Adler32 : Checksum {
    // Long accumulators: over a 5552-byte NMAX run s2 reaches ~4.1e9, which overflows
    // a SIGNED 32-bit Int (zlib's NMAX is sized for UNSIGNED 32-bit). Long has the room.
    private var a = 1L
    private var b = 0L

    override fun update(data: ByteArray, offset: Int, length: Int) {
        var s1 = a
        var s2 = b
        var i = offset
        val end = offset + length
        while (i < end) {
            // Process in NMAX-sized runs to bound how often we take the modulo.
            var n = minOf(end - i, NMAX)
            while (n-- > 0) {
                s1 += (data[i++].toInt() and 0xFF).toLong()
                s2 += s1
            }
            s1 %= MOD
            s2 %= MOD
        }
        a = s1
        b = s2
    }

    override fun value(): Long = ((b shl 16) or a) and 0xFFFFFFFFL

    override fun reset() { a = 1L; b = 0L }

    companion object {
        private const val MOD = 65521      // largest prime below 2^16
        private const val NMAX = 5552      // max iterations before a mod is required

        fun of(data: ByteArray): Long = Adler32().apply { update(data) }.value()
    }
}
