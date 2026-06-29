package io.github.yuroyami.kitearchive.archive

/** A container / single-stream format detected by [Magic]. */
enum class ArchiveFormat {
    ZIP, TAR, GZIP, ZLIB, BZIP2, XZ, LZMA, ZSTD, LZ4, SEVEN_ZIP, RAR, BROTLI, SNAPPY, UNKNOWN
}
