package io.github.yuroyami.archivekodec.archive.cpio

/** Old binary cpio stores octal 070707 as a 16-bit word in either byte order. */
internal fun isBinaryCpioMagic(bytes: ByteArray): Boolean = bytes.size >= 2 && (
    (bytes[0] == 0xc7.toByte() && bytes[1] == 0x71.toByte()) ||
        (bytes[0] == 0x71.toByte() && bytes[1] == 0xc7.toByte())
    )
