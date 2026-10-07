package io.github.yuroyami.archivekodec.codec.snappy

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * Snappy raw block format (Google): a byte-oriented LZ77 with no entropy stage.
 * [decompress] reads any conformant stream. [compress] emits a valid all-literal
 * stream, which is correct and fast. It has no back-reference matching, so it does
 * not shrink the input.
 */
object Snappy {

    /** Decompress a Snappy block, validating its complete structure before output allocation. */
    @Throws(ArchiveException::class)
    fun decompress(src: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray {
        var p = 0
        var length = 0L
        var terminated = false
        for (i in 0 until 5) {
            if (p == src.size) throw ArchiveFormatException("snappy: truncated length preamble", p.toLong())
            val b = src[p++].toInt() and 0xFF
            if (i == 4 && b > 0x0F) throw ArchiveFormatException("snappy: length preamble exceeds u32", (p - 1).toLong())
            length = length or ((b and 0x7F).toLong() shl (7 * i))
            if (b < 0x80) { terminated = true; break }
        }
        if (!terminated) throw ArchiveFormatException("snappy: unterminated length preamble", p.toLong())
        limits.checkOutputSize(length)
        if (length > Int.MAX_VALUE) {
            throw DecompressionBombException("snappy: declared output $length exceeds ByteArray maximum ${Int.MAX_VALUE}")
        }
        limits.checkRatio(src.size.toLong(), length)
        val size = length.toInt()
        decode(src, p, size, null)
        val out = ByteArray(size)
        decode(src, p, size, out)
        return out
    }

    /** Scan once without writes, then decode using exactly the same validated tag rules. */
    private fun decode(src: ByteArray, start: Int, size: Int, out: ByteArray?) {
        var p = start
        var o = 0
        fun needInput(n: Int) {
            if (n > src.size - p) throw ArchiveFormatException("snappy: truncated tag data", p.toLong())
        }
        fun littleEndian(n: Int): Long {
            needInput(n)
            var value = 0L
            repeat(n) { i -> value = value or ((src[p++].toLong() and 0xFF) shl (8 * i)) }
            return value
        }
        fun needOutput(n: Long) {
            if (n > size.toLong() - o) throw ArchiveFormatException("snappy: tag exceeds declared output size $size", p.toLong())
        }
        while (p < src.size) {
            val tagOffset = p
            val tag = src[p++].toInt() and 0xFF
            when (tag and 3) {
                0 -> {
                    val encoded = tag ushr 2
                    val length = (if (encoded < 60) encoded.toLong() else littleEndian(encoded - 59)) + 1
                    if (length > src.size - p) throw ArchiveFormatException("snappy: truncated literal", p.toLong())
                    needOutput(length)
                    val count = length.toInt()
                    if (out != null) src.copyInto(out, o, p, p + count)
                    p += count
                    o += count
                }
                else -> {
                    val count: Int
                    val offset: Long
                    if (tag and 3 == 1) {
                        count = ((tag ushr 2) and 7) + 4
                        offset = ((tag ushr 5).toLong() shl 8) or littleEndian(1)
                    } else {
                        count = (tag ushr 2) + 1
                        offset = littleEndian(if (tag and 3 == 2) 2 else 4)
                    }
                    if (offset == 0L || offset > o) {
                        throw ArchiveFormatException("snappy: invalid copy offset $offset at output $o", tagOffset.toLong())
                    }
                    needOutput(count.toLong())
                    if (out != null) {
                        var from = o - offset.toInt()
                        repeat(count) { out[o++] = out[from++] } // overlapping copies are intentional
                    } else {
                        o += count
                    }
                }
            }
        }
        if (o != size) throw ArchiveFormatException("snappy: decoded $o bytes, expected $size", p.toLong())
    }

    /** Compress [data] into a valid (all-literal) Snappy block. */
    @Throws(ArchiveException::class)
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
