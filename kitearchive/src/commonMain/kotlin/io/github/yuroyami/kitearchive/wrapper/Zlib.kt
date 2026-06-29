package io.github.yuroyami.kitearchive.wrapper

import io.github.yuroyami.kitearchive.checksum.Adler32
import io.github.yuroyami.kitearchive.codec.deflate.Deflate
import io.github.yuroyami.kitearchive.codec.deflate.Inflate
import io.github.yuroyami.kitearchive.codec.deflate.InflateError
import io.github.yuroyami.kitearchive.codec.deflate.InflateException
import io.github.yuroyami.kitearchive.util.ByteArrayBuilder

/**
 * zlib (RFC 1950) framing over [Deflate] / [Inflate] — a 2-byte CMF/FLG header and
 * a big-endian Adler-32 trailer. This is the wrapper PNG `IDAT` and PDF `FlateDecode`
 * use.
 */
object Zlib {

    /** Compress [data] into a zlib stream. */
    fun compress(data: ByteArray): ByteArray {
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

    /** Decompress a zlib stream. The Adler-32 trailer is not re-validated here. */
    fun decompress(input: ByteArray, maximumSize: Long = 4L * 1024 * 1024): ByteArray {
        require(maximumSize > 0) { "maximumSize must be > 0" }
        if (input.size < 2) throw InflateException(InflateError.INVALID_GZIP_HEADER)

        val cmf = input[0].toInt() and 0xFF
        val flg = input[1].toInt() and 0xFF
        if ((cmf and 0x0F) != 8 || ((cmf ushr 4) and 0x0F) > 7) {
            throw InflateException(InflateError.INVALID_GZIP_HEADER)
        }
        if ((flg and 0x20) != 0) throw InflateException(InflateError.INVALID_GZIP_HEADER) // FDICT
        if (((cmf shl 8) or flg) % 31 != 0) throw InflateException(InflateError.INVALID_GZIP_HEADER)

        val out = Inflate.inflate(input.copyOfRange(2, input.size))
        if (out.size.toLong() > maximumSize) {
            throw InflateException(InflateError.INFLATED_DATA_TOO_LARGE)
        }
        return out
    }
}
