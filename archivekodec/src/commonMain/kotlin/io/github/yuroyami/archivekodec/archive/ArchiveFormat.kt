package io.github.yuroyami.archivekodec.archive

/**
 * A container or single-stream format identifier. [Magic] recognizes only formats
 * with supported identifying headers; it never returns [LZMA], [BROTLI] or [SNAPPY].
 * An identifier does not imply that a reader or decoder is implemented.
 */
public enum class ArchiveFormat {
    /** ZIP member container, including supported ZIP64 metadata. */
    ZIP,
    /** TAR file container with fixed-size record headers. */
    TAR,
    /** Unix ar member container used by static libraries and Debian packages. */
    AR,
    /** Cpio member container used by archive tools and Linux initramfs images. */
    CPIO,
    /** Gzip framing around one or more DEFLATE streams. */
    GZIP,
    /** Zlib framing around a DEFLATE stream with an Adler-32 checksum. */
    ZLIB,
    /** Bzip2 compressed stream with block-sorting compression. */
    BZIP2,
    /** XZ compressed-stream container, commonly carrying LZMA2 data. */
    XZ,
    /** LZMA stream identifier; the sniffer does not return this value. */
    LZMA,
    /** Zstandard compressed frame format. */
    ZSTD,
    /** LZ4 compressed frame format. */
    LZ4,
    /** 7z member container. */
    SEVEN_ZIP,
    /** RAR member container. */
    RAR,
    /** Brotli stream identifier; the sniffer does not return this value. */
    BROTLI,
    /** Snappy stream identifier; raw blocks have no dependable signature. */
    SNAPPY,
    /** The inspected bytes do not identify a recognized format. */
    UNKNOWN,
}
