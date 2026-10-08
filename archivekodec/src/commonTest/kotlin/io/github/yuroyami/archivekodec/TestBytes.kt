package io.github.yuroyami.archivekodec

/** Decode frozen hexadecimal fixtures without using production binary helpers. */
internal fun hex(value: String): ByteArray {
    require(value.length % 2 == 0) { "hex fixture must contain complete bytes" }
    return ByteArray(value.length / 2) {
        ((value[it * 2].digitToInt(16) shl 4) or value[it * 2 + 1].digitToInt(16)).toByte()
    }
}
