package io.github.yuroyami.archivekodec.codec

/**
 * Identifier for a compression codec. [implemented] tells you whether this build can
 * decode the codec. A container can name a method even when no engine handles it.
 */
public enum class CodecId(public val displayName: String, public val implemented: Boolean) {
    STORE("store", true),
    DEFLATE("deflate", true),
    ZLIB("zlib", true),
    GZIP("gzip", true),

    LZ4("lz4", true),
    SNAPPY("snappy", true),

    // Recognised names with no implementation.
    BZIP2("bzip2", false),
    LZMA("lzma", false),
    LZMA2("lzma2", false),
    XZ("xz", false),
    ZSTD("zstd", false),
    BROTLI("brotli", false),
    PPMD("ppmd", false),
    ;
}
