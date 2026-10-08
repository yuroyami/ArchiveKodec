package io.github.yuroyami.archivekodec.codec

/**
 * Identifier for a compression codec. [implemented] tells you whether this build can
 * decode the codec. A container can name a method even when no engine handles it.
 */
public enum class CodecId(
    /** Human-readable lowercase codec name. */
    public val displayName: String,
    /** Whether [Codecs] supplies an encoder and decoder for this identifier. */
    public val implemented: Boolean,
) {
    /** Uncompressed bytes, with no framing or transformation. */
    STORE("store", true),
    /** Raw DEFLATE compression as defined by RFC 1951. */
    DEFLATE("deflate", true),
    /** DEFLATE with RFC 1950 zlib framing. */
    ZLIB("zlib", true),
    /** DEFLATE with RFC 1952 gzip framing. */
    GZIP("gzip", true),

    /** LZ4 frame compression, including frame headers and block boundaries. */
    LZ4("lz4", true),
    /** Snappy raw blocks; this identifier does not select Snappy stream framing. */
    SNAPPY("snappy", true),

    // Recognised names with no implementation.
    /** Bzip2 block-sorting compression. */
    BZIP2("bzip2", false),
    /** Lempel-Ziv-Markov chain compression. */
    LZMA("lzma", false),
    /** Chunked LZMA2 compression, used by XZ and 7z. */
    LZMA2("lzma2", false),
    /** XZ compressed-stream container. */
    XZ("xz", false),
    /** Zstandard compression. */
    ZSTD("zstd", false),
    /** Brotli compression. */
    BROTLI("brotli", false),
    /** Prediction by partial matching compression. */
    PPMD("ppmd", false),
    ;
}
