package io.github.yuroyami.archivekodec.codec.snappy

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.le16
import io.github.yuroyami.archivekodec.util.le32
import io.github.yuroyami.archivekodec.util.leBytes
import io.github.yuroyami.archivekodec.util.readI32Le
import io.github.yuroyami.archivekodec.util.readUIntLe

/**
 * Snappy raw block format (Google): a byte-oriented LZ77 with no entropy stage.
 * [decompress] and [compress] handle raw blocks. [frameDecompress] and
 * [frameCompress] handle the optional Snappy framed format with chunk checksums.
 * [compress] finds repeated four-byte sequences with a bounded hash table, extends
 * matches greedily and emits the smallest applicable copy-tag form. Incompressible
 * input is stored in literal runs with a small framing overhead.
 */
public object Snappy {

    /**
     * Decompress a Snappy block, validating its complete structure before output allocation.
     *
     * @throws ArchiveFormatException for a malformed preamble, invalid tags or inconsistent output size.
     * @throws DecompressionBombException if declared output exceeds [limits] or the maximum ByteArray size.
     */
    @Throws(ArchiveException::class)
    public fun decompress(src: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray =
        decompressBlock(src, 0, src.size) { length ->
            limits.checkOutputSize(length)
            limits.checkRatio(src.size.toLong(), length)
        }

    /** Decode one bounded raw block, checking its declared output before allocation. */
    internal fun decompressBlock(src: ByteArray, start: Int, end: Int, checkSize: (Long) -> Unit): ByteArray {
        var p = start
        var length = 0L
        var terminated = false
        for (i in 0 until 5) {
            if (p == end) throw ArchiveFormatException("snappy: truncated length preamble", p.toLong())
            val b = src[p++].toInt() and 0xFF
            if (i == 4 && b > 0x0F) throw ArchiveFormatException("snappy: length preamble exceeds u32", (p - 1).toLong())
            length = length or ((b and 0x7F).toLong() shl (7 * i))
            if (b < 0x80) { terminated = true; break }
        }
        if (!terminated) throw ArchiveFormatException("snappy: unterminated length preamble", p.toLong())
        checkSize(length)
        if (length > Int.MAX_VALUE) {
            throw DecompressionBombException("snappy: declared output $length exceeds ByteArray maximum ${Int.MAX_VALUE}")
        }
        val size = length.toInt()
        decode(src, p, end, size, null)
        val out = ByteArray(size)
        decode(src, p, end, size, out)
        return out
    }

    /** Scan once without writes, then decode using exactly the same validated tag rules. */
    private fun decode(src: ByteArray, start: Int, end: Int, size: Int, out: ByteArray?) {
        var p = start
        var o = 0
        fun needInput(n: Int) {
            if (n > end - p) throw ArchiveFormatException("snappy: truncated tag data", p.toLong())
        }
        fun littleEndian(n: Int): Long {
            needInput(n)
            val value = readUIntLe(src, p, n)
            p += n
            return value
        }
        fun needOutput(n: Long) {
            if (n > size.toLong() - o) throw ArchiveFormatException("snappy: tag exceeds declared output size $size", p.toLong())
        }
        while (p < end) {
            val tagOffset = p
            val tag = src[p++].toInt() and 0xFF
            when (tag and 3) {
                0 -> {
                    val encoded = tag ushr 2
                    val length = (if (encoded < 60) encoded.toLong() else littleEndian(encoded - 59)) + 1
                    if (length > end - p) throw ArchiveFormatException("snappy: truncated literal", p.toLong())
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
                        val outputEnd = o + count
                        if (offset >= count) {
                            out.copyInto(out, o, from, from + count)
                        } else {
                            var destination = o
                            while (destination < outputEnd) out[destination++] = out[from++]
                        }
                        o = outputEnd
                    } else {
                        o += count
                    }
                }
            }
        }
        if (o != size) throw ArchiveFormatException("snappy: decoded $o bytes, expected $size", p.toLong())
    }

    /**
     * Compress [data] into a raw Snappy block using literal runs and back-references.
     * One candidate is kept per hash slot; the table uses at most 64 KiB regardless
     * of input size. No external dictionary or stream framing is produced.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    public fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(minOf(data.size, 64 * 1024) + 8)
        var v = data.size
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v != 0) out.append((b or 0x80).toByte()) else { out.append(b.toByte()); break }
        }
        var anchor = 0
        if (data.size >= 4) {
            var tableSize = 256
            var hashBits = 8
            while (tableSize < minOf(data.size, 1 shl 14)) {
                tableSize *= 2
                hashBits++
            }
            val shift = 32 - hashBits
            val table = IntArray(tableSize) { -1 }
            fun hash(position: Int): Int = (readI32Le(data, position) * -1640531535) ushr shift

            val lastStart = data.size - 4
            var position = 0
            while (position <= lastStart) {
                val slot = hash(position)
                val candidate = table[slot]
                table[slot] = position
                if (candidate < 0 || readI32Le(data, candidate) != readI32Le(data, position)) {
                    position++
                    continue
                }

                var length = 4
                val remaining = data.size - position
                while (length < remaining && data[candidate + length] == data[position + length]) length++
                literal(out, data, anchor, position - anchor)
                copy(out, position - candidate, length)
                position += length
                anchor = position

                // Keep nearby history available after skipping the matched input.
                // Overlapping copies are legal, including offsets one and two.
                for (previous in position - 2 until position) {
                    if (previous <= lastStart) table[hash(previous)] = previous
                }
            }
        }
        literal(out, data, anchor, data.size - anchor)
        return out.toByteArray()
    }

    /**
     * Decode a standard Snappy framed stream, including concatenated streams.
     * Validates each chunk's masked CRC-32C and 64 KiB output ceiling, accepts
     * repeat stream identifiers, and skips reserved skippable chunks and padding.
     * [limits] apply to the combined output and the complete input/output ratio.
     *
     * @throws ArchiveFormatException for malformed framing, invalid raw blocks, oversized chunks or bad checksums.
     * @throws io.github.yuroyami.archivekodec.ArchiveUnsupportedException for reserved unskippable chunk types.
     * @throws DecompressionBombException if combined output exceeds [limits] or the maximum ByteArray size.
     */
    @Throws(ArchiveException::class)
    public fun frameDecompress(src: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): ByteArray =
        SnappyFraming.decompress(src, limits)

    /**
     * Encode standard Snappy framing with a stream identifier and independent
     * chunks of at most 64 KiB, each protected by a masked CRC-32C. Chunks that
     * do not shrink are stored uncompressed. Empty input produces an identifier.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    public fun frameCompress(data: ByteArray): ByteArray = SnappyFraming.compress(data)

    private fun literal(out: ByteArrayBuilder, data: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        val encodedLength = length - 1
        if (encodedLength < 60) {
            out.append((encodedLength shl 2).toByte())
        } else {
            var byteCount = 1
            var remaining = encodedLength
            while (remaining >= 256) { byteCount++; remaining = remaining ushr 8 }
            out.append(((59 + byteCount) shl 2).toByte())
            out.leBytes(encodedLength.toLong(), byteCount)
        }
        out.append(data, offset, length)
    }

    private fun copy(out: ByteArrayBuilder, offset: Int, length: Int) {
        var remaining = length
        while (remaining >= 68) {
            copyTag(out, offset, 64)
            remaining -= 64
        }
        // Leave at least four bytes so a short tail can use the compact copy tag.
        if (remaining > 64) {
            copyTag(out, offset, 60)
            remaining -= 60
        }
        copyTag(out, offset, remaining)
    }

    private fun copyTag(out: ByteArrayBuilder, offset: Int, length: Int) {
        when {
            offset < 2048 && length <= 11 -> {
                out.append((1 or ((length - 4) shl 2) or ((offset ushr 8) shl 5)).toByte())
                out.append(offset.toByte())
            }
            offset <= 0xffff -> {
                out.append((2 or ((length - 1) shl 2)).toByte())
                out.le16(offset)
            }
            else -> {
                out.append((3 or ((length - 1) shl 2)).toByte())
                out.le32(offset.toLong())
            }
        }
    }
}
