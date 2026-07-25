package io.github.yuroyami.kitearchive.codec.snappy

import io.github.yuroyami.kitearchive.security.ArchiveLimits
import io.github.yuroyami.kitearchive.util.ByteArrayBuilder

/**
 * Snappy raw block format (Google): a byte-oriented LZ77 with no entropy stage.
 * [decompress] reads any conformant stream. [compress] emits a valid all-literal
 * stream, which is correct and fast. It has no back-reference matching, so it does
 * not shrink the input.
 */
object Snappy {

    /** Decompress a Snappy block, enforcing [limits]. */
    fun decompress(src: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray {
        var p = 0
        var length = 0L
        var shift = 0
        while (true) {
            val b = src[p++].toInt() and 0xFF
            length = length or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) break
            shift += 7
        }
        limits.checkOutputSize(length)
        var out = ByteArray(length.toInt())
        var o = 0

        while (p < src.size) {
            val tag = src[p++].toInt() and 0xFF
            when (tag and 0x03) {
                0 -> { // literal
                    var litLen = tag ushr 2
                    if (litLen >= 60) {
                        val n = litLen - 59
                        litLen = 0
                        for (k in 0 until n) litLen = litLen or ((src[p++].toInt() and 0xFF) shl (8 * k))
                    }
                    litLen += 1
                    for (k in 0 until litLen) out[o++] = src[p++]
                }
                1 -> { // copy, 1-byte offset
                    val len = ((tag ushr 2) and 0x07) + 4
                    val offset = ((tag ushr 5) shl 8) or (src[p++].toInt() and 0xFF)
                    o = copyMatch(out, o, offset, len)
                }
                2 -> { // copy, 2-byte offset
                    val len = (tag ushr 2) + 1
                    val offset = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8)
                    p += 2
                    o = copyMatch(out, o, offset, len)
                }
                else -> { // copy, 4-byte offset
                    val len = (tag ushr 2) + 1
                    val offset = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8) or
                        ((src[p + 2].toInt() and 0xFF) shl 16) or ((src[p + 3].toInt() and 0xFF) shl 24)
                    p += 4
                    o = copyMatch(out, o, offset, len)
                }
            }
        }
        return out
    }

    private fun copyMatch(out: ByteArray, o: Int, offset: Int, len: Int): Int {
        var oo = o
        var m = o - offset
        repeat(len) { out[oo++] = out[m++] } // byte-by-byte: overlap-safe
        return oo
    }

    /** Compress [data] into a valid (all-literal) Snappy block. */
    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(data.size + 8)
        var v = data.size
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v != 0) out.append((b or 0x80).toByte()) else { out.append(b.toByte()); break }
        }
        var off = 0
        while (off < data.size) {
            val chunk = minOf(data.size - off, 1 shl 24)
            val l = chunk - 1
            if (l < 60) {
                out.append((l shl 2).toByte())
            } else {
                var nbytes = 1
                var t = l
                while (t >= 256) { nbytes++; t = t ushr 8 }
                out.append(((59 + nbytes) shl 2).toByte())
                var x = l
                repeat(nbytes) { out.append((x and 0xFF).toByte()); x = x ushr 8 }
            }
            out.append(data, off, chunk)
            off += chunk
        }
        return out.toByteArray()
    }
}
