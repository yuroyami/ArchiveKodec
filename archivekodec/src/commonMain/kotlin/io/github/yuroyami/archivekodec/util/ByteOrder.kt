package io.github.yuroyami.archivekodec.util

// Callers validate input ranges so each format retains its own error and offset.
internal fun readU16Le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

internal fun readU16Be(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

/** Read the raw 32 bits without Long arithmetic in hashing and match finding. */
internal fun readI32Le(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)

internal fun readU32Le(bytes: ByteArray, offset: Int): Long =
    readI32Le(bytes, offset).toLong() and 0xffffffffL

/** Preserve all 64 bits; unsigned consumers can convert the result to ULong. */
internal fun readI64Le(bytes: ByteArray, offset: Int): Long =
    readU32Le(bytes, offset) or (readI32Le(bytes, offset + 4).toLong() shl 32)

internal fun readU32Be(bytes: ByteArray, offset: Int): Long =
    (((bytes[offset].toInt() and 0xff) shl 24) or ((bytes[offset + 1].toInt() and 0xff) shl 16) or
        ((bytes[offset + 2].toInt() and 0xff) shl 8) or (bytes[offset + 3].toInt() and 0xff)).toLong() and 0xffffffffL

/** Snappy uses unsigned little-endian fields of one through four bytes. */
internal fun readUIntLe(bytes: ByteArray, offset: Int, byteCount: Int): Long = when (byteCount) {
    1 -> (bytes[offset].toInt() and 0xff).toLong()
    2 -> readU16Le(bytes, offset).toLong()
    3 -> readU16Le(bytes, offset).toLong() or ((bytes[offset + 2].toLong() and 0xff) shl 16)
    4 -> readU32Le(bytes, offset)
    else -> throw IllegalArgumentException("unsigned field width must be 1..4")
}

internal fun ByteArrayBuilder.le16(value: Int) {
    append(value.toByte())
    append((value ushr 8).toByte())
}

internal fun ByteArrayBuilder.le32(value: Long) {
    append(value.toByte())
    append((value ushr 8).toByte())
    append((value ushr 16).toByte())
    append((value ushr 24).toByte())
}

internal fun ByteArrayBuilder.le64(value: Long): Unit = leBytes(value, 8)

internal fun ByteArrayBuilder.leBytes(value: Long, byteCount: Int) {
    require(byteCount in 1..8)
    repeat(byteCount) { append((value ushr (8 * it)).toByte()) }
}

internal fun ByteArrayBuilder.be32(value: Long) {
    append((value ushr 24).toByte())
    append((value ushr 16).toByte())
    append((value ushr 8).toByte())
    append(value.toByte())
}
