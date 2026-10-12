package io.github.yuroyami.archivekodec.codec

import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.codec.bzip2.Bzip2Decoder
import io.github.yuroyami.archivekodec.codec.bzip2.Bzip2Encoder
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.wrapper.GzipHeader
import io.github.yuroyami.archivekodec.codec.lz4.Lz4Options
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.codec.lzma.Lzma2
import io.github.yuroyami.archivekodec.codec.lzma.LzmaAlone
import io.github.yuroyami.archivekodec.codec.lzma.Xz
import io.github.yuroyami.archivekodec.codec.brotli.BrotliDecoder
import io.github.yuroyami.archivekodec.codec.brotli.BrotliEncoder
import io.github.yuroyami.archivekodec.codec.ppmd.PpmdZip
import io.github.yuroyami.archivekodec.codec.zstd.ZstdDecoder
import io.github.yuroyami.archivekodec.codec.zstd.ZstdEncoder
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import io.github.yuroyami.archivekodec.codec.snappy.Snappy
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Gzip
import io.github.yuroyami.archivekodec.wrapper.Zlib

/**
 * Built-in codec implementations, each adapting a low-level engine
 * ([Deflate]/[Inflate], [Gzip], [Zlib], [Store]) to the [Codec] contract.
 * Lookups delegate to [CodecRegistry.DEFAULT]. Customize an immutable registry
 * with [CodecRegistry.with] and pass it to a ZIP reader to replace these engines.
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
        override fun compress(data: ByteArray, options: CodecOptions): ByteArray = Deflate.encode(data, deflateOptions(id, options))
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Inflate.inflate(data, limits)
        override fun decompress(data: ByteArray, sink: ByteSink, limits: ArchiveLimits): Long = Inflate.inflateTo(data, limits, sink)
    }

    private fun deflateOptions(id: CodecId, options: CodecOptions): DeflateOptions =
        options as? DeflateOptions ?: throw IllegalArgumentException("$id takes DeflateOptions, not ${options::class.simpleName}")

    /**
     * Raw Deflate64 (PKWARE "enhanced deflate", ZIP method 9). Decoding handles the 64 KiB
     * window and long matches; encoding emits DEFLATE-compatible codes within 32 KiB.
     */
    @JvmField
    public val DEFLATE64: Codec = object : Codec {
        override val id = CodecId.DEFLATE64
        override fun compress(data: ByteArray): ByteArray = Deflate.encode64(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = Inflate.inflate64(data, limits)
        override fun decompress(data: ByteArray, sink: ByteSink, limits: ArchiveLimits): Long = Inflate.inflateTo(data, limits, sink, deflate64 = true)
    }

    /**
     * bzip2 at block size level 9. Decoding accepts any level, concatenated streams
     * and randomised blocks, and verifies the block and stream CRCs.
     */
    @JvmField
    public val BZIP2: Codec = object : Codec {
        override val id = CodecId.BZIP2
        override fun compress(data: ByteArray): ByteArray = Bzip2Encoder.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = Bzip2Decoder.decompress(data, limits)
    }

    /**
     * The `.lzma` ("LZMA alone") format: a 13-byte header, then one LZMA stream. Decoding
     * accepts a known size or an end marker; encoding writes the size and no marker.
     * ZIP readers decode method 14's own framing through this codec's registration.
     */
    @JvmField
    public val LZMA: Codec = object : Codec {
        override val id = CodecId.LZMA
        override fun compress(data: ByteArray): ByteArray = LzmaAlone.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = LzmaAlone.decompress(data, limits)
    }

    /** Raw LZMA2 chunks, as 7z stores them; decoding allows any dictionary distance. */
    @JvmField
    public val LZMA2: Codec = object : Codec {
        override val id = CodecId.LZMA2
        override fun compress(data: ByteArray): ByteArray = Lzma2.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = Lzma2.decompress(data, limits)
    }

    /**
     * The .xz container. Decoding verifies CRC-32, CRC-64 and SHA-256 checks and the index,
     * accepts concatenated streams and the delta filter, and refuses BCJ filters. Encoding
     * writes one LZMA2 block with a CRC-64 check.
     */
    @JvmField
    public val XZ: Codec = object : Codec {
        override val id = CodecId.XZ
        override fun compress(data: ByteArray): ByteArray = Xz.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = Xz.decompress(data, limits)
    }

    /**
     * Zstandard (RFC 8878). Decoding handles every block and table mode, repeat offsets,
     * skippable and concatenated frames, and verifies the xxHash64 content checksum;
     * dictionary frames are refused. Encoding writes one frame with predefined tables.
     */
    @JvmField
    public val ZSTD: Codec = object : Codec {
        override val id = CodecId.ZSTD
        override fun compress(data: ByteArray): ByteArray = ZstdEncoder.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = ZstdDecoder.decompress(data, limits)
    }

    /**
     * Brotli (RFC 7932), as in `Content-Encoding: br`. Decoding handles every meta-block
     * kind, context modelling, block switching and the built-in static dictionary, which
     * is inflated on first use. Encoding writes valid streams without context modelling or
     * dictionary references, so it compresses less than the reference encoder.
     */
    @JvmField
    public val BROTLI: Codec = object : Codec {
        override val id = CodecId.BROTLI
        override fun compress(data: ByteArray): ByteArray = BrotliEncoder.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = BrotliDecoder.decompress(data, limits)
    }

    /**
     * PPMd var.I revision 1 in ZIP's framing (method 98): a two-byte header with the model
     * order, memory and restore method, then the coded data and an end marker. Decoding
     * supports the restart and cut-off restore methods; encoding uses order 6, up to 16 MiB
     * of model memory and restart. 7z's PPMd var.H is read by the 7z reader itself.
     */
    @JvmField
    public val PPMD: Codec = object : Codec {
        override val id = CodecId.PPMD
        override fun compress(data: ByteArray): ByteArray = PpmdZip.compress(data)
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = PpmdZip.decompress(data, limits)
    }

    /** Gzip framing; decoding concatenates members and also accepts one zlib stream. */
    @JvmField
    public val GZIP: Codec = object : Codec {
        override val id = CodecId.GZIP
        override fun compress(data: ByteArray): ByteArray = Gzip.compress(data)
        override fun compress(data: ByteArray, options: CodecOptions): ByteArray =
            Gzip.compress(data, GzipHeader.DEFAULT, deflateOptions(id, options))
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Gzip.decompress(data, limits)
        override fun decompress(data: ByteArray, sink: ByteSink, limits: ArchiveLimits): Long = Gzip.decompressTo(data, limits, sink)
    }

    /**
     * Zlib framing with Adler-32 validation. Options may set a preset dictionary; decoding one
     * needs `Zlib.decompress(stream, limits, dictionary)`, since this codec takes none.
     */
    @JvmField
    public val ZLIB: Codec = object : Codec {
        override val id = CodecId.ZLIB
        override fun compress(data: ByteArray): ByteArray = Zlib.compress(data)
        override fun compress(data: ByteArray, options: CodecOptions): ByteArray = Zlib.compress(data, deflateOptions(id, options))
        override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
            Zlib.decompress(data, limits)
        override fun decompress(data: ByteArray, sink: ByteSink, limits: ArchiveLimits): Long = Zlib.decompressTo(data, limits, sink)
    }

    /** LZ4 frame codec; decoding supports linked blocks and concatenated frames. */
    @JvmField
    public val LZ4: Codec = object : Codec {
        override val id = CodecId.LZ4
        override fun compress(data: ByteArray): ByteArray = Lz4.frameCompress(data)
        override fun compress(data: ByteArray, options: CodecOptions): ByteArray = Lz4.frameCompress(
            data, options as? Lz4Options ?: throw IllegalArgumentException("$id takes Lz4Options, not ${options::class.simpleName}"),
        )
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

    /** The codec for [id], or null if not yet implemented. */
    @JvmStatic
    public fun byId(id: CodecId): Codec? = CodecRegistry.DEFAULT.byId(id)

    /**
     * Look up a [CodecId.displayName], ignoring case. Unknown or unavailable names
     * return null. Names are codec identifiers, not HTTP Content-Encoding aliases;
     * `deflate` selects raw DEFLATE and `snappy` selects raw Snappy blocks.
     */
    @JvmStatic
    public fun byName(name: String): Codec? = CodecRegistry.DEFAULT.byName(name)

    /**
     * Decoder for a compressed-stream [format], or null for containers, unknown
     * formats and unavailable codecs. [ArchiveFormat.SNAPPY] selects framed Snappy.
     */
    @JvmStatic
    public fun byFormat(format: ArchiveFormat): Codec? = CodecRegistry.DEFAULT.byFormat(format)

    /** Ids of the codecs available right now. */
    @JvmStatic
    public val implemented: List<CodecId> get() = CodecRegistry.DEFAULT.implemented
}
