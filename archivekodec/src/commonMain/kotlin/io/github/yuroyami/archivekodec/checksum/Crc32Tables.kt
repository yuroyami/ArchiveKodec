package io.github.yuroyami.archivekodec.checksum

import io.github.yuroyami.archivekodec.util.readI32Le

/** Shared slicing-by-eight implementation for the reflected 32-bit CRC polynomials. */
internal class Crc32Tables(polynomial: Int) {
    private val tables = Array(8) { IntArray(256) }

    init {
        val first = tables[0]
        for (n in first.indices) {
            var c = n
            repeat(8) { c = if (c and 1 != 0) polynomial xor (c ushr 1) else c ushr 1 }
            first[n] = c
        }
        for (n in first.indices) {
            var c = first[n]
            for (slice in 1 until 8) {
                c = (c ushr 8) xor first[c and 0xFF]
                tables[slice][n] = c
            }
        }
    }

    /** Update unfinalized [initial] over a slice already checked by the public checksum. */
    fun update(data: ByteArray, offset: Int, end: Int, initial: Int): Int {
        var c = initial
        var i = offset
        while (end - i >= 8) {
            val a = c xor readI32Le(data, i)
            val b = readI32Le(data, i + 4)
            c = tables[7][a and 0xFF] xor tables[6][(a ushr 8) and 0xFF] xor
                tables[5][(a ushr 16) and 0xFF] xor tables[4][a ushr 24] xor
                tables[3][b and 0xFF] xor tables[2][(b ushr 8) and 0xFF] xor
                tables[1][(b ushr 16) and 0xFF] xor tables[0][b ushr 24]
            i += 8
        }
        val first = tables[0]
        while (i < end) {
            c = (c ushr 8) xor first[(c xor data[i].toInt()) and 0xFF]
            i++
        }
        return c
    }
}
