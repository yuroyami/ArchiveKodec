package io.github.yuroyami.kitearchive.codec.lz4

import io.github.yuroyami.kitearchive.checksum.XxHash32
import io.github.yuroyami.kitearchive.security.ArchiveLimits
import io.github.yuroyami.kitearchive.util.ByteArrayBuilder

/**
 * LZ4 — block format (the LZ77 token codec) + frame format (`.lz4`, magic
 * `0x184D2204`). Pure-Kotlin, no entropy stage, so this is the cheap fast tier.
 *
 * Decoder reads any conformant frame (skipping/honoring the optional block and
 * content xxHash32 checks). Encoder writes a minimal frame (block-independent,
 * 4 MiB max block, no checksums) with a real greedy hash-chain block compressor and
 * a stored-block fallback when a block doesn't shrink.
 */
object Lz4 {

    private const val MAGIC = 0x184D2204L
    private const val MIN_MATCH = 4
    private const val MF_LIMIT = 12          // last 12 bytes are never the start of a match
    private const val LAST_LITERALS = 5      // last 5 bytes are always literals
    private const val MAX_BLOCK = 4 * 1024 * 1024
    private const val HASH_BITS = 16

    /* ─── Block format ───────────────────────────────────────────────────── */

    /** Decompress one LZ4 block in `src[sOff, sEnd)`. */
    fun blockDecompress(src: ByteArray, sOff: Int, sEnd: Int, expectedSize: Int = -1): ByteArray {
        var out = ByteArray(if (expectedSize > 0) expectedSize else maxOf(64, (sEnd - sOff) * 3))
        var o = 0
        fun ensure(n: Int) {
            if (o + n > out.size) {
                var c = out.size
                while (c < o + n) c = c shl 1
                out = out.copyOf(c)
            }
        }
        var s = sOff
        while (s < sEnd) {
            val token = src[s++].toInt() and 0xFF
            var litLen = token ushr 4
            if (litLen == 15) {
                var b = 255
                while (b == 255) { b = src[s++].toInt() and 0xFF; litLen += b }
            }
            ensure(litLen)
            for (k in 0 until litLen) out[o++] = src[s++]
            if (s >= sEnd) break // last sequence: literals only

            val offset = (src[s].toInt() and 0xFF) or ((src[s + 1].toInt() and 0xFF) shl 8)
            s += 2
            if (offset == 0) error("lz4: invalid zero offset")
            var matchLen = token and 0xF
            if (matchLen == 15) {
                var b = 255
                while (b == 255) { b = src[s++].toInt() and 0xFF; matchLen += b }
            }
            matchLen += MIN_MATCH
            ensure(matchLen)
            var m = o - offset
            if (m < 0) error("lz4: offset before start of output")
            repeat(matchLen) { out[o++] = out[m++] } // byte-by-byte: overlap-safe
        }
        return out.copyOf(o)
    }

    /** Greedy LZ4 block compressor. Always produces a conformant block. */
    fun blockCompress(data: ByteArray): ByteArray {
        val n = data.size
        val out = ByteArrayBuilder(maxOf(16, n / 2))
        if (n < MF_LIMIT + 1) {
            emitLastLiterals(out, data, 0, n)
            return out.toByteArray()
        }
        val table = IntArray(1 shl HASH_BITS) { -1 }
        val mfLimit = n - MF_LIMIT
        val matchLimit = n - LAST_LITERALS
        var anchor = 0
        var i = 0
        while (i < mfLimit) {
            val h = hash(read4(data, i))
            val ref = table[h]
            table[h] = i
            if (ref >= 0 && i - ref <= 0xFFFF && read4(data, ref) == read4(data, i)) {
                var len = MIN_MATCH
                while (i + len < matchLimit && data[ref + len] == data[i + len]) len++
                emitSequence(out, data, anchor, i, i - ref, len)
                i += len
                anchor = i
            } else {
                i++
            }
        }
        emitLastLiterals(out, data, anchor, n)
        return out.toByteArray()
    }

    private fun emitSequence(out: ByteArrayBuilder, data: ByteArray, anchor: Int, matchPos: Int, offset: Int, matchLen: Int) {
        val litLen = matchPos - anchor
        val mlen = matchLen - MIN_MATCH
        val token = ((if (litLen >= 15) 15 else litLen) shl 4) or (if (mlen >= 15) 15 else mlen)
        out.append(token.toByte())
        writeLength(out, litLen)
        out.append(data, anchor, litLen)
        out.append((offset and 0xFF).toByte())
        out.append(((offset ushr 8) and 0xFF).toByte())
        writeLength(out, mlen)
    }

    private fun emitLastLiterals(out: ByteArrayBuilder, data: ByteArray, anchor: Int, end: Int) {
        val litLen = end - anchor
        val token = (if (litLen >= 15) 15 else litLen) shl 4
        out.append(token.toByte())
        writeLength(out, litLen)
        out.append(data, anchor, litLen)
    }

    /** Write the 255-chunked extension bytes for a length >= 15. */
    private fun writeLength(out: ByteArrayBuilder, length: Int) {
        if (length < 15) return
        var r = length - 15
        while (r >= 255) { out.append(255.toByte()); r -= 255 }
        out.append(r.toByte())
    }

    private fun read4(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun hash(v: Int): Int = (v * -1640531535) ushr (32 - HASH_BITS)

    /* ─── Frame format (.lz4) ────────────────────────────────────────────── */

    /** Compress [data] into a complete LZ4 frame (no checksums, 4 MiB blocks). */
    fun frameCompress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(maxOf(32, data.size / 2))
        out.append(0x04.toByte()); out.append(0x22.toByte()); out.append(0x4D.toByte()); out.append(0x18.toByte())
        val flg = 0x60 // version=01, block-independent
        val bd = 0x70  // 4 MiB max block size
        out.append(flg.toByte())
        out.append(bd.toByte())
        val hc = (XxHash32.hash(byteArrayOf(flg.toByte(), bd.toByte())).toInt() ushr 8) and 0xFF
        out.append(hc.toByte())

        var pos = 0
        while (pos < data.size) {
            val end = minOf(pos + MAX_BLOCK, data.size)
            val chunk = data.copyOfRange(pos, end)
            val comp = blockCompress(chunk)
            if (comp.size < chunk.size) {
                writeLe32(out, comp.size.toLong())
                out.append(comp)
            } else {
                writeLe32(out, chunk.size.toLong() or 0x80000000L) // high bit = uncompressed
                out.append(chunk)
            }
            pos = end
        }
        writeLe32(out, 0L) // end mark
        return out.toByteArray()
    }

    /** Decompress an LZ4 frame, enforcing [limits]. */
    fun frameDecompress(input: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray {
        var p = 0
        fun u32(): Long {
            val v = (input[p].toLong() and 0xFF) or ((input[p + 1].toLong() and 0xFF) shl 8) or
                ((input[p + 2].toLong() and 0xFF) shl 16) or ((input[p + 3].toLong() and 0xFF) shl 24)
            p += 4
            return v
        }
        if (u32() != MAGIC) error("lz4: bad frame magic")
        val flg = input[p++].toInt() and 0xFF
        p++ // BD
        val blockChecksum = (flg and 0x10) != 0
        val contentSize = (flg and 0x08) != 0
        val contentChecksum = (flg and 0x04) != 0
        val dictId = (flg and 0x01) != 0
        if (contentSize) p += 8
        if (dictId) p += 4
        p++ // HC header checksum byte

        val out = ByteArrayBuilder(maxOf(64, input.size))
        while (true) {
            val raw = u32()
            if (raw == 0L) break // end mark
            val uncompressed = (raw and 0x80000000L) != 0L
            val blockSize = (raw and 0x7FFFFFFFL).toInt()
            if (uncompressed) {
                out.append(input, p, blockSize)
            } else {
                out.append(blockDecompress(input, p, p + blockSize))
            }
            p += blockSize
            if (blockChecksum) p += 4
            limits.checkOutputSize(out.size.toLong())
        }
        if (contentChecksum) p += 4
        return out.toByteArray()
    }

    private fun writeLe32(out: ByteArrayBuilder, v: Long) {
        out.append((v and 0xFF).toByte())
        out.append(((v ushr 8) and 0xFF).toByte())
        out.append(((v ushr 16) and 0xFF).toByte())
        out.append(((v ushr 24) and 0xFF).toByte())
    }
}
