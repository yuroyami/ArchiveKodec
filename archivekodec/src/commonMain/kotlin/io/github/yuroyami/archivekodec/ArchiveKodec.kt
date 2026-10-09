package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.ArchiveOpenOptions
import io.github.yuroyami.archivekodec.archive.FormatRegistry
import io.github.yuroyami.archivekodec.archive.FormatSpec
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.readFullyChecked
import io.github.yuroyami.archivekodec.archive.zip.ZipEndRecord
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Gzip
import kotlin.jvm.JvmStatic
import kotlin.jvm.JvmOverloads

/**
 * Top-level entry point for the library. Use it to detect an archive format, get a
 * codec by id, name or format, decode a recognized compressed stream, or open
 * bytes/a seekable source as a container reader, including one gzip wrapper.
 *
 * Pure `commonMain`, `kotlin-stdlib` only. The separate `:archivekodec-io` artifact
 * provides convenience adapters that drain a kotlinx-io `RawSource` into memory;
 * it does not provide streaming codecs or seekable file handles.
 */
public object ArchiveKodec {

    /**
     * The version of the ArchiveKodec artifact that is running, such as `0.2.0`.
     *
     * The build generates it from the Gradle `version` property. It is a property,
     * not a `const`, so a caller compiled against one release reads the value of the
     * release it runs with.
     */
    @JvmStatic
    public val VERSION: String get() = GENERATED_VERSION

    /** Detect the format of a header buffer. */
    @JvmStatic
    public fun sniff(header: ByteArray): ArchiveFormat =
        FormatRegistry.DEFAULT.sniff(ByteArrayRandomAccessSource(header))?.id ?: ArchiveFormat.UNKNOWN

    /**
     * Detect the format of a seekable source (reads up to the first 512 bytes).
     *
     * @throws ArchiveFormatException if the source reports an invalid size, premature EOF or invalid read count.
     * @throws ArchiveException if the supplied source reports an IO or lifecycle failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun sniff(source: RandomAccessSource): ArchiveFormat = sniff(source, FormatRegistry.DEFAULT)

    /**
     * Detect with [formats], including custom probes beyond the built-in header.
     * The full supplied buffer is available to probes; no source ownership transfers.
     * @throws ArchiveException if a custom probe fails.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun sniff(header: ByteArray, formats: FormatRegistry): ArchiveFormat =
        sniff(ByteArrayRandomAccessSource(header), formats)

    /**
     * Detect with an immutable registry without opening or closing [source].
     * @throws ArchiveException if a probe or source read fails.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun sniff(source: RandomAccessSource, formats: FormatRegistry): ArchiveFormat =
        formats.sniff(source)?.id ?: ArchiveFormat.UNKNOWN

    /** The codec in the immutable default registry for [id], or null if unavailable. */
    @JvmStatic
    public fun codec(id: CodecId): Codec? = Codecs.byId(id)

    /**
     * The registered codec for a compressed-stream [format], or null for containers
     * and unavailable formats. [ArchiveFormat.SNAPPY] means framed Snappy.
     */
    @JvmStatic
    public fun codec(format: ArchiveFormat): Codec? = Codecs.byFormat(format)

    /**
     * Look up a [CodecId.displayName], ignoring case; null if unknown or unavailable.
     * These are codec names, not HTTP aliases: `deflate` is raw DEFLATE and `snappy`
     * is raw Snappy. Use `zlib` and `snappy-framed` for their respective framing.
     */
    @JvmStatic
    public fun codec(name: String): Codec? = Codecs.byName(name)

    /** Codec ids available right now. */
    @JvmStatic
    public val codecs: List<CodecId> get() = Codecs.implemented

    /** Identity codec: returns the input bytes unchanged. */
    @JvmStatic
    public val store: Codec get() = Codecs.STORE
    /** Raw DEFLATE codec, without gzip or zlib framing. */
    @JvmStatic
    public val deflate: Codec get() = Codecs.DEFLATE
    /** Raw Deflate64 codec, ZIP method 9. */
    @JvmStatic
    public val deflate64: Codec get() = Codecs.DEFLATE64
    /** bzip2 codec; decoding accepts concatenated streams. */
    @JvmStatic
    public val bzip2: Codec get() = Codecs.BZIP2
    /** `.lzma` ("LZMA alone") codec. */
    @JvmStatic
    public val lzma: Codec get() = Codecs.LZMA
    /** Raw LZMA2 codec. */
    @JvmStatic
    public val lzma2: Codec get() = Codecs.LZMA2
    /** .xz codec; decoding accepts concatenated streams. */
    @JvmStatic
    public val xz: Codec get() = Codecs.XZ
    /** Zstandard codec; decoding accepts concatenated and skippable frames. */
    @JvmStatic
    public val zstd: Codec get() = Codecs.ZSTD
    /** Brotli codec (RFC 7932). Brotli has no magic bytes, so [decompress] cannot sniff it. */
    @JvmStatic
    public val brotli: Codec get() = Codecs.BROTLI
    /** PPMd var.I codec in ZIP method 98 framing. */
    @JvmStatic
    public val ppmd: Codec get() = Codecs.PPMD
    /** Gzip encoder and decoder; decoding also accepts one zlib stream. */
    @JvmStatic
    public val gzip: Codec get() = Codecs.GZIP
    /** Zlib codec with RFC 1950 framing and Adler-32 integrity checks. */
    @JvmStatic
    public val zlib: Codec get() = Codecs.ZLIB
    /** LZ4 frame encoder and decoder. */
    @JvmStatic
    public val lz4: Codec get() = Codecs.LZ4
    /** Raw Snappy block encoder and decoder, without stream framing. */
    @JvmStatic
    public val snappy: Codec get() = Codecs.SNAPPY
    /** Standard Snappy framed-stream encoder and decoder. */
    @JvmStatic
    public val snappyFramed: Codec get() = Codecs.SNAPPY_FRAMED

    /**
     * Detect and decode one supported compressed stream with [ArchiveLimits.DEFAULT].
     * @throws ArchiveException for the format, integrity, unsupported-codec and limit failures documented by [decompress].
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun decompress(bytes: ByteArray): ByteArray = decompress(bytes, ArchiveLimits.DEFAULT)

    /**
     * Sniff [bytes] and decode gzip, zlib, bzip2, xz, zstd, LZ4 or framed Snappy under [limits].
     * Only one framing layer is decoded; concatenated members follow the codec's
     * rules. Raw DEFLATE, raw Snappy, LZMA and brotli cannot be reliably sniffed and
     * need an explicit codec. Unsupported errors name the detected format.
     *
     * @throws ArchiveUnsupportedException if the format has no implemented stream decoder (including UNKNOWN and containers), or decoding needs an unsupported feature or dictionary.
     * @throws ArchiveFormatException for malformed framing, inconsistent sizes or failed integrity checks.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed DEFLATE data.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if decoding exceeds [limits].
     * @throws ArchiveSizeException if the output cannot fit in a ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun decompress(bytes: ByteArray, limits: ArchiveLimits): ByteArray {
        val format = sniff(bytes)
        val decoder = codec(format) ?: throw ArchiveUnsupportedException("no stream decoder for $format", offset = 0)
        return decoder.decompress(bytes, limits)
    }

    /**
     * Open archive [bytes], unwrapping at most one gzip layer with [ArchiveLimits.DEFAULT].
     * Plain archive bytes are borrowed without copying; keep them unchanged until
     * the returned reader closes. Gzip decoding takes an independent snapshot.
     * @throws ArchiveException for the detection, gzip, unsupported-format and limit failures documented by [open].
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(bytes: ByteArray): ArchiveReader = open(bytes, ArchiveLimits.DEFAULT)

    /**
     * Open archive [bytes] with [limits] for gzip decoding, listing and each read.
     * Plain bytes are borrowed unchanged until close; gzip output is a snapshot.
     * @throws ArchiveException for the detection, gzip, unsupported-format and limit failures documented by [open].
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(bytes: ByteArray, limits: ArchiveLimits): ArchiveReader =
        openBytes(bytes, FormatRegistry.DEFAULT, null, options(null, limits))

    /**
     * Open archive [bytes] with explicit filename decoding and default limits.
     * Plain bytes are borrowed unchanged until close; gzip output is a snapshot.
     * @throws ArchiveException for the detection, gzip, unsupported-format and limit failures documented by [open].
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(bytes: ByteArray, nameDecoder: ArchiveNameDecoder): ArchiveReader =
        open(bytes, nameDecoder, ArchiveLimits.DEFAULT)

    /**
     * Open archive [bytes] with explicit names and [limits]. Gzip decoding, listing
     * and each entry read enforce the same policy separately, without a cumulative
     * budget. Plain bytes are borrowed until close; gzip output is a snapshot.
     * @throws ArchiveFormatException if the archive is unrecognized or gzip framing/integrity is invalid.
     * @throws ArchiveUnsupportedException if gzip uses an unsupported feature or the recognized format has no container reader, including another gzip layer.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed gzip DEFLATE data.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if gzip decoding exceeds [limits].
     * @throws ArchiveSizeException if decoded gzip output cannot fit in a ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(bytes: ByteArray, nameDecoder: ArchiveNameDecoder, limits: ArchiveLimits): ArchiveReader =
        openBytes(bytes, FormatRegistry.DEFAULT, null, options(nameDecoder, limits))

    /**
     * Open a container reader for a seekable [source], dispatching on the sniffed
     * format. Gzip is decoded fully before opening its contained archive; only one
     * gzip layer is unwrapped. Other stream wrappers are not opened automatically.
     * An unknown header falls back to a bounded ZIP footer search, allowing
     * prepended launchers. Known header formats retain priority over that footer.
     * Ownership transfers to the returned reader. If detection or opening
     * throws, the caller still owns [source] and must close it.
     * Gzip decode errors refer to compressed-input offsets; reader offsets refer
     * to the unwrapped archive. Plain source opening preserves source offsets.
     *
     * @throws ArchiveFormatException if the format is unrecognized, source ranges are invalid, or gzip framing/integrity is invalid.
     * @throws ArchiveUnsupportedException if gzip uses an unsupported feature or the recognized format has no container reader.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed gzip DEFLATE data.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if gzip decoding exceeds the default policy.
     * @throws ArchiveSizeException if decoded gzip output cannot fit in a ByteArray.
     * @throws ArchiveException if the supplied source reports an IO or lifecycle failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(source: RandomAccessSource): ArchiveReader = open(source, ArchiveLimits.DEFAULT)

    /**
     * Open with a policy enforced separately for gzip decoding, entry listing and
     * each read. Ownership transfers only on success, as in [open].
     *
     * @throws ArchiveFormatException if the format is unrecognized, source ranges are invalid, or gzip framing/integrity is invalid.
     * @throws ArchiveUnsupportedException if gzip uses an unsupported feature or the recognized format has no container reader.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed gzip DEFLATE data.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if gzip decoding exceeds [limits].
     * @throws ArchiveSizeException if decoded gzip output cannot fit in a ByteArray.
     * @throws ArchiveException if the supplied source reports an IO or lifecycle failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(source: RandomAccessSource, limits: ArchiveLimits): ArchiveReader =
        openSource(source, FormatRegistry.DEFAULT, null, options(null, limits), true)

    /**
     * Open with explicit filename decoding; ZIP Unicode metadata still takes precedence.
     *
     * @throws ArchiveFormatException if the format is unrecognized, source ranges are invalid, or gzip framing/integrity is invalid.
     * @throws ArchiveUnsupportedException if gzip uses an unsupported feature or the recognized format has no container reader.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed gzip DEFLATE data.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if gzip decoding exceeds the default policy.
     * @throws ArchiveSizeException if decoded gzip output cannot fit in a ByteArray.
     * @throws ArchiveException if the supplied source reports an IO or lifecycle failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder): ArchiveReader =
        open(source, nameDecoder, ArchiveLimits.DEFAULT)

    /**
     * Open with explicit filename decoding and a policy for gzip decoding, listing
     * and entry reads. These operations enforce the policy separately. Ownership
     * transfers only on success; close the returned reader to close [source].
     *
     * @throws ArchiveFormatException if the format is unrecognized, source ranges are invalid, or gzip framing/integrity is invalid.
     * @throws ArchiveUnsupportedException if gzip uses an unsupported feature or the recognized format has no container reader.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed gzip DEFLATE data.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if gzip decoding exceeds [limits].
     * @throws ArchiveSizeException if decoded gzip output cannot fit in a ByteArray.
     * @throws ArchiveException if the supplied source reports an IO or lifecycle failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder, limits: ArchiveLimits): ArchiveReader =
        openSource(source, FormatRegistry.DEFAULT, null, options(nameDecoder, limits), true)

    /**
     * Open using [formats] and [options]. A non-null [format] selects its identifier
     * directly and skips all probes and ZIP footer fallback. Otherwise the first
     * matching descriptor wins; no match falls back to the registered ZIP reader
     * when a valid ZIP footer is present. Failed opens retain caller ownership.
     * Built-in gzip unwraps at most once using the same registry and options inside.
     * Custom factories must enforce the supplied policy and own the source on success.
     *
     * @throws ArchiveFormatException if automatic detection finds no archive or built-in metadata is malformed.
     * @throws ArchiveUnsupportedException if the selected id is unregistered, has no reader, or needs an unsupported feature.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if wrapper decoding exceeds the policy.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed gzip DEFLATE data.
     * @throws ArchiveSizeException if decoded gzip output cannot fit in a ByteArray.
     * @throws ArchiveException for other detection, opening, gzip or source failures, including custom callback failures.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    @JvmOverloads
    public fun open(
        source: RandomAccessSource, formats: FormatRegistry, format: String? = null,
        options: ArchiveOpenOptions = ArchiveOpenOptions.DEFAULT,
    ): ArchiveReader = openSource(source, formats, format, options, true)

    /**
     * Open bytes through a registry, or select [format] explicitly without probing.
     * Plain arrays are borrowed until close; built-in gzip output is an independent
     * snapshot. Policies, single-wrapper behavior and failures follow the source overload.
     * @throws ArchiveException for detection, opening, gzip, policy or custom callback failures.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    @JvmOverloads
    public fun open(
        bytes: ByteArray, formats: FormatRegistry, format: String? = null,
        options: ArchiveOpenOptions = ArchiveOpenOptions.DEFAULT,
    ): ArchiveReader = openBytes(bytes, formats, format, options)

    /**
     * Select a default-registered format directly, without sniffing or footer fallback;
     * null uses automatic detection.
     * Ownership transfers only when opening succeeds.
     * @throws ArchiveException for an unknown id or an opening, gzip, source or policy failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    @JvmOverloads
    public fun open(
        source: RandomAccessSource, format: String?, options: ArchiveOpenOptions = ArchiveOpenOptions.DEFAULT,
    ): ArchiveReader = open(source, FormatRegistry.DEFAULT, format, options)

    /**
     * Open borrowed archive bytes as a default-registered format, or detect when null.
     * Built-in gzip takes an independent snapshot under [options].
     * @throws ArchiveException for an unknown id or an opening, gzip or policy failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    @JvmOverloads
    public fun open(
        bytes: ByteArray, format: String?, options: ArchiveOpenOptions = ArchiveOpenOptions.DEFAULT,
    ): ArchiveReader = open(bytes, FormatRegistry.DEFAULT, format, options)

    private fun options(names: ArchiveNameDecoder?, policy: ArchiveLimits): ArchiveOpenOptions =
        if (names == null && policy === ArchiveLimits.DEFAULT) ArchiveOpenOptions.DEFAULT
        else ArchiveOpenOptions { nameDecoder = names; limits = policy }

    private fun select(source: RandomAccessSource, formats: FormatRegistry, format: String?): FormatSpec {
        if (format != null) return formats.byName(format)
            ?: throw ArchiveUnsupportedException("no registered format '$format'", offset = 0)
        return formats.sniff(source)
            ?: if (ZipEndRecord.find(source) != null) formats.byId(ArchiveFormat.ZIP)
                ?: throw ArchiveUnsupportedException("no container reader for ZIP", offset = 0)
            else throw ArchiveFormatException("unrecognized archive format", 0)
    }

    private fun openBytes(
        bytes: ByteArray, formats: FormatRegistry, format: String?, options: ArchiveOpenOptions,
    ): ArchiveReader {
        val source = ByteArrayRandomAccessSource(bytes)
        val spec = select(source, formats, format)
        if (spec.builtin && spec.id == ArchiveFormat.GZIP) {
            val content = Gzip.decompress(bytes, options.limits)
            return openSource(ByteArrayRandomAccessSource(content), formats, null, options, false)
        }
        return spec.open(source, options)
    }

    private fun openSource(
        source: RandomAccessSource, formats: FormatRegistry, format: String?,
        options: ArchiveOpenOptions, unwrapGzip: Boolean,
    ): ArchiveReader {
        val spec = select(source, formats, format)
        if (spec.builtin && spec.id == ArchiveFormat.GZIP) {
            if (!unwrapGzip) throw ArchiveUnsupportedException("no container reader for nested GZIP", offset = 0)
            val content = Gzip.decompress(source.readFullyChecked(0, source.size), options.limits)
            return openSource(GzipContainerSource(content, source), formats, null, options, false)
        }
        return spec.open(source, options)
    }

}

/** Holds the compressed source until its snapshot's reader closes; failed opens retain caller ownership. */
private class GzipContainerSource(bytes: ByteArray, source: RandomAccessSource) : RandomAccessSource {
    private var original: RandomAccessSource? = source
    private var snapshot: ByteArrayRandomAccessSource? = ByteArrayRandomAccessSource(bytes)

    override val size: Long get() = data.size
    private val data: ByteArrayRandomAccessSource
        get() = snapshot ?: throw ArchiveClosedException("gzip container source is closed")

    @Throws(ArchiveException::class, IllegalArgumentException::class)
    override fun read(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int =
        data.read(offset, into, intoOffset, length)

    @Throws(ArchiveException::class)
    override fun close() {
        val closing = original ?: return
        original = null
        snapshot = null
        closing.close()
    }

    override fun toString(): String = "GzipContainerSource(snapshot=${snapshot ?: "closed"})"
}
