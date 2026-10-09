package io.github.yuroyami.archivekodec.codec

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.codec.snappy.Snappy
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Gzip
import io.github.yuroyami.archivekodec.wrapper.Zlib

/**
 * Registry of the codecs implemented today, each adapting a low-level engine
 * ([Deflate]/[Inflate], [Gzip], [Zlib], [Store]) to the [Codec] contract.
 */
public object Codecs {

    /** Identity transform; decompression still enforces the supplied limits. */
    @JvmField
    public val STORE: Codec = Store

    /** Raw DEFLATE without a wrapper header or checksum. */
    @JvmField
    public val DEFLATE: Codec = object : Codec {
        override val id = CodecId.DEFLATE
        override fun compress(data: ByteArray): ByteArray = Deflate.encode(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Inflate.inflate(data, limits)
    }

    /** Gzip framing; decoding concatenates members and also accepts one zlib stream. */
    @JvmField
    public val GZIP: Codec = object : Codec {
        override val id = CodecId.GZIP
        override fun compress(data: ByteArray): ByteArray = Gzip.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Gzip.decompress(data, limits)
    }

    /** Zlib framing with Adler-32 validation and no preset dictionary support. */
    @JvmField
    public val ZLIB: Codec = object : Codec {
        override val id = CodecId.ZLIB
        override fun compress(data: ByteArray): ByteArray = Zlib.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Zlib.decompress(data, limits)
    }

    /** LZ4 frame codec; decoding supports linked blocks and concatenated frames. */
    @JvmField
    public val LZ4: Codec = object : Codec {
        override val id = CodecId.LZ4
        override fun compress(data: ByteArray): ByteArray = Lz4.frameCompress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Lz4.frameDecompress(data, limits)
    }

    /** Snappy raw-block codec with greedy back-reference compression. */
    @JvmField
    public val SNAPPY: Codec = object : Codec {
        override val id = CodecId.SNAPPY
        override fun compress(data: ByteArray): ByteArray = Snappy.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Snappy.decompress(data, limits)
    }

    /** Standard Snappy framing with per-chunk CRC-32C validation; [SNAPPY] handles raw blocks. */
    @JvmField
    public val SNAPPY_FRAMED: Codec = object : Codec {
        override val id = CodecId.SNAPPY_FRAMED
        override fun compress(data: ByteArray): ByteArray = Snappy.frameCompress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Snappy.frameDecompress(data, limits)
    }

    private val all: List<Codec> = listOf(STORE, DEFLATE, GZIP, ZLIB, LZ4, SNAPPY, SNAPPY_FRAMED)

    /** The codec for [id], or null if not yet implemented. */
    @JvmStatic
    public fun byId(id: CodecId): Codec? = all.firstOrNull { it.id == id }

    /**
     * Look up a [CodecId.displayName], ignoring case. Unknown or unavailable names
     * return null. Names are codec identifiers, not HTTP Content-Encoding aliases;
     * `deflate` selects raw DEFLATE and `snappy` selects raw Snappy blocks.
     */
    @JvmStatic
    public fun byName(name: String): Codec? = all.firstOrNull { it.id.displayName.equals(name, ignoreCase = true) }

    /**
     * Decoder for a compressed-stream [format], or null for containers, unknown
     * formats and unavailable codecs. [ArchiveFormat.SNAPPY] selects framed Snappy.
     */
    @JvmStatic
    public fun byFormat(format: ArchiveFormat): Codec? = when (format) {
        ArchiveFormat.GZIP -> byId(CodecId.GZIP)
        ArchiveFormat.ZLIB -> byId(CodecId.ZLIB)
        ArchiveFormat.LZ4 -> byId(CodecId.LZ4)
        ArchiveFormat.SNAPPY -> byId(CodecId.SNAPPY_FRAMED)
        ArchiveFormat.BZIP2 -> byId(CodecId.BZIP2)
        ArchiveFormat.XZ -> byId(CodecId.XZ)
        ArchiveFormat.LZMA -> byId(CodecId.LZMA)
        ArchiveFormat.ZSTD -> byId(CodecId.ZSTD)
        ArchiveFormat.BROTLI -> byId(CodecId.BROTLI)
        else -> null
    }

    /** Ids of the codecs available right now. */
    @JvmStatic
    public val implemented: List<CodecId> get() = all.map { it.id }
}
