package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ExperimentalArchiveImplementation
import io.github.yuroyami.archivekodec.security.ArchiveLimits

/**
 * A single compression codec: a reversible byte transform. Codecs take a whole array;
 * [decompress] can return a whole array or stream its output to a [ByteSink]. [decompress] takes an
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
     * [io.github.yuroyami.archivekodec.codec.lz4.Lz4Options], bzip2 takes
     * [io.github.yuroyami.archivekodec.codec.bzip2.Bzip2Options], LZW takes
     * [io.github.yuroyami.archivekodec.codec.lzw.LzwOptions] and PackBits takes
     * [io.github.yuroyami.archivekodec.codec.packbits.PackBitsOptions]. The default rejects all options.
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
     * Decompress [data], writing the output to [sink] in chunks rather than returning it,
     * and return the number of bytes written. [limits] are checked before every write, so
     * the sink never receives more than they allow; raise
     * [ArchiveLimits.maxUncompressedBytes] for outputs above its 2 GiB default.
     *
     * DEFLATE, Deflate64, zlib and gzip stream, holding at most about 1 MiB of output at a time, so
     * the output may be larger than memory. Other codecs decode fully in memory first and
     * then write the result, which is also this default.
     *
     * @throws ArchiveException for the framing, decoding, integrity and limit failures documented by [decompress],
     *   and for failures the sink reports. Output written before a failure stays written.
     */
    @Throws(ArchiveException::class)
    public fun decompress(data: ByteArray, sink: ByteSink, limits: ArchiveLimits): Long {
        val out = decompress(data, limits)
        if (out.isNotEmpty()) sink.write(out, 0, out.size)
        return out.size.toLong()
    }

    /**
     * Decompress the stream read from [source], writing the output to [sink], and return the
     * number of bytes written. [limits] are checked before every write; the ratio is measured
     * against the compressed bytes read so far.
     *
     * DEFLATE, zlib, gzip, bzip2, xz and zstd read their input as they need it and write their
     * output as it is produced, so neither side has to fit in memory: memory stays near a
     * window, a block or a dictionary. Other codecs read the whole input first
     * and decode it as [decompress] with a sink does, which is also this default.
     *
     * @throws ArchiveException for the failures documented by [decompress], and for failures
     *   the source or the sink reports. Output written before a failure stays written.
     */
    @Throws(ArchiveException::class)
    public fun decompress(source: ByteSource, sink: ByteSink, limits: ArchiveLimits): Long {
        val input = io.github.yuroyami.archivekodec.util.ByteArrayBuilder(64 * 1024)
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val n = source.read(chunk, 0, chunk.size)
            if (n < 0) break
            input.append(chunk, 0, n)
        }
        return decompress(input.takeByteArray(), sink, limits)
    }

    /**
     * Open a streaming encoder that writes its compressed output to [sink]; see
     * [EncoderSession]. DEFLATE, zlib, gzip, bzip2, xz, zstd, LZ4 and lzip encode in pieces as the input
     * arrives, so the input may be larger than memory. Other codecs keep the input and
     * compress it at [EncoderSession.finish], which is also this default.
     */
    public fun encoder(sink: ByteSink): EncoderSession = BufferingEncoderSession({ compress(it) }, sink)

    /**
     * Open a streaming encoder with encoder [options], as [compress] with options takes them.
     * @throws IllegalArgumentException if this codec does not accept [options].
     */
    @Throws(IllegalArgumentException::class)
    public fun encoder(sink: ByteSink, options: CodecOptions): EncoderSession {
        // Fail on unsuitable options now, not at finish.
        compress(ByteArray(0), options)
        return BufferingEncoderSession({ compress(it, options) }, sink)
    }

    /**
     * Decode with [ArchiveLimits.DEFAULT], including from Java and Swift.
     * @throws ArchiveException for the framing, decoding, integrity and limit failures documented by [decompress].
     */
    @Throws(ArchiveException::class)
    public fun decompress(data: ByteArray): ByteArray = decompress(data, ArchiveLimits.DEFAULT)
}
