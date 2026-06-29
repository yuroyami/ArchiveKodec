package io.github.yuroyami.kitearchive.checksum

/**
 * Adler-32 (RFC 1950) — the zlib-stream trailer checksum. Two 16-bit rolling sums
 * modulo 65521, combined as `(b shl 16) or a`. Faster but weaker than CRC-32.
 *
 * Check value: `ADLER32("123456789") == 0x091E01DE`.
 */
class Adler32 : Checksum {
    private var a = 1
    private var b = 0

    override fun update(data: ByteArray, offset: Int, length: Int) {
        var s1 = a
        var s2 = b
        var i = offset
        val end = offset + length
        while (i < end) {
            // Process in NMAX-sized runs so the sums can't overflow Int before the mod.
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

    override fun value(): Long = ((b.toLong() shl 16) or a.toLong()) and 0xFFFFFFFFL

    override fun reset() { a = 1; b = 0 }

    companion object {
        private const val MOD = 65521      // largest prime below 2^16
        private const val NMAX = 5552      // max iterations before a mod is required

        fun of(data: ByteArray): Long = Adler32().apply { update(data) }.value()
    }
}
