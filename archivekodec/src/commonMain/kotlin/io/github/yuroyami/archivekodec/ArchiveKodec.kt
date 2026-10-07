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

/**
 * Top-level entry point for the library. Use it to detect an archive format, get a
 * codec by id, or open a seekable source as a container reader.
 *
 * Pure `commonMain`, `kotlin-stdlib` only. The kotlinx-io stream and file adapters
 * live in the separate `:archivekodec-io` artifact.
 */
object ArchiveKodec {

    const val VERSION: String = "0.1.0"

    /** Detect the format of a header buffer. */
    fun sniff(header: ByteArray): ArchiveFormat = Magic.sniff(header)

    /** Detect the format of a seekable source (reads up to the first 512 bytes). */
    @Throws(ArchiveException::class)
    fun sniff(source: RandomAccessSource): ArchiveFormat = Magic.sniff(source)

    /** The codec for [id], or null if not yet implemented. */
    fun codec(id: CodecId): Codec? = Codecs.byId(id)

    /** Codec ids available right now. */
    val codecs: List<CodecId> get() = Codecs.implemented

    val store: Codec get() = Codecs.STORE
    val deflate: Codec get() = Codecs.DEFLATE
    val gzip: Codec get() = Codecs.GZIP
    val zlib: Codec get() = Codecs.ZLIB

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
    fun open(source: RandomAccessSource): ArchiveReader = when (val format = containerFormat(source)) {
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
    fun open(source: RandomAccessSource, nameDecoder: ArchiveNameDecoder): ArchiveReader = when (val format = containerFormat(source)) {
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
