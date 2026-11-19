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
import io.github.yuroyami.archivekodec.codec.lzw.Lzw
import io.github.yuroyami.archivekodec.codec.lzw.LzwOptions
import io.github.yuroyami.archivekodec.codec.packbits.PackBits
import io.github.yuroyami.archivekodec.codec.packbits.PackBitsOptions
import io.github.yuroyami.archivekodec.codec.palmdoc.PalmDoc
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
    @JvmStatic
    public val STORE: Codec get() = Store

    /** Raw DEFLATE without a wrapper header or checksum. */
    @JvmStatic
    public val DEFLATE: Codec get() = DeflateCodec

    /**
     * Raw Deflate64 (PKWARE "enhanced deflate", ZIP method 9). Decoding and encoding both use
     * the 64 KiB window and matches up to 65,538 bytes, so the output is not plain DEFLATE.
     */
    @JvmStatic
    public val DEFLATE64: Codec get() = Deflate64Codec

    /**
     * bzip2 at block size level 9. Decoding accepts any level, concatenated streams
     * and randomised blocks, and verifies the block and stream CRCs.
     */
    @JvmStatic
    public val BZIP2: Codec get() = Bzip2Codec

    /**
     * The `.lzma` ("LZMA alone") format: a 13-byte header, then one LZMA stream. Decoding
     * accepts a known size or an end marker; encoding writes the size and no marker.
     * ZIP readers decode method 14's own framing through this codec's registration.
     */
    @JvmStatic
    public val LZMA: Codec get() = LzmaCodec

    /** Raw LZMA2 chunks, as 7z stores them; decoding allows any dictionary distance. */
    @JvmStatic
    public val LZMA2: Codec get() = Lzma2Codec

    /**
     * The .xz container. Decoding verifies CRC-32, CRC-64 and SHA-256 checks and the index,
     * accepts concatenated streams, the delta filter and the x86 BCJ filter, and refuses the
     * other branch filters by name. Encoding
     * writes one LZMA2 block with a CRC-64 check.
     */
    @JvmStatic
    public val XZ: Codec get() = XzCodec

    /**
     * lzip (`.lz`, version 1): LZMA members ending with an end marker, each with a CRC-32, data
     * size and member size trailer. Decoding joins concatenated members and checks every
     * trailer; encoding writes one member.
     */
    @JvmStatic
    public val LZIP: Codec get() = LzipCodec

    /**
     * Unix `compress` (`.Z`): LZW with codes of 9 up to 16 bits, least significant bit first.
     * Decoding accepts maximum widths 10 to 16 (9 is refused) and block mode on or off.
     * Encoding uses 16 bits and block mode, clearing the table when the ratio stops improving,
     * as ncompress does.
     */
    @JvmStatic
    public val COMPRESS: Codec get() = CompressCodec

    /**
     * Zstandard (RFC 8878). Decoding handles every block and table mode, repeat offsets,
     * skippable and concatenated frames, and verifies the xxHash64 content checksum;
     * dictionary frames are refused. Encoding writes one frame with predefined tables.
     */
    @JvmStatic
    public val ZSTD: Codec get() = ZstdCodec

    /**
     * Brotli (RFC 7932), as in `Content-Encoding: br`. Decoding handles every meta-block
     * kind, context modelling, block switching and the built-in static dictionary, which
     * is inflated on first use. Encoding writes valid streams without context modelling or
     * dictionary references, so it compresses less than the reference encoder.
     */
    @JvmStatic
    public val BROTLI: Codec get() = BrotliCodec

    /**
     * PPMd var.I revision 1 in ZIP's framing (method 98): a two-byte header with the model
     * order, memory and restore method, then the coded data and an end marker. Decoding
     * supports the restart and cut-off restore methods; encoding uses order 6, up to 16 MiB
     * of model memory and restart. 7z's PPMd var.H is read by the 7z reader itself.
     */
    @JvmStatic
    public val PPMD: Codec get() = PpmdCodec

    /** Gzip framing; decoding concatenates members and also accepts one zlib stream. */
    @JvmStatic
    public val GZIP: Codec get() = GzipCodec

    /**
     * Zlib framing with Adler-32 validation. Options may set a preset dictionary; decoding one
     * needs `Zlib.decompress(stream, limits, dictionary)`, since this codec takes none.
     */
    @JvmStatic
    public val ZLIB: Codec get() = ZlibCodec

    /** LZ4 frame codec; decoding supports linked blocks and concatenated frames. */
    @JvmStatic
    public val LZ4: Codec get() = Lz4Codec

    /** Snappy raw-block codec with greedy back-reference compression. */
    @JvmStatic
    public val SNAPPY: Codec get() = SnappyCodec

    /** Standard Snappy framing with per-chunk CRC-32C validation; [SNAPPY] handles raw blocks. */
    @JvmStatic
    public val SNAPPY_FRAMED: Codec get() = SnappyFramedCodec

    /**
     * LZW in the form of PDF's `LZWDecode` with `EarlyChange` 1 and of TIFF compression 5. Encoding
     * takes [LzwOptions] for the GIF and old-style TIFF forms; [Lzw] decodes them, with an expected size.
     */
    @JvmStatic
    public val LZW: Codec get() = LzwCodec

    /**
     * TIFF's PackBits. Encoding takes [PackBitsOptions] for PDF's `RunLengthDecode`; [PackBits]
     * decodes it, with an expected size.
     */
    @JvmStatic
    public val PACKBITS: Codec get() = PackbitsCodec

    /** PalmDOC compression of one PalmDOC or MOBI text record. */
    @JvmStatic
    public val PALMDOC: Codec get() = PalmdocCodec

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

private fun deflateOptions(id: CodecId, options: CodecOptions): DeflateOptions =
    options as? DeflateOptions ?: throw IllegalArgumentException("$id takes DeflateOptions, not ${options::class.simpleName}")

// One object per codec: referencing one does not initialize, or keep, the others.

/** The implementation of [Codecs.DEFLATE]. */
internal object DeflateCodec : Codec {
    override val id = CodecId.DEFLATE
    override fun compress(data: ByteArray): ByteArray = Deflate.encode(data)
    override fun compress(data: ByteArray, options: CodecOptions): ByteArray = Deflate.encode(data, deflateOptions(id, options))
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
        Inflate.inflate(data, limits)
    override fun decompress(data: ByteArray, sink: ByteSink, limits: ArchiveLimits): Long = Inflate.inflateTo(data, limits, sink)
}

/** The implementation of [Codecs.DEFLATE64]. */
internal object Deflate64Codec : Codec {
    override val id = CodecId.DEFLATE64
    override fun compress(data: ByteArray): ByteArray = Deflate.encode64(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = Inflate.inflate64(data, limits)
    override fun decompress(data: ByteArray, sink: ByteSink, limits: ArchiveLimits): Long = Inflate.inflateTo(data, limits, sink, deflate64 = true)
}

/** The implementation of [Codecs.BZIP2]. */
internal object Bzip2Codec : Codec {
    override val id = CodecId.BZIP2
    override fun compress(data: ByteArray): ByteArray = Bzip2Encoder.compress(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = Bzip2Decoder.decompress(data, limits)
}

/** The implementation of [Codecs.LZMA]. */
internal object LzmaCodec : Codec {
    override val id = CodecId.LZMA
    override fun compress(data: ByteArray): ByteArray = LzmaAlone.compress(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = LzmaAlone.decompress(data, limits)
}

/** The implementation of [Codecs.LZMA2]. */
internal object Lzma2Codec : Codec {
    override val id = CodecId.LZMA2
    override fun compress(data: ByteArray): ByteArray = Lzma2.compress(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = Lzma2.decompress(data, limits)
}

/** The implementation of [Codecs.XZ]. */
internal object XzCodec : Codec {
    override val id = CodecId.XZ
    override fun compress(data: ByteArray): ByteArray = Xz.compress(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = Xz.decompress(data, limits)
}

/** The implementation of [Codecs.ZSTD]. */
internal object ZstdCodec : Codec {
    override val id = CodecId.ZSTD
    override fun compress(data: ByteArray): ByteArray = ZstdEncoder.compress(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = ZstdDecoder.decompress(data, limits)
}

/** The implementation of [Codecs.BROTLI]. */
internal object BrotliCodec : Codec {
    override val id = CodecId.BROTLI
    override fun compress(data: ByteArray): ByteArray = BrotliEncoder.compress(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = BrotliDecoder.decompress(data, limits)
}

/** The implementation of [Codecs.PPMD]. */
internal object PpmdCodec : Codec {
    override val id = CodecId.PPMD
    override fun compress(data: ByteArray): ByteArray = PpmdZip.compress(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = PpmdZip.decompress(data, limits)
}

/** The implementation of [Codecs.GZIP]. */
internal object GzipCodec : Codec {
    override val id = CodecId.GZIP
    override fun compress(data: ByteArray): ByteArray = Gzip.compress(data)
    override fun compress(data: ByteArray, options: CodecOptions): ByteArray =
        Gzip.compress(data, GzipHeader.DEFAULT, deflateOptions(id, options))
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
        Gzip.decompress(data, limits)
    override fun decompress(data: ByteArray, sink: ByteSink, limits: ArchiveLimits): Long = Gzip.decompressTo(data, limits, sink)
}

/** The implementation of [Codecs.ZLIB]. */
internal object ZlibCodec : Codec {
    override val id = CodecId.ZLIB
    override fun compress(data: ByteArray): ByteArray = Zlib.compress(data)
    override fun compress(data: ByteArray, options: CodecOptions): ByteArray = Zlib.compress(data, deflateOptions(id, options))
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
        Zlib.decompress(data, limits)
    override fun decompress(data: ByteArray, sink: ByteSink, limits: ArchiveLimits): Long = Zlib.decompressTo(data, limits, sink)
}

/** The implementation of [Codecs.LZ4]. */
internal object Lz4Codec : Codec {
    override val id = CodecId.LZ4
    override fun compress(data: ByteArray): ByteArray = Lz4.frameCompress(data)
    override fun compress(data: ByteArray, options: CodecOptions): ByteArray = Lz4.frameCompress(
        data, options as? Lz4Options ?: throw IllegalArgumentException("$id takes Lz4Options, not ${options::class.simpleName}"),
    )
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
        Lz4.frameDecompress(data, limits)
}

/** The implementation of [Codecs.SNAPPY]. */
internal object SnappyCodec : Codec {
    override val id = CodecId.SNAPPY
    override fun compress(data: ByteArray): ByteArray = Snappy.compress(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
        Snappy.decompress(data, limits)
}

/** The implementation of [Codecs.SNAPPY_FRAMED]. */
internal object SnappyFramedCodec : Codec {
    override val id = CodecId.SNAPPY_FRAMED
    override fun compress(data: ByteArray): ByteArray = Snappy.frameCompress(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
        Snappy.frameDecompress(data, limits)
}

/** The implementation of [Codecs.LZW]. */
internal object LzwCodec : Codec {
    override val id = CodecId.LZW
    override fun compress(data: ByteArray): ByteArray = Lzw.encode(data)
    override fun compress(data: ByteArray, options: CodecOptions): ByteArray = Lzw.encode(
        data, options as? LzwOptions ?: throw IllegalArgumentException("$id takes LzwOptions, not ${options::class.simpleName}"),
    )
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
        Lzw.decode(data, LzwOptions { this.limits = limits }).output
}

/** The implementation of [Codecs.PACKBITS]. */
internal object PackbitsCodec : Codec {
    override val id = CodecId.PACKBITS
    override fun compress(data: ByteArray): ByteArray = PackBits.encode(data)
    override fun compress(data: ByteArray, options: CodecOptions): ByteArray = PackBits.encode(
        data, options as? PackBitsOptions ?: throw IllegalArgumentException("$id takes PackBitsOptions, not ${options::class.simpleName}"),
    )
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
        PackBits.decode(data, PackBitsOptions { this.limits = limits }).output
}

/** The implementation of [Codecs.PALMDOC]. */
internal object PalmdocCodec : Codec {
    override val id = CodecId.PALMDOC
    override fun compress(data: ByteArray): ByteArray = PalmDoc.encode(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray =
        PalmDoc.decode(data, 0, data.size, limits).output
}

/** The implementation of [Codecs.LZIP]. */
internal object LzipCodec : Codec {
    override val id = CodecId.LZIP
    override fun compress(data: ByteArray): ByteArray = io.github.yuroyami.archivekodec.codec.lzma.Lzip.compress(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = io.github.yuroyami.archivekodec.codec.lzma.Lzip.decompress(data, limits)
}

/** The implementation of [Codecs.COMPRESS]. */
internal object CompressCodec : Codec {
    override val id = CodecId.COMPRESS
    override fun compress(data: ByteArray): ByteArray = io.github.yuroyami.archivekodec.codec.lzw.UnixCompress.compress(data)
    override fun decompress(data: ByteArray, limits: ArchiveLimits): ByteArray = io.github.yuroyami.archivekodec.codec.lzw.UnixCompress.decompress(data, limits)
}
