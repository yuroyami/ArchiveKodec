package io.github.yuroyami.archivekodec.codec

import kotlin.jvm.JvmField

/**
 * Open compression identifier, compared by its lowercase ASCII [name]. Construct
 * application identifiers with `CodecId("my-codec")`; equal names identify the
 * same codec even when constructed separately. Names are codec identifiers, not
 * HTTP Content-Encoding aliases. Availability belongs to a [CodecRegistry].
 *
 * This ordinary immutable class is not an enum: there is no exhaustive
 * list or ordinal. Keep an `else` branch when dispatching on identifiers.
 *
 * @throws IllegalArgumentException if [name] is empty, does not start with an ASCII
 * letter/digit, or contains characters other than ASCII letters/digits, `.`, `_`, `+`, `-`.
 */
public class CodecId @Throws(IllegalArgumentException::class) public constructor(name: String) {
    /** Canonical lowercase ASCII identifier. */
    public val name: String

    init {
        fun letterOrDigit(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
        require(name.isNotEmpty() && letterOrDigit(name[0]) && name.all { letterOrDigit(it) || it in "._+-" }) {
            "codec name must be a nonempty ASCII identifier beginning with a letter or digit"
        }
        this.name = name.lowercase()
    }

    /** Human-readable codec name; the same canonical value as [name]. */
    public val displayName: String get() = name

    /** Compare canonical names; implementation availability is not part of identity. */
    override fun equals(other: Any?): Boolean = other is CodecId && name == other.name

    /** Hash the canonical name. */
    override fun hashCode(): Int = name.hashCode()

    /** Return the canonical name. */
    override fun toString(): String = name

    /** Well-known identifiers, including formats without a built-in implementation. */
    public companion object {
        /** Uncompressed bytes, with no framing or transformation. */
        @JvmField public val STORE: CodecId = CodecId("store")
        /** Raw DEFLATE compression as defined by RFC 1951. */
        @JvmField public val DEFLATE: CodecId = CodecId("deflate")
        /** PKWARE Deflate64 ("enhanced deflate"), ZIP method 9: a 64 KiB window and longer matches. */
        @JvmField public val DEFLATE64: CodecId = CodecId("deflate64")
        /** DEFLATE with RFC 1950 zlib framing. */
        @JvmField public val ZLIB: CodecId = CodecId("zlib")
        /** DEFLATE with RFC 1952 gzip framing. */
        @JvmField public val GZIP: CodecId = CodecId("gzip")
        /** LZ4 frame compression, including headers and block boundaries. */
        @JvmField public val LZ4: CodecId = CodecId("lz4")
        /** Raw Snappy blocks, without stream framing. */
        @JvmField public val SNAPPY: CodecId = CodecId("snappy")
        /** Standard Snappy stream framing with masked CRC-32C checksums. */
        @JvmField public val SNAPPY_FRAMED: CodecId = CodecId("snappy-framed")
        /** Bzip2 block-sorting compression. */
        @JvmField public val BZIP2: CodecId = CodecId("bzip2")
        /** Lempel-Ziv-Markov chain compression. */
        @JvmField public val LZMA: CodecId = CodecId("lzma")
        /** Chunked LZMA2 compression, used by XZ and 7z. */
        @JvmField public val LZMA2: CodecId = CodecId("lzma2")
        /** XZ compressed-stream container. */
        @JvmField public val XZ: CodecId = CodecId("xz")
        /** Zstandard compression. */
        @JvmField public val ZSTD: CodecId = CodecId("zstd")
        /** Brotli compression. */
        @JvmField public val BROTLI: CodecId = CodecId("brotli")
        /** Prediction by partial matching compression. */
        @JvmField public val PPMD: CodecId = CodecId("ppmd")
    }
}
