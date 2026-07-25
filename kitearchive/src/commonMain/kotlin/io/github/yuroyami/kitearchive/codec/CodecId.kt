package io.github.yuroyami.kitearchive.codec

/**
 * Identifier for a compression codec. Marks both the codecs implemented today and
 * the ones on the roadmap (see PORTING_STATUS.md), so containers can name a method
 * even before its engine ships.
 */
enum class CodecId(val displayName: String, val implemented: Boolean) {
    STORE("store", true),
    DEFLATE("deflate", true),
    ZLIB("zlib", true),
    GZIP("gzip", true),

    LZ4("lz4", true),
    SNAPPY("snappy", true),

    // Roadmap — not yet implemented.
    BZIP2("bzip2", false),
    LZMA("lzma", false),
    LZMA2("lzma2", false),
    XZ("xz", false),
    ZSTD("zstd", false),
    BROTLI("brotli", false),
    PPMD("ppmd", false),
    ;
}
