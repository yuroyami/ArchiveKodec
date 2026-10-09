package io.github.yuroyami.archivekodec.archive

import kotlin.jvm.JvmField

/**
 * Open container or stream identifier, compared by its canonical lowercase ASCII
 * [id]. Construct application values with `ArchiveFormat("squashfs")` and use the
 * same value in a [FormatSpec] and its [ArchiveReader.format]. An identifier does
 * not imply an available reader or decoder. New identifiers need an `else` branch
 * in dispatch; this class has no enum entries, ordinal or exhaustive value list.
 *
 * @throws IllegalArgumentException unless [id] starts with an ASCII letter/digit
 * and contains only ASCII letters/digits, `.`, `_`, `+` or `-`.
 */
public class ArchiveFormat @Throws(IllegalArgumentException::class) public constructor(id: String) {
    /** Canonical lowercase ASCII format identifier, used for explicit selection. */
    public val id: String

    init {
        fun letterOrDigit(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
        require(id.isNotEmpty() && letterOrDigit(id[0]) && id.all { letterOrDigit(it) || it in "._+-" }) {
            "format id must be a nonempty ASCII identifier beginning with a letter or digit"
        }
        this.id = id.lowercase()
    }

    /** Uppercase diagnostic name; preserves the spelling of the former built-in enum names. */
    public val name: String get() = id.uppercase()

    /** Compare canonical identifiers. */
    override fun equals(other: Any?): Boolean = other is ArchiveFormat && id == other.id
    /** Hash the canonical identifier. */
    override fun hashCode(): Int = id.hashCode()
    /** Return the uppercase diagnostic [name]. */
    override fun toString(): String = name

    /** Well-known identifiers and the unknown-detection sentinel. */
    public companion object {
        /** ZIP member container, including supported ZIP64 metadata. */
        @JvmField public val ZIP: ArchiveFormat = ArchiveFormat("zip")
        /** TAR file container with fixed-size record headers. */
        @JvmField public val TAR: ArchiveFormat = ArchiveFormat("tar")
        /** Unix ar member container used by static libraries and Debian packages. */
        @JvmField public val AR: ArchiveFormat = ArchiveFormat("ar")
        /** Cpio member container used by archive tools and Linux initramfs images. */
        @JvmField public val CPIO: ArchiveFormat = ArchiveFormat("cpio")
        /** Gzip framing around one or more DEFLATE streams. */
        @JvmField public val GZIP: ArchiveFormat = ArchiveFormat("gzip")
        /** Zlib framing around a DEFLATE stream with an Adler-32 checksum. */
        @JvmField public val ZLIB: ArchiveFormat = ArchiveFormat("zlib")
        /** Bzip2 compressed stream with block-sorting compression. */
        @JvmField public val BZIP2: ArchiveFormat = ArchiveFormat("bzip2")
        /** XZ compressed-stream container, commonly carrying LZMA2 data. */
        @JvmField public val XZ: ArchiveFormat = ArchiveFormat("xz")
        /** LZMA stream identifier; the sniffer does not return this value. */
        @JvmField public val LZMA: ArchiveFormat = ArchiveFormat("lzma")
        /** Zstandard compressed frame format. */
        @JvmField public val ZSTD: ArchiveFormat = ArchiveFormat("zstd")
        /** LZ4 compressed frame format. */
        @JvmField public val LZ4: ArchiveFormat = ArchiveFormat("lz4")
        /** 7z member container. */
        @JvmField public val SEVEN_ZIP: ArchiveFormat = ArchiveFormat("seven_zip")
        /** RAR member container. */
        @JvmField public val RAR: ArchiveFormat = ArchiveFormat("rar")
        /** Brotli stream identifier; the sniffer does not return this value. */
        @JvmField public val BROTLI: ArchiveFormat = ArchiveFormat("brotli")
        /** Standard Snappy framed stream; raw blocks have no dependable signature. */
        @JvmField public val SNAPPY: ArchiveFormat = ArchiveFormat("snappy")
        /** ISO 9660 image (ECMA-119), with Joliet and Rock Ridge names; its signature is at byte 32769. */
        @JvmField public val ISO9660: ArchiveFormat = ArchiveFormat("iso9660")
        /** Microsoft cabinet (`.cab`): solid folders of stored, MSZIP, LZX or Quantum data. */
        @JvmField public val CAB: ArchiveFormat = ArchiveFormat("cab")
        /** The inspected bytes do not identify a recognized format. */
        @JvmField public val UNKNOWN: ArchiveFormat = ArchiveFormat("unknown")
    }
}
