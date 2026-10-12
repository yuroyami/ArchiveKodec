package io.github.yuroyami.archivekodec.wrapper

import io.github.yuroyami.archivekodec.util.readU16Be

/** Validate CM, CINFO and FCHECK. FDICT identifies a valid but unsupported dictionary stream. */
internal fun hasZlibHeader(bytes: ByteArray): Boolean {
    if (bytes.size < 2) return false
    val cmf = bytes[0].toInt() and 0xff
    return cmf and 0x0f == 8 && cmf ushr 4 <= 7 && readU16Be(bytes, 0) % 31 == 0
}
