package io.github.yuroyami.archivekodec.codec.zstd

import io.github.yuroyami.archivekodec.checksum.XxHash64
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * Zstandard encoder: one single-segment frame with a content size and checksum. Blocks hold
 * raw literals and LZ77 sequences coded with the predefined FSE tables, and fall back to raw
 * blocks when that does not shrink them. It favours simplicity over ratio: there are no
 * Huffman literals, repeat offsets or custom tables, so `zstd -3` compresses better.
 */
internal object ZstdEncoder {
    private const val HASH_BITS = 16
    private const val CHAIN_DEPTH = 32
    private const val MIN_MATCH = 4
    private const val MAX_MATCH = 65_539 + 65_535

    private val LL_ENCODER = FseEncoder(LL_DEFAULT, 6)
    private val ML_ENCODER = FseEncoder(ML_DEFAULT, 6)
    private val OF_ENCODER = FseEncoder(OF_DEFAULT, 5)

    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(maxOf(64, data.size / 3))
        fun le(value: Long, n: Int) { for (i in 0 until n) out.append((value ushr (8 * i)).toByte()) }
        le(ZSTD_MAGIC.toLong(), 4)
        val size = data.size.toLong()
        val (flag, fieldSize) = when {
            size < 256 -> 0 to 1
            size < 65_536 + 256 -> 1 to 2
            else -> 2 to 4
        }
        out.append(((flag shl 6) or 0x20 or 0x04).toByte()) // single segment, content checksum
        le(if (flag == 1) size - 256 else size, fieldSize)

        val matcher = Matcher(data)
        var pos = 0
        do {
            val end = minOf(data.size, pos + MAX_BLOCK_SIZE)
            val last = end == data.size
            val block = compressBlock(data, pos, end, matcher)
            if (block != null && block.size < end - pos) {
                le(((block.size shl 3) or (2 shl 1) or (if (last) 1 else 0)).toLong(), 3)
                out.append(block)
            } else {
                le((((end - pos) shl 3) or (if (last) 1 else 0)).toLong(), 3)
                out.append(data, pos, end - pos)
            }
            pos = end
        } while (pos < data.size)
        le(XxHash64.hash(data) and 0xFFFFFFFFL, 4)
        return out.takeByteArray()
    }

    /** Hash-chain match finder over the whole input, since the frame is a single segment. */
    private class Matcher(private val data: ByteArray) {
        private val head = IntArray(1 shl HASH_BITS) { -1 }
        private val chain = IntArray(data.size)
        private var inserted = 0
        var distance = 0
            private set

        private fun hash(p: Int): Int {
            val v = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8) or
                ((data[p + 2].toInt() and 0xFF) shl 16) or ((data[p + 3].toInt() and 0xFF) shl 24)
            return (v * -1640531535) ushr (32 - HASH_BITS)
        }

        fun find(p: Int, end: Int): Int {
            while (inserted < p) {
                if (inserted + 3 < data.size) {
                    val h = hash(inserted)
                    chain[inserted] = head[h]
                    head[h] = inserted
                }
                inserted++
            }
            val limit = minOf(MAX_MATCH, end - p)
            if (limit < MIN_MATCH) return 0
            var best = 0
            var candidate = head[hash(p)]
            var depth = CHAIN_DEPTH
            // The predefined offset table codes offset values below 2^29.
            val oldest = p - (1 shl 29) + 4
            while (candidate >= 0 && candidate >= oldest && depth-- > 0) {
                if (data[candidate + best] == data[p + best]) {
                    var n = 0
                    while (n < limit && data[candidate + n] == data[p + n]) n++
                    if (n > best) { best = n; distance = p - candidate; if (n == limit) break }
                }
                candidate = chain[candidate]
            }
            return if (best >= MIN_MATCH) best else 0
        }
    }

    private fun llCode(value: Int): Int = if (value < 16) value else (35 downTo 16).first { LL_BASE[it] <= value }
    private fun mlCode(value: Int): Int = if (value - 3 < 32) value - 3 else (52 downTo 32).first { ML_BASE[it] <= value }

    /** A compressed block body, or null when the block has no sequences worth coding. */
    private fun compressBlock(data: ByteArray, start: Int, end: Int, matcher: Matcher): ByteArray? {
        val literals = ByteArrayBuilder(end - start + 16)
        val lls = ArrayList<Int>()
        val mls = ArrayList<Int>()
        val offsets = ArrayList<Int>()
        var anchor = start
        var p = start
        while (p < end) {
            val length = matcher.find(p, end)
            val distance = matcher.distance
            if (length == 0 || (p + 1 < end && matcher.find(p + 1, end) > length + 1)) { p++; continue }
            literals.append(data, anchor, p - anchor)
            lls.add(p - anchor); mls.add(length); offsets.add(distance + 3)
            p += length
            anchor = p
        }
        if (lls.isEmpty()) return null
        literals.append(data, anchor, end - anchor)

        val body = ByteArrayBuilder(end - start)
        val literalCount = literals.size
        when {
            literalCount < 32 -> body.append((literalCount shl 3).toByte())
            literalCount < 4096 -> { body.append(((literalCount shl 4) or 0x04).toByte()); body.append((literalCount ushr 4).toByte()) }
            else -> {
                body.append(((literalCount shl 4) or 0x0C).toByte())
                body.append((literalCount ushr 4).toByte()); body.append((literalCount ushr 12).toByte())
            }
        }
        body.append(literals.takeByteArray())
        val count = lls.size
        when {
            count < 128 -> body.append(count.toByte())
            count < 0x7F00 -> { body.append(((count ushr 8) + 128).toByte()); body.append(count.toByte()) }
            else -> { body.append(255.toByte()); body.append((count - 0x7F00).toByte()); body.append(((count - 0x7F00) ushr 8).toByte()) }
        }
        body.append(0) // all three tables predefined

        val bits = ForwardBits(body)
        fun extras(n: Int) {
            val ll = lls[n]; val ml = mls[n]; val of = offsets[n]
            val llc = llCode(ll); val mlc = mlCode(ml); val ofc = highBit(of)
            bits.write((ll - LL_BASE[llc]).toLong(), LL_BITS[llc])
            bits.write((ml - ML_BASE[mlc]).toLong(), ML_BITS[mlc])
            bits.write((of - (1 shl ofc)).toLong(), ofc)
        }
        val last = count - 1
        var mlState = ML_ENCODER.initial(mlCode(mls[last]))
        var ofState = OF_ENCODER.initial(highBit(offsets[last]))
        var llState = LL_ENCODER.initial(llCode(lls[last]))
        extras(last)
        for (n in count - 2 downTo 0) {
            ofState = OF_ENCODER.encode(bits, ofState, highBit(offsets[n]))
            mlState = ML_ENCODER.encode(bits, mlState, mlCode(mls[n]))
            llState = LL_ENCODER.encode(bits, llState, llCode(lls[n]))
            extras(n)
        }
        ML_ENCODER.flush(bits, mlState)
        OF_ENCODER.flush(bits, ofState)
        LL_ENCODER.flush(bits, llState)
        bits.close()
        return body.takeByteArray()
    }
}
