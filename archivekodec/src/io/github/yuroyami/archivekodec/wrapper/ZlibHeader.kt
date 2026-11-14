package io.github.yuroyami.archivekodec.wrapper

import io.github.yuroyami.archivekodec.util.readU16Be

/** Validate CM, CINFO and FCHECK. FDICT identifies a valid but unsupported dictionary stream. */
internal fun hasZlibHeader(bytes: ByteArray, offset: Int = 0): Boolean {
    if (bytes.size - offset < 2) return false
    val cmf = bytes[offset].toInt() and 0xff
    return cmf and 0x0f == 8 && cmf ushr 4 <= 7 && readU16Be(bytes, offset) % 31 == 0
}
