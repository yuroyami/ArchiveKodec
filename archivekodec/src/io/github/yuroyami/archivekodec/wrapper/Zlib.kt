package io.github.yuroyami.archivekodec.wrapper

import io.github.yuroyami.archivekodec.codec.ByteSink
import kotlin.jvm.JvmStatic

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.checksum.Adler32
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.be32
import io.github.yuroyami.archivekodec.util.readU32Be

/**
 * zlib (RFC 1950) framing over [Deflate] / [Inflate]: a 2-byte CMF/FLG header and
 * a big-endian Adler-32 trailer. This is the wrapper PNG `IDAT` and PDF `FlateDecode`
 * use.
 *
 * This is a supported low-level public API for callers that specifically need
 * RFC 1950 framing and its compatibility overloads. Use
 * [io.github.yuroyami.archivekodec.codec.Codecs.ZLIB] for the shared
 * [io.github.yuroyami.archivekodec.codec.Codec] interface.
 */
public object Zlib {

    /**
     * Compress [data] into a zlib stream.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun compress(data: ByteArray): ByteArray = compress(data, DeflateOptions.DEFAULT)

    /**
     * Compress [data] into a zlib stream with [options]. The header records the window,
     * a level hint and, with [DeflateOptions.dictionary], the FDICT flag and the
     * dictionary's Adler-32, which [decompress] needs the same dictionary to match.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun compress(data: ByteArray, options: DeflateOptions): ByteArray {
        val out = ByteArrayBuilder(data.size / 2 + 16)
        val dictionary = options.dictionary
        // CMF: CM=8, CINFO = window bits - 8. FLG: FLEVEL from the level, FDICT, and FCHECK
        // so that (CMF*256 + FLG) % 31 == 0. Level 6 with a 32 KiB window gives 78 9C.
        val cmf = 0x08 or ((options.windowBits - 8) shl 4)
        val level = when (options.level) {
            0, 1 -> 0
            in 2..5 -> 1
            6 -> 2
            else -> 3
        }
        var flg = (level shl 6) or (if (dictionary != null) 0x20 else 0)
        flg += 31 - (cmf * 256 + flg) % 31
        out.append(cmf.toByte())
        out.append(flg.toByte())
        if (dictionary != null) out.be32(Adler32.of(dictionary))
        out.append(Deflate.encode(data, options))
        out.be32(Adler32.of(data))
        return out.toByteArray()
    }

    /**
     * Decode exactly one complete zlib stream with [ArchiveLimits.DEFAULT].
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveFormatException for malformed framing, inconsistent sizes or failed integrity checks.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed or truncated DEFLATE data.
     * @throws io.github.yuroyami.archivekodec.ArchiveUnsupportedException if decoding needs an unsupported format feature or dictionary.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if an output or compression-ratio limit is exceeded.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun decompress(input: ByteArray): ByteArray = decompress(input, ArchiveLimits.DEFAULT)

    /**
     * Decode one zlib stream and validate Adler-32 at its consumed-stream boundary.
     * Trailing bytes are rejected. Preset dictionaries are unsupported.
     * Output and whole-input ratio budgets are checked before allocating or copying.
     *
     * @throws ArchiveFormatException for an invalid header, trailer or checksum.
     * @throws ArchiveUnsupportedException if a preset dictionary is required.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for invalid DEFLATE.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException
     *   if the output or whole-input ratio exceeds [limits].
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray = decompress(input, limits, null)

    /**
     * Decode one zlib stream that may need a preset [dictionary] (FDICT). The dictionary's
     * Adler-32 must match the stream's DICTID. Without a dictionary, an FDICT stream fails.
     *
     * @throws ArchiveFormatException for an invalid header, trailer or checksum, or a dictionary whose Adler-32 does not match.
     * @throws ArchiveUnsupportedException if the stream needs a dictionary and none was given.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for invalid DEFLATE.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException
     *   if the output or whole-input ratio exceeds [limits].
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun decompress(input: ByteArray, limits: ArchiveLimits, dictionary: ByteArray?): ByteArray {
        val output = Inflate.Output(limits, input.size)
        decode(input, dictionary, output)
        return output.takeByteArray()
    }

    /** Stream the output to [sink]; return the byte count. */
    internal fun decompressTo(input: ByteArray, limits: ArchiveLimits, sink: ByteSink): Long {
        val output = Inflate.Output(limits, input.size, sink = sink)
        decode(input, null, output)
        return output.finish()
    }

    private fun decode(input: ByteArray, dictionary: ByteArray?, output: Inflate.Output) {
        if (input.size < 2) throw ArchiveFormatException("zlib: truncated header", 0)
        if (!hasZlibHeader(input)) {
            throw ArchiveFormatException("zlib: invalid header", 0)
        }
        var start = 2
        if (input[1].toInt() and 0x20 != 0) {
            if (input.size < 6) throw ArchiveFormatException("zlib: truncated dictionary ID", 2)
            val id = readU32Be(input, 2)
            if (dictionary == null) {
                throw ArchiveUnsupportedException("zlib: stream needs preset dictionary ${id.toString(16)}; pass it to decompress", 2)
            }
            if (Adler32.of(dictionary) != id) throw ArchiveFormatException("zlib: dictionary does not match DICTID ${id.toString(16)}", 2)
            output.preload(dictionary)
            start = 6
        }
        output.startChecksum(Adler32())
        val trailer = Inflate.inflateInto(input, start, input.size, output)
        if (input.size - trailer < 4) throw ArchiveFormatException("zlib: truncated Adler-32 trailer", trailer.toLong())
        val expected = readU32Be(input, trailer)
        if (expected != output.checksumValue()) throw ArchiveFormatException("zlib: Adler-32 mismatch", trailer.toLong())
        if (input.size - trailer != 4) throw ArchiveFormatException("zlib: trailing bytes after stream", (trailer + 4).toLong())
    }

    /**
     * Compatibility overload. A zero cap accepts only empty output, as [ArchiveLimits] does.
     * @throws IllegalArgumentException if [maximumSize] is negative.
     * @throws io.github.yuroyami.archivekodec.ArchiveFormatException for malformed framing, inconsistent sizes or failed integrity checks.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for malformed or truncated DEFLATE data.
     * @throws io.github.yuroyami.archivekodec.ArchiveUnsupportedException if decoding needs an unsupported format feature or dictionary.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException if an output or compression-ratio limit is exceeded.
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Deprecated(
        "Pass ArchiveLimits to configure the complete decompression policy",
        ReplaceWith("decompress(input, ArchiveLimits.DEFAULT.copy { maxUncompressedBytes = maximumSize })", "io.github.yuroyami.archivekodec.security.ArchiveLimits"),
    )
    @Throws(ArchiveException::class, IllegalArgumentException::class)
    @JvmStatic
    public fun decompress(input: ByteArray, maximumSize: Long = ArchiveLimits.DEFAULT.maxUncompressedBytes): ByteArray =
        decompress(input, ArchiveLimits.DEFAULT.copy { maxUncompressedBytes = maximumSize })
}
