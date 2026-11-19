package io.github.yuroyami.archivekodec.archive.tar

import io.github.yuroyami.archivekodec.ArchiveFormatException

/** Checks for a complete TAR header record. */
internal object TarHeader {
    const val SIZE = 512

    // The six-byte POSIX marker selects the prefix layout. Version bytes do not
    // change that layout; old GNU uses these trailing fields for other metadata.
    fun usesUstarPrefix(bytes: ByteArray): Boolean = bytes.size >= SIZE &&
        bytes[257].toInt() == 'u'.code && bytes[258].toInt() == 's'.code &&
        bytes[259].toInt() == 't'.code && bytes[260].toInt() == 'a'.code &&
        bytes[261].toInt() == 'r'.code && bytes[262].toInt() == 0

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

/** Remove NUL framing while preserving all stored filename bytes. */
internal fun cString(b: ByteArray, off: Int, len: Int): ByteArray {
    var end = off
    val max = off + len
    while (end < max && b[end].toInt() != 0) end++
    return b.copyOfRange(off, end)
}

/** Like [numeric], but null for a field holding only NUL and space padding. */
internal fun optionalNumeric(b: ByteArray, off: Int, len: Int, headerOffset: Long): Long? {
    for (i in off until off + len) if (b[i].toInt() != 0 && b[i].toInt() != ' '.code) return numeric(b, off, len, headerOffset)
    return null
}

/**
 * Parse a numeric header field: octal ASCII normally, GNU base-256 binary when
 * the field's high bit is set (used when a value overflows the octal width).
 */
internal fun numeric(b: ByteArray, off: Int, len: Int, headerOffset: Long): Long {
    if (len > 0 && (b[off].toInt() and 0x80) != 0) {
        val first = b[off].toInt() and 0x7F
        var v = if (first and 0x40 != 0) first.toLong() - 128 else first.toLong()
        for (i in 1 until len) {
            if (v < (Long.MIN_VALUE shr 8) || v > (Long.MAX_VALUE shr 8)) {
                throw ArchiveFormatException("TAR: numeric field overflows Long", headerOffset + off)
            }
            v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return v
    }
    var v = 0L
    var i = off
    val max = off + len
    while (i < max) {
        val c = b[i].toInt()
        if (c == 0 || c == ' '.code) { i++; continue }
        if (c < '0'.code || c > '7'.code) break
        v = v * 8 + (c - '0'.code)
        i++
    }
    return v
}
