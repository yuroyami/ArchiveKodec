package io.github.yuroyami.archivekodec.wrapper

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.checksum.Adler32
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

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

    /** Compress [data] into a zlib stream. */
    @Throws(ArchiveException::class)
    public fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(data.size / 2 + 16)
        // CMF = 0x78 (CM=8, CINFO=7 → 32 KiB window), FLG = 0x9C so that
        // (CMF*256 + FLG) % 31 == 0 with FLEVEL=2, FDICT=0.
        out.append(0x78.toByte())
        out.append(0x9C.toByte())
        out.append(Deflate.encode(data))
        val adler = Adler32.of(data)
        out.append(((adler ushr 24) and 0xFF).toByte())   // big-endian
        out.append(((adler ushr 16) and 0xFF).toByte())
        out.append(((adler ushr 8) and 0xFF).toByte())
        out.append((adler and 0xFF).toByte())
        return out.toByteArray()
    }

    /** Decode exactly one complete zlib stream with [ArchiveLimits.DEFAULT]. */
    @Throws(ArchiveException::class)
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
     */
    @Throws(ArchiveException::class)
    public fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        if (input.size < 2) throw ArchiveFormatException("zlib: truncated header", 0)
        val cmf = input[0].toInt() and 0xff
        val flg = input[1].toInt() and 0xff
        if (cmf and 0x0f != 8 || cmf ushr 4 > 7 || ((cmf shl 8) or flg) % 31 != 0) {
            throw ArchiveFormatException("zlib: invalid header", 0)
        }
        if (flg and 0x20 != 0) {
            if (input.size < 6) throw ArchiveFormatException("zlib: truncated dictionary ID", 2)
            var dictionary = 0L
            repeat(4) { dictionary = (dictionary shl 8) or (input[2 + it].toLong() and 0xff) }
            throw ArchiveUnsupportedException("zlib: preset dictionary ${dictionary.toString(16)} is required but unsupported", 2)
        }
        val output = Inflate.Output(limits, input.size)
        val trailer = Inflate.inflateInto(input, 2, input.size, output)
        if (input.size - trailer < 4) throw ArchiveFormatException("zlib: truncated Adler-32 trailer", trailer.toLong())
        var expected = 0L
        repeat(4) { expected = (expected shl 8) or (input[trailer + it].toLong() and 0xff) }
        if (expected != output.checksum(Adler32())) throw ArchiveFormatException("zlib: Adler-32 mismatch", trailer.toLong())
        if (input.size - trailer != 4) throw ArchiveFormatException("zlib: trailing bytes after stream", (trailer + 4).toLong())
        return output.toByteArray()
    }

    /** Compatibility overload. A zero cap accepts only empty output, as [ArchiveLimits] does. */
    @Deprecated(
        "Pass ArchiveLimits to configure the complete decompression policy",
        ReplaceWith("decompress(input, ArchiveLimits.DEFAULT.copy(maxUncompressedBytes = maximumSize))", "io.github.yuroyami.archivekodec.security.ArchiveLimits"),
    )
    @Throws(ArchiveException::class)
    public fun decompress(input: ByteArray, maximumSize: Long = ArchiveLimits.DEFAULT.maxUncompressedBytes): ByteArray =
        decompress(input, ArchiveLimits.DEFAULT.copy(maxUncompressedBytes = maximumSize))
}
