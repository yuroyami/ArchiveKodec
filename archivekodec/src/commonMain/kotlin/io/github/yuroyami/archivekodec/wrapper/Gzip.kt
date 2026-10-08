package io.github.yuroyami.archivekodec.wrapper

import kotlin.jvm.JvmStatic

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.checkedArraySize
import io.github.yuroyami.archivekodec.util.le16
import io.github.yuroyami.archivekodec.util.le32
import io.github.yuroyami.archivekodec.util.readU16Le
import io.github.yuroyami.archivekodec.util.readU32Le

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
    private const val FTEXT = 0x01
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

    /**
     * Compress [data] into a complete gzip member with [GzipHeader.DEFAULT].
     * The default ten header bytes remain `1f 8b 08 00 00 00 00 00 00 ff`.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun compress(data: ByteArray): ByteArray = compress(data, GzipHeader.DEFAULT)

    /**
     * Compress [data] with explicit RFC 1952 metadata. Names/comments are written
     * as Latin-1, with NUL terminators; [GzipHeader] validates their representation.
     * Emits optional extra bytes and calculates FHCRC when requested. Metadata
     * does not change the DEFLATE algorithm or perform any filesystem operation.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun compress(data: ByteArray, header: GzipHeader): ByteArray {
        val extra = header.storedExtra
        val headerSize = 10L + (extra?.let { 2L + it.size } ?: 0L) +
            (header.name?.let { it.length + 1L } ?: 0L) + (header.comment?.let { it.length + 1L } ?: 0L) +
            (if (header.hasHeaderCrc) 2L else 0L)
        checkedArraySize(headerSize + 8)
        val compressed = Deflate.encode(data)
        val out = ByteArrayBuilder(checkedArraySize(headerSize + compressed.size + 8))
        val flags = (if (header.isText) FTEXT else 0) or (if (header.hasHeaderCrc) FHCRC else 0) or
            (if (extra != null) FEXTRA else 0) or (if (header.name != null) FNAME else 0) or
            (if (header.comment != null) FCOMMENT else 0)
        out.append(MAGIC0.toByte())
        out.append(MAGIC1.toByte())
        out.append(8.toByte())              // CM = DEFLATE
        out.append(flags.toByte())
        out.le32(header.mtimeEpochSeconds)
        out.append(header.extraFlags.toByte())
        out.append(header.os.toByte())
        if (extra != null) {
            out.le16(extra.size)
            out.append(extra)
        }
        fun text(value: String?) {
            if (value != null) {
                for (char in value) out.append(char.code.toByte())
                out.append(0)
            }
        }
        text(header.name)
        text(header.comment)
        if (header.hasHeaderCrc) {
            val sum = out.checksum { bytes, size -> Crc32().apply { update(bytes, 0, size) }.value() }
            out.le16((sum and 0xffff).toInt())
        }
        out.append(compressed)
        out.le32(Crc32.of(data))                       // CRC-32 of uncompressed data
        out.le32(data.size.toLong() and 0xFFFFFFFFL)   // ISIZE mod 2^32
        return out.takeByteArray()
    }

    /**
     * Inspect the first member's header without decoding its payload. Returns null
     * if the first two bytes are not gzip magic (including input shorter than two
     * bytes). Once magic matches, validates all header fields and optional FHCRC.
     * Decodes names/comments as Latin-1 and preserves absent versus empty fields.
     * The result owns its extra bytes and remains independent of [input].
     *
     * A complete header alone is sufficient. This does not validate DEFLATE data,
     * the trailer or later members; use [decompress] for those checks. It does not
     * accept the zlib fallback supported by [decompress].
     *
     * @throws ArchiveFormatException for a recognized but malformed/truncated gzip header or failed FHCRC.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun readHeader(input: ByteArray): GzipHeader? {
        if (input.size < 2 || u(input, 0) != MAGIC0 || u(input, 1) != MAGIC1) return null
        val metadata = GzipHeader.Builder()
        gzipHeader(input, 0, metadata)
        return metadata.build()
    }

    /**
     * Decode all gzip members with [ArchiveLimits.DEFAULT]. Also accepts one zlib stream.
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
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     * @throws io.github.yuroyami.archivekodec.ArchiveUnsupportedException if a zlib input requires a preset dictionary.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        if (input.size < 2 || u(input, 0) != MAGIC0 || u(input, 1) != MAGIC1) {
            return Zlib.decompress(input, limits)
        }
        // This is only a hint: padding may follow the last trailer, and ISIZE
        // covers one member modulo 2^32, not the combined output of all members.
        val sizeHint = if (input.size >= 18) readU32Le(input, input.size - 4) else null
        val output = Inflate.Output(limits, input.size, sizeHint = sizeHint)
        var position = 0
        do {
            val payload = gzipHeader(input, position)
            val outputStart = output.size
            val trailer = Inflate.inflateInto(input, payload, input.size, output)
            if (input.size - trailer < 8) throw ArchiveFormatException("gzip: truncated trailer", trailer.toLong())
            if (readU32Le(input, trailer) != output.checksum(Crc32(), outputStart)) {
                throw ArchiveFormatException("gzip: CRC-32 mismatch", trailer.toLong())
            }
            if (readU32Le(input, trailer + 4) != (output.size - outputStart).toLong()) {
                throw ArchiveFormatException("gzip: ISIZE mismatch", (trailer + 4).toLong())
            }
            position = trailer + 8
            while (position < input.size && input[position] == 0.toByte()) position++
        } while (position < input.size)
        return output.takeByteArray()
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

    private fun u(input: ByteArray, i: Int): Int = input[i].toInt() and 0xff

    private fun gzipHeader(input: ByteArray, start: Int, metadata: GzipHeader.Builder? = null): Int {
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
        if (metadata != null) {
            metadata.mtimeEpochSeconds = readU32Le(input, start + 4)
            metadata.os = u(input, start + 9)
            metadata.extraFlags = u(input, start + 8)
            metadata.isText = flags and FTEXT != 0
            metadata.hasHeaderCrc = flags and FHCRC != 0
        }
        position += 10
        if (flags and FEXTRA != 0) {
            need(2)
            val length = readU16Le(input, position)
            position += 2
            need(length)
            if (metadata != null) metadata.extra = input.copyOfRange(position, position + length)
            position += length
        }
        fun readString(): String? {
            val begin = position
            do {
                need(1)
            } while (input[position++] != 0.toByte())
            return if (metadata == null) null else buildString(position - begin - 1) {
                for (index in begin until position - 1) append(u(input, index).toChar())
            }
        }
        if (flags and FNAME != 0) {
            val name = readString()
            if (metadata != null) metadata.name = name
        }
        if (flags and FCOMMENT != 0) {
            val comment = readString()
            if (metadata != null) metadata.comment = comment
        }
        if (flags and FHCRC != 0) {
            need(2)
            val expected = readU16Le(input, position)
            val actual = Crc32().apply { update(input, start, position - start) }.value().toInt() and 0xffff
            if (actual != expected) throw ArchiveFormatException("gzip: header CRC-16 mismatch", position.toLong())
            position += 2
        }
        return position
    }
}
