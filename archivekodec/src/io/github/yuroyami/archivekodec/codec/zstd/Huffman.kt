package io.github.yuroyami.archivekodec.codec.zstd

import io.github.yuroyami.archivekodec.ArchiveFormatException

/**
 * A Huff0 decoding table (RFC 8878 4.2): every code is looked up from [maxBits] bits, giving
 * a symbol and how many bits it really used.
 */
internal class HuffmanTable(val maxBits: Int, private val symbols: ByteArray, private val lengths: ByteArray) {
    fun decode(bits: BackwardBits): Int {
        val index = bits.peek(maxBits)
        bits.skip(lengths[index].toInt())
        return symbols[index].toInt() and 0xFF
    }

    companion object {
        private const val MAX_BITS = 11

        /** Read a tree description at input[[start] until [end]); returns the table and bytes used. */
        fun read(input: ByteArray, start: Int, end: Int): Pair<HuffmanTable, Int> {
            fun fail(message: String): Nothing = throw ArchiveFormatException("zstd: $message", start.toLong())
            if (start >= end) fail("truncated Huffman tree description")
            val header = input[start].toInt() and 0xFF
            val weights = IntArray(256)
            var count: Int
            val used: Int
            if (header < 128) {
                // FSE-compressed weights: two interleaved states over one backward stream.
                val size = header
                if (size == 0 || start + 1 + size > end) fail("truncated Huffman weights")
                val (table, tableBytes) = FseTable.read(input, start + 1, start + 1 + size, 15, 6)
                val bits = BackwardBits(input, start + 1 + tableBytes, start + 1 + size)
                var state1 = bits.read(table.accuracyLog)
                var state2 = bits.read(table.accuracyLog)
                count = 0
                fun next(state: Int): Int = table.baselines[state] + bits.read(table.bits[state])
                while (true) {
                    if (count >= 255) fail("too many Huffman weights")
                    weights[count++] = table.symbols[state1]
                    state1 = next(state1)
                    if (bits.overflowed) { weights[count++] = table.symbols[state2]; break }
                    if (count >= 255) fail("too many Huffman weights")
                    weights[count++] = table.symbols[state2]
                    state2 = next(state2)
                    if (bits.overflowed) { weights[count++] = table.symbols[state1]; break }
                }
                used = 1 + size
            } else {
                count = header - 127
                used = 1 + (count + 1) / 2
                if (start + used > end) fail("truncated Huffman weights")
                for (i in 0 until count) {
                    val b = input[start + 1 + i / 2].toInt() and 0xFF
                    weights[i] = if (i % 2 == 0) b ushr 4 else b and 0x0F
                }
            }
            // The last symbol's weight is implied: it completes the sum to a power of two.
            var total = 0
            for (i in 0 until count) {
                if (weights[i] > MAX_BITS) fail("Huffman weight ${weights[i]} is too large")
                if (weights[i] > 0) total += 1 shl (weights[i] - 1)
            }
            if (total == 0) fail("Huffman weights are all zero")
            val maxBits = highBit(total) + 1
            if (maxBits > MAX_BITS) fail("Huffman codes exceed $MAX_BITS bits")
            val rest = (1 shl maxBits) - total
            if (rest and (rest - 1) != 0) fail("Huffman weights do not complete a tree")
            weights[count++] = highBit(rest) + 1
            if (count > 256) fail("too many Huffman symbols")

            // Fill the table by weight, lowest (longest code) first, symbols in order.
            val size = 1 shl maxBits
            val symbols = ByteArray(size)
            val lengths = ByteArray(size)
            val rankCount = IntArray(MAX_BITS + 2)
            for (i in 0 until count) rankCount[weights[i]]++
            // A complete prefix code has an even number, at least two, of the longest codes.
            if (rankCount[1] < 2 || rankCount[1] % 2 != 0) fail("Huffman weights do not form a valid tree")
            val rankStart = IntArray(MAX_BITS + 2)
            var next = 0
            for (w in 1..maxBits) { rankStart[w] = next; next += rankCount[w] shl (w - 1) }
            for (symbol in 0 until count) {
                val w = weights[symbol]
                if (w == 0) continue
                val length = 1 shl (w - 1)
                val first = rankStart[w]
                for (i in first until first + length) {
                    symbols[i] = symbol.toByte()
                    lengths[i] = (maxBits + 1 - w).toByte()
                }
                rankStart[w] += length
            }
            return HuffmanTable(maxBits, symbols, lengths) to used
        }
    }
}
