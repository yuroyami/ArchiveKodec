package io.github.yuroyami.archivekodec.archive.zip

import io.github.yuroyami.archivekodec.codec.CodecId

/** ZIP signatures and method mapping. */
internal object ZipFormat {
    const val LOC = 0x04034b50L          // local file header
    const val CEN = 0x02014b50L          // central directory header
    const val CEN_SIGNATURE = 0x05054b50L // optional central directory digital signature
    const val EOCD = 0x06054b50L         // end of central directory
    const val Z64_EOCD = 0x06064b50L     // zip64 end of central directory
    const val Z64_LOC = 0x07064b50L      // zip64 EOCD locator

    /** ZIP method code → codec id (null = unknown/unsupported method). */
    fun codecForMethod(m: Int): CodecId? = when (m) {
        0 -> CodecId.STORE
        8 -> CodecId.DEFLATE
        12 -> CodecId.BZIP2
        14 -> CodecId.LZMA
        93 -> CodecId.ZSTD
        98 -> CodecId.PPMD
        else -> null
    }

    /** Codec id → ZIP method code. */
    fun methodCode(c: CodecId): Int = when (c) {
        CodecId.STORE -> 0
        CodecId.DEFLATE -> 8
        CodecId.BZIP2 -> 12
        CodecId.LZMA -> 14
        CodecId.ZSTD -> 93
        CodecId.PPMD -> 98
        else -> throw IllegalArgumentException("codec $c has no ZIP method code")
    }
}
