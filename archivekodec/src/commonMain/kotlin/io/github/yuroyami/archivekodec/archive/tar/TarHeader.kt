package io.github.yuroyami.archivekodec.archive.tar

/** Integrity checks for a complete TAR header record. */
internal object TarHeader {
    const val SIZE = 512

    /** A complete first zero record is the end marker of an empty TAR. */
    fun isZeroRecord(bytes: ByteArray): Boolean {
        if (bytes.size < SIZE) return false
        for (i in 0 until SIZE) if (bytes[i].toInt() != 0) return false
        return true
    }

    /** Check the first complete record, including legacy signed-byte checksums. */
    fun hasValidChecksum(bytes: ByteArray): Boolean {
        if (bytes.size < SIZE) return false
        var i = 148
        while (i < 156 && bytes[i].toInt() == 32) i++
        val start = i
        var stored = 0
        while (i < 156 && bytes[i].toInt() in '0'.code..'7'.code) {
            stored = stored * 8 + bytes[i++].toInt() - '0'.code
        }
        if (i == start) return false
        while (i < 156) {
            if (bytes[i].toInt() != 0 && bytes[i].toInt() != 32) return false
            i++
        }

        // Eight checksum bytes count as spaces. Both 512-byte sums and the
        // eight-digit octal field fit in Int, regardless of the input bytes.
        var unsigned = 8 * 32
        var signed = 8 * 32
        for (offset in 0 until SIZE) {
            if (offset in 148..155) continue
            unsigned += bytes[offset].toInt() and 255
            signed += bytes[offset].toInt()
        }
        return stored == unsigned || stored == signed
    }
}
