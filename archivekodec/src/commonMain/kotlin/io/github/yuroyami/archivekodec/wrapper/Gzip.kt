package io.github.yuroyami.archivekodec.wrapper

import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.deflate.Deflate
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.codec.deflate.InflateError
import io.github.yuroyami.archivekodec.codec.deflate.InflateException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * gzip (RFC 1952) framing over [Deflate] / [Inflate], with the CRC-32 + ISIZE
 * trailer. [decompress] also transparently accepts a bare zlib (RFC 1950) stream
 * (some servers label a zlib stream `Content-Encoding: deflate`); that fallback is
 * a small, clearly-marked extension.
 */
object Gzip {

    // gzip header flag bits.
    private const val FHCRC = 0x02
    private const val FEXTRA = 0x04
    private const val FNAME = 0x08
    private const val FCOMMENT = 0x10
    private const val FRESERVED = 0xe0

    private const val MAGIC0 = 0x1f
    private const val MAGIC1 = 0x8b

    /** Conservative default output cap (callers should pass their own from `ArchiveLimits`). */
    const val DEFAULT_MAXIMUM_SIZE: Long = 4L * 1024 * 1024

    /** Compress [data] into a complete gzip member. */
    fun compress(data: ByteArray): ByteArray {
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

    /**
     * Decompress a gzip (or bare zlib) stream and return the inflated bytes.
     *
     * @throws InflateException [InflateError.INVALID_GZIP_HEADER] if no valid header,
     *   [InflateError.INFLATED_DATA_TOO_LARGE] if the cap is hit, or a DEFLATE-level error.
     */
    fun decompress(input: ByteArray, maximumSize: Long = DEFAULT_MAXIMUM_SIZE): ByteArray {
        require(maximumSize > 0) { "maximumSize must be > 0" }

        val headerLen = headerLength(input)
        if (headerLen < 0) throw InflateException(InflateError.INVALID_GZIP_HEADER)

        // The gzip CRC32+ISIZE trailer and zlib Adler-32 trailer are ignored. Puff
        // stops at the final DEFLATE block and never reads them.
        val payload = input.copyOfRange(headerLen, input.size)
        val out = Inflate.inflate(payload)

        if (out.size.toLong() > maximumSize) {
            throw InflateException(InflateError.INFLATED_DATA_TOO_LARGE)
        }
        return out
    }

    private fun writeLe32(out: ByteArrayBuilder, v: Long) {
        out.append((v and 0xFF).toByte())
        out.append(((v ushr 8) and 0xFF).toByte())
        out.append(((v ushr 16) and 0xFF).toByte())
        out.append(((v ushr 24) and 0xFF).toByte())
    }

    private fun headerLength(input: ByteArray): Int {
        val gz = gzipHeader(input)
        if (gz >= 0) return gz
        return zlibHeader(input)
    }

    private fun gzipHeader(input: ByteArray): Int {
        if (input.size < 10) return -1
        fun u(i: Int): Int = input[i].toInt() and 0xff

        if (u(0) != MAGIC0 || u(1) != MAGIC1) return -1
        val method = u(2)
        val flags = u(3)
        if (method != 8 || (flags and FRESERVED) != 0) return -1

        var pos = 10
        fun remaining(): Int = input.size - pos

        if (flags and FEXTRA != 0) {
            if (remaining() < 2) return -1
            val extraLen = (u(pos + 1) shl 8) or u(pos)
            if (remaining() < extraLen + 2) return -1
            pos += extraLen + 2
        }
        if (flags and FNAME != 0) {
            if (remaining() == 0) return -1
            while (u(pos) != 0) { pos++; if (remaining() == 0) return -1 }
            pos++
        }
        if (flags and FCOMMENT != 0) {
            if (remaining() == 0) return -1
            while (u(pos) != 0) { pos++; if (remaining() == 0) return -1 }
            pos++
        }
        if (flags and FHCRC != 0) {
            if (remaining() < 2) return -1
            pos += 2
        }
        return pos
    }

    private fun zlibHeader(input: ByteArray): Int {
        if (input.size < 2) return -1
        val cmf = input[0].toInt() and 0xff
        val flg = input[1].toInt() and 0xff
        val method = cmf and 0x0f
        val cinfo = (cmf ushr 4) and 0x0f
        if (method != 8 || cinfo > 7) return -1
        if ((flg and 0x20) != 0) return -1                 // FDICT set: unsupported
        if (((cmf shl 8) or flg) % 31 != 0) return -1
        return 2
    }
}
