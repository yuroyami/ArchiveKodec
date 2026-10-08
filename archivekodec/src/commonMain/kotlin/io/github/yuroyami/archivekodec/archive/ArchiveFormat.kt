package io.github.yuroyami.archivekodec.archive

/**
 * A container or single-stream format identifier. [Magic] recognizes only formats
 * with supported identifying headers; it never returns [LZMA], [BROTLI] or [SNAPPY].
 * An identifier does not imply that a reader or decoder is implemented.
 */
public enum class ArchiveFormat {
    ZIP, TAR, AR, CPIO, GZIP, ZLIB, BZIP2, XZ, LZMA, ZSTD, LZ4, SEVEN_ZIP, RAR, BROTLI, SNAPPY, UNKNOWN
}
