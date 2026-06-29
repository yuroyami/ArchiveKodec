package io.github.yuroyami.kitearchive

import io.github.yuroyami.kitearchive.archive.ArchiveFormat
import io.github.yuroyami.kitearchive.archive.ArchiveReader
import io.github.yuroyami.kitearchive.archive.Magic
import io.github.yuroyami.kitearchive.archive.RandomAccessSource
import io.github.yuroyami.kitearchive.archive.tar.TarReader
import io.github.yuroyami.kitearchive.archive.zip.ZipReader
import io.github.yuroyami.kitearchive.codec.Codec
import io.github.yuroyami.kitearchive.codec.CodecId
import io.github.yuroyami.kitearchive.codec.Codecs

/**
 * Top-level entry point — the analogue of `PdfDocument` / `KiteTorrentEngine` /
 * KiteQR's `Qr`. Sniff a format, grab a codec by id, or open a seekable source as a
 * container reader.
 *
 * Pure `commonMain`, `kotlin-stdlib` only. The kotlinx-io stream/file adapters live
 * in the separate `:kitearchive-io` artifact.
 */
object KiteArchive {

    const val VERSION: String = "0.0.1-SNAPSHOT"

    /** Detect the format of a header buffer. */
    fun sniff(header: ByteArray): ArchiveFormat = Magic.sniff(header)

    /** Detect the format of a seekable source (reads up to the first 512 bytes). */
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
     *
     * @throws UnsupportedOperationException for a format with no reader yet.
     */
    fun open(source: RandomAccessSource): ArchiveReader = when (val format = sniff(source)) {
        ArchiveFormat.TAR -> TarReader(source)
        ArchiveFormat.ZIP -> ZipReader(source)
        else -> throw UnsupportedOperationException(
            "no container reader for $format yet — see PORTING_STATUS.md",
        )
    }
}
