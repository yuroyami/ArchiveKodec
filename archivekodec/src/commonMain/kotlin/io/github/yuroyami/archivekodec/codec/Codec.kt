package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ExperimentalArchiveImplementation
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/**
 * A single compression codec: a reversible byte transform. Every codec is buffered,
 * so it takes a whole array and returns a whole array. [decompress] takes an
 * [ArchiveLimits], so the decompression-bomb guard is part of the contract rather
 * than an option a caller can forget. Custom engines must enforce limits during
 * decoding, keep [id] stable, and document their thread-safety. Register one with
 * `CodecRegistry.DEFAULT.with(custom, zipMethod = 93)` and pass that registry to
 * [io.github.yuroyami.archivekodec.archive.zip.ZipReader]. The ZIP reader retains
 * header, output-size and CRC validation; it cannot bound a custom engine that
 * ignores the decoding limits.
 *
 * Calling a codec is stable. Implementing one requires opting in to
 * [ExperimentalArchiveImplementation], because members may be added to this interface.
 */
@SubclassOptInRequired(ExperimentalArchiveImplementation::class)
public interface Codec {
    /** Which codec this is. */
    public val id: CodecId

    /**
     * Compress [data] fully in memory.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    public fun compress(data: ByteArray): ByteArray

    /**
     * Compress [data] with encoder [options]. Built-in DEFLATE, zlib and gzip take
     * [io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions] and LZ4 takes
     * [io.github.yuroyami.archivekodec.codec.lz4.Lz4Options]. The default rejects all options.
     *
     * @throws IllegalArgumentException if this codec does not take [options]' class or a setting.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class, IllegalArgumentException::class)
    public fun compress(data: ByteArray, options: CodecOptions): ByteArray =
        throw IllegalArgumentException("$id takes no compression options, not ${options::class.simpleName}")

    /**
     * Decompress [data] fully in memory, enforcing [limits].
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveFormatException for malformed framing, inconsistent sizes or failed integrity checks.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed or truncated DEFLATE data.
     * @throws io.github.yuroyami.archivekodec.ArchiveUnsupportedException if decoding needs an unsupported format feature or dictionary.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if an output or compression-ratio limit is exceeded.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    public fun decompress(data: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray

    /**
     * Decode with [ArchiveLimits.DEFAULT], including from Java and Swift.
     * @throws ArchiveException for the framing, decoding, integrity and limit failures documented by [decompress].
     */
    @Throws(ArchiveException::class)
    public fun decompress(data: ByteArray): ByteArray = decompress(data, ArchiveLimits.DEFAULT)
}
