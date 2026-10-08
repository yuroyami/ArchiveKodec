package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.archive.ArchiveNameDecoder
import io.github.yuroyami.archivekodec.archive.ArchiveReader
import io.github.yuroyami.archivekodec.archive.Magic
import io.github.yuroyami.archivekodec.archive.RandomAccessSource
import io.github.yuroyami.archivekodec.archive.ar.ArReader
import io.github.yuroyami.archivekodec.archive.cpio.CpioReader
import io.github.yuroyami.archivekodec.archive.tar.TarReader
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import io.github.yuroyami.archivekodec.archive.zip.ZipEndRecord
import io.github.yuroyami.archivekodec.codec.Codec
import io.github.yuroyami.archivekodec.codec.CodecId
import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import kotlin.jvm.JvmStatic

/**
 * Top-level entry point for the library. Use it to detect an archive format, get a
 * codec by id, or open a seekable source as a container reader.
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
    public fun sniff(header: ByteArray): ArchiveFormat = Magic.sniff(header)

    /**
     * Detect the format of a seekable source (reads up to the first 512 bytes).
     *
     * @throws ArchiveFormatException if the source reports an invalid size, premature EOF or invalid read count.
     * @throws ArchiveException if the supplied source reports an IO or lifecycle failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun sniff(source: RandomAccessSource): ArchiveFormat = Magic.sniff(source)

    /** The codec for [id], or null if not yet implemented. */
    @JvmStatic
    public fun codec(id: CodecId): Codec? = Codecs.byId(id)

    /** Codec ids available right now. */
    @JvmStatic
    public val codecs: List<CodecId> get() = Codecs.implemented

    /** Identity codec: returns the input bytes unchanged. */
    @JvmStatic
    public val store: Codec get() = Codecs.STORE
    /** Raw DEFLATE codec, without gzip or zlib framing. */
    @JvmStatic
    public val deflate: Codec get() = Codecs.DEFLATE
    /** Gzip encoder and decoder; decoding also accepts one zlib stream. */
    @JvmStatic
    public val gzip: Codec get() = Codecs.GZIP
    /** Zlib codec with RFC 1950 framing and Adler-32 integrity checks. */
    @JvmStatic
    public val zlib: Codec get() = Codecs.ZLIB

    /**
     * Open a container reader for a seekable [source], dispatching on the sniffed
     * format.
     * An unknown header falls back to a bounded ZIP footer search, allowing
     * prepended launchers. Known header formats retain priority over that footer.
     * Ownership transfers to the returned reader. If detection or opening
     * throws, the caller still owns [source] and must close it.
     *
     * @throws ArchiveFormatException if the format is unrecognized or source ranges are invalid.
     * @throws ArchiveUnsupportedException if the recognized format has no container reader.
     * @throws ArchiveException if the supplied source reports an IO or lifecycle failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(source: RandomAccessSource): ArchiveReader = open(source, ArchiveLimits.DEFAULT)

    /**
     * Open with a policy for entry listing and the default limits of each read.
     *
     * @throws ArchiveFormatException if the format is unrecognized or source ranges are invalid.
     * @throws ArchiveUnsupportedException if the recognized format has no container reader.
     * @throws ArchiveException if the supplied source reports an IO or lifecycle failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(source: RandomAccessSource, limits: ArchiveLimits): ArchiveReader = when (val format = containerFormat(source)) {
        ArchiveFormat.TAR -> TarReader(source, limits)
        ArchiveFormat.ZIP -> ZipReader(source, limits)
        ArchiveFormat.AR -> ArReader(source, limits)
        ArchiveFormat.CPIO -> CpioReader(source, limits)
        ArchiveFormat.UNKNOWN -> throw ArchiveFormatException("unrecognized archive format", 0)
        else -> throw ArchiveUnsupportedException(
            "no container reader for $format", offset = 0,
        )
    }

    /**
     * Open with explicit filename decoding; ZIP Unicode metadata still takes precedence.
     *
     * @throws ArchiveFormatException if the format is unrecognized or source ranges are invalid.
     * @throws ArchiveUnsupportedException if the recognized format has no container reader.
     * @throws ArchiveException if the supplied source reports an IO or lifecycle failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder): ArchiveReader =
        open(source, nameDecoder, ArchiveLimits.DEFAULT)

    /**
     * Open with explicit filename decoding and a policy for listing and entry reads.
     *
     * @throws ArchiveFormatException if the format is unrecognized or source ranges are invalid.
     * @throws ArchiveUnsupportedException if the recognized format has no container reader.
     * @throws ArchiveException if the supplied source reports an IO or lifecycle failure.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun open(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder, limits: ArchiveLimits): ArchiveReader = when (val format = containerFormat(source)) {
        ArchiveFormat.TAR -> TarReader(source, nameDecoder, limits)
        ArchiveFormat.ZIP -> ZipReader(source, nameDecoder, limits)
        ArchiveFormat.AR -> ArReader(source, nameDecoder, limits)
        ArchiveFormat.CPIO -> CpioReader(source, nameDecoder, limits)
        ArchiveFormat.UNKNOWN -> throw ArchiveFormatException("unrecognized archive format", 0)
        else -> throw ArchiveUnsupportedException("no container reader for $format", offset = 0)
    }

    private fun containerFormat(source: RandomAccessSource): ArchiveFormat {
        val header = sniff(source)
        return if (header == ArchiveFormat.UNKNOWN && ZipEndRecord.find(source) != null) ArchiveFormat.ZIP else header
    }
}
