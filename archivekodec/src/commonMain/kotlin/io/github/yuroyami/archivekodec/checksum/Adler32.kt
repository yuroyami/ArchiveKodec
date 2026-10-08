package io.github.yuroyami.archivekodec.checksum

/**
 * Adler-32 (RFC 1950): the zlib-stream trailer checksum. Two 16-bit rolling sums
 * modulo 65521, combined as `(b shl 16) or a`. Faster but weaker than CRC-32.
 *
 * Check value: `ADLER32("123456789") == 0x091E01DE`.
 */
public class Adler32 : Checksum {
    private var a = 1
    private var b = 0

    @Throws(IllegalArgumentException::class)
    override fun update(data: ByteArray, offset: Int, length: Int) {
        val end = checkedChecksumEnd(data.size, offset, length)
        var s1 = a
        var s2 = b
        var i = offset
        while (i < end) {
            // Process in NMAX-sized runs to bound how often we take the modulo.
            var n = minOf(end - i, NMAX)
            while (n-- > 0) {
                s1 += data[i++].toInt() and 0xFF
                s2 += s1
            }
            s1 %= MOD
            s2 %= MOD
        }
        a = s1
        b = s2
    }

    override fun value(): Long = ((b shl 16) or a).toLong() and 0xFFFFFFFFL

    override fun reset() { a = 1; b = 0 }

    /** One-shot checksum calculation for a complete byte array. */
    public companion object {
        private const val MOD = 65521      // largest prime below 2^16
        // Both sums start below MOD. With all bytes equal to 255, the largest
        // s2 is 255*n*(n+1)/2 + (n+1)*65520. At n=3854 it is 2,146,868,775;
        // the next byte could overflow a signed Int (zlib's 5552 uses unsigned).
        private const val NMAX = 3854

        /** Calculate the unsigned 32-bit checksum as a Long of all [data]. */
        public fun of(data: ByteArray): Long = Adler32().apply { update(data) }.value()
    }
}
