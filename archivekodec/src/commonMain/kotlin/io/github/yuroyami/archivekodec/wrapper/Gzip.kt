package io.github.yuroyami.archivekodec.wrapper

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * gzip (RFC 1952) framing over [Deflate] / [Inflate], with the CRC-32 + ISIZE
 * trailer. [decompress] also transparently accepts a bare zlib (RFC 1950) stream
 * (some servers label a zlib stream `Content-Encoding: deflate`); that fallback is
 * a small, clearly-marked extension.
 *
 * This is a supported low-level public API for format-specific gzip operations,
 * including its compatibility overloads. The registered
 * [io.github.yuroyami.archivekodec.codec.Codecs.GZIP] codec offers the shared
 * [io.github.yuroyami.archivekodec.codec.Codec] interface.
 */
public object Gzip {

    // gzip header flag bits.
    private const val FHCRC = 0x02
    private const val FEXTRA = 0x04
    private const val FNAME = 0x08
    private const val FCOMMENT = 0x10
    private const val FRESERVED = 0xe0

    private const val MAGIC0 = 0x1f
    private const val MAGIC1 = 0x8b

    /** Legacy explicit cap; the active default is [ArchiveLimits.DEFAULT]. */
    @Deprecated("Use ArchiveLimits.DEFAULT or an explicit ArchiveLimits policy")
    public const val DEFAULT_MAXIMUM_SIZE: Long = 4L * 1024 * 1024

    /** Compress [data] into a complete gzip member. */
    @Throws(ArchiveException::class)
    public fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(data.size / 2 + 32)
        out.append(MAGIC0.toByte())
        out.append(MAGIC1.toByte())
        out.append(8.toByte())              // CM = DEFLATE
        out.append(0.toByte())              // FLG = 0 (no optional fields)
        repeat(4) { out.append(0.toByte()) } // MTIME = 0
        out.append(0.toByte())              // XFL
        out.append(255.toByte())            // OS = unknown
        out.append(Deflate.encode(data))
        writeLe32(out, Crc32.of(data))                       // CRC-32 of uncompressed data
        writeLe32(out, data.size.toLong() and 0xFFFFFFFFL)   // ISIZE mod 2^32
        return out.toByteArray()
    }

    /** Decode all gzip members with [ArchiveLimits.DEFAULT]. Also accepts one zlib stream. */
    @Throws(ArchiveException::class)
    public fun decompress(input: ByteArray): ByteArray = decompress(input, ArchiveLimits.DEFAULT)

    /**
     * Decode and concatenate all gzip members under one cumulative [limits] policy.
     * Validates header CRC-16 when present and every member's CRC-32 and ISIZE.
     * Zero padding between or after members is accepted as an interoperability
     * extension. Nonzero trailing bytes or an incomplete later member are errors.
     * A bare zlib input delegates to [Zlib.decompress], including its integrity checks.
     *
     * @throws ArchiveFormatException for an invalid header, trailer or checksum.
     * @throws io.github.yuroyami.archivekodec.codec.deflate.InflateException for invalid DEFLATE.
     * @throws io.github.yuroyami.archivekodec.security.DecompressionBombException
     *   before output growth if the output or whole-input ratio exceeds [limits].
     */
    @Throws(ArchiveException::class)
    public fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        if (input.size < 2 || u(input, 0) != MAGIC0 || u(input, 1) != MAGIC1) {
            return Zlib.decompress(input, limits)
        }
        val output = Inflate.Output(limits, input.size)
        var position = 0
        do {
            val payload = gzipHeader(input, position)
            val outputStart = output.size
            val trailer = Inflate.inflateInto(input, payload, input.size, output)
            if (input.size - trailer < 8) throw ArchiveFormatException("gzip: truncated trailer", trailer.toLong())
            if (le32(input, trailer) != output.checksum(Crc32(), outputStart)) {
                throw ArchiveFormatException("gzip: CRC-32 mismatch", trailer.toLong())
            }
            if (le32(input, trailer + 4) != (output.size - outputStart).toLong()) {
                throw ArchiveFormatException("gzip: ISIZE mismatch", (trailer + 4).toLong())
            }
            position = trailer + 8
            while (position < input.size && input[position] == 0.toByte()) position++
        } while (position < input.size)
        return output.toByteArray()
    }

    /**
     * Compatibility overload. A zero cap accepts only empty output, as [ArchiveLimits] does.
     * @throws IllegalArgumentException if [maximumSize] is negative.
     */
    @Deprecated(
        "Pass ArchiveLimits to configure the complete decompression policy",
        ReplaceWith("decompress(input, ArchiveLimits.DEFAULT.copy { maxUncompressedBytes = maximumSize })", "io.github.yuroyami.archivekodec.security.ArchiveLimits"),
    )
    @Throws(ArchiveException::class, IllegalArgumentException::class)
    public fun decompress(input: ByteArray, maximumSize: Long = ArchiveLimits.DEFAULT.maxUncompressedBytes): ByteArray =
        decompress(input, ArchiveLimits.DEFAULT.copy { maxUncompressedBytes = maximumSize })

    private fun writeLe32(out: ByteArrayBuilder, v: Long) {
        out.append((v and 0xFF).toByte())
        out.append(((v ushr 8) and 0xFF).toByte())
        out.append(((v ushr 16) and 0xFF).toByte())
        out.append(((v ushr 24) and 0xFF).toByte())
    }

    private fun u(input: ByteArray, i: Int): Int = input[i].toInt() and 0xff

    private fun le32(input: ByteArray, offset: Int): Long =
        u(input, offset).toLong() or (u(input, offset + 1).toLong() shl 8) or
            (u(input, offset + 2).toLong() shl 16) or (u(input, offset + 3).toLong() shl 24)

    private fun gzipHeader(input: ByteArray, start: Int): Int {
        var position = start
        fun need(count: Int) {
            if (count > input.size - position) throw ArchiveFormatException("gzip: truncated header", position.toLong())
        }
        need(10)
        if (u(input, start) != MAGIC0 || u(input, start + 1) != MAGIC1) {
            throw ArchiveFormatException("gzip: expected member header", start.toLong())
        }
        val flags = u(input, start + 3)
        if (u(input, start + 2) != 8 || flags and FRESERVED != 0) {
            throw ArchiveFormatException("gzip: invalid method or reserved flags", (start + 2).toLong())
        }
        position += 10
        if (flags and FEXTRA != 0) {
            need(2)
            val length = u(input, position) or (u(input, position + 1) shl 8)
            position += 2
            need(length)
            position += length
        }
        fun skipString() {
            do {
                need(1)
            } while (input[position++] != 0.toByte())
        }
        if (flags and FNAME != 0) skipString()
        if (flags and FCOMMENT != 0) skipString()
        if (flags and FHCRC != 0) {
            need(2)
            val expected = u(input, position) or (u(input, position + 1) shl 8)
            val actual = Crc32().apply { update(input, start, position - start) }.value().toInt() and 0xffff
            if (actual != expected) throw ArchiveFormatException("gzip: header CRC-16 mismatch", position.toLong())
            position += 2
        }
        return position
    }
}
