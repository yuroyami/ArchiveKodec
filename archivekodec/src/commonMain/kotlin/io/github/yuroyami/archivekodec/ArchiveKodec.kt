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
import kotlin.jvm.JvmStatic

/**
 * Top-level entry point for the library. Use it to detect an archive format, get a
 * codec by id, or open a seekable source as a container reader.
 *
 * Pure `commonMain`, `kotlin-stdlib` only. The kotlinx-io stream and file adapters
 * live in the separate `:archivekodec-io` artifact.
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
    public fun sniff(header: ByteArray): ArchiveFormat = Magic.sniff(header)

    /** Detect the format of a seekable source (reads up to the first 512 bytes). */
    @Throws(ArchiveException::class)
    public fun sniff(source: RandomAccessSource): ArchiveFormat = Magic.sniff(source)

    /** The codec for [id], or null if not yet implemented. */
    public fun codec(id: CodecId): Codec? = Codecs.byId(id)

    /** Codec ids available right now. */
    public val codecs: List<CodecId> get() = Codecs.implemented

    public val store: Codec get() = Codecs.STORE
    public val deflate: Codec get() = Codecs.DEFLATE
    public val gzip: Codec get() = Codecs.GZIP
    public val zlib: Codec get() = Codecs.ZLIB

    /**
     * Open a container reader for a seekable [source], dispatching on the sniffed
     * format.
     * An unknown header falls back to a bounded ZIP footer search, allowing
     * prepended launchers. Known header formats retain priority over that footer.
     * Ownership transfers to the returned reader. If detection or opening
     * throws, the caller still owns [source] and must close it.
     *
     * @throws ArchiveUnsupportedException for a format with no reader yet.
     */
    @Throws(ArchiveException::class)
    public fun open(source: RandomAccessSource): ArchiveReader = when (val format = containerFormat(source)) {
        ArchiveFormat.TAR -> TarReader(source)
        ArchiveFormat.ZIP -> ZipReader(source)
        ArchiveFormat.AR -> ArReader(source)
        ArchiveFormat.CPIO -> CpioReader(source)
        ArchiveFormat.UNKNOWN -> throw ArchiveFormatException("unrecognized archive format", 0)
        else -> throw ArchiveUnsupportedException(
            "no container reader for $format", offset = 0,
        )
    }

    /** Open with explicit filename decoding; ZIP Unicode metadata still takes precedence. */
    @Throws(ArchiveException::class)
    public fun open(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder): ArchiveReader = when (val format = containerFormat(source)) {
        ArchiveFormat.TAR -> TarReader(source, nameDecoder)
        ArchiveFormat.ZIP -> ZipReader(source, nameDecoder)
        ArchiveFormat.AR -> ArReader(source, nameDecoder)
        ArchiveFormat.CPIO -> CpioReader(source, nameDecoder)
        ArchiveFormat.UNKNOWN -> throw ArchiveFormatException("unrecognized archive format", 0)
        else -> throw ArchiveUnsupportedException("no container reader for $format", offset = 0)
    }

    private fun containerFormat(source: RandomAccessSource): ArchiveFormat {
        val header = sniff(source)
        return if (header == ArchiveFormat.UNKNOWN && ZipEndRecord.find(source) != null) ArchiveFormat.ZIP else header
    }
}
