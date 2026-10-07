package io.github.yuroyami.archivekodec.archive

/** A container / single-stream format detected by [Magic]. */
public enum class ArchiveFormat {
    ZIP, TAR, AR, CPIO, GZIP, ZLIB, BZIP2, XZ, LZMA, ZSTD, LZ4, SEVEN_ZIP, RAR, BROTLI, SNAPPY, UNKNOWN
}
