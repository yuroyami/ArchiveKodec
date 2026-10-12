package io.github.yuroyami.archivekodec.codec.zstd

import io.github.yuroyami.archivekodec.ArchiveFormatException

/** An FSE decoding table (RFC 8878 4.1.1): per state, a symbol, a bit count and a baseline. */
internal class FseTable(val accuracyLog: Int, val symbols: IntArray, val bits: IntArray, val baselines: IntArray) {
    companion object {
        /** A one-symbol table, for RLE mode: no bits are ever read. */
        fun rle(symbol: Int) = FseTable(0, intArrayOf(symbol), intArrayOf(0), intArrayOf(0))

        /** Build from normalised counts, where -1 marks a "less than one" probability. */
        fun build(counts: IntArray, accuracyLog: Int): FseTable {
            val size = 1 shl accuracyLog
            val symbols = IntArray(size)
            val next = IntArray(counts.size)
            var high = size - 1
            for (s in counts.indices) {
                if (counts[s] == -1) { symbols[high--] = s; next[s] = 1 } else next[s] = counts[s]
            }
            spread(counts, accuracyLog, high, symbols)
            val bits = IntArray(size)
            val baselines = IntArray(size)
            for (u in 0 until size) {
                val state = next[symbols[u]]++
                bits[u] = accuracyLog - highBit(state)
                baselines[u] = (state shl bits[u]) - size
            }
            return FseTable(accuracyLog, symbols, bits, baselines)
        }

        /** Place each symbol's states with zstd's step, skipping the high cells of -1 symbols. */
        fun spread(counts: IntArray, accuracyLog: Int, high: Int, symbols: IntArray) {
            val size = 1 shl accuracyLog
            val step = (size ushr 1) + (size ushr 3) + 3
            val mask = size - 1
            var position = 0
            for (s in counts.indices) {
                repeat(maxOf(counts[s], 0)) {
                    symbols[position] = s
                    do { position = (position + step) and mask } while (position > high)
                }
            }
            if (position != 0) throw ArchiveFormatException("zstd: FSE distribution does not fill its table")
        }

        /**
         * Read a table description from input[[start] until [end]) (RFC 8878 4.1.1), with at
         * most [maxSymbol] + 1 symbols and an accuracy log of at most [maxLog]. Returns the
         * table and the number of bytes the description used.
         */
        fun read(input: ByteArray, start: Int, end: Int, maxSymbol: Int, maxLog: Int): Pair<FseTable, Int> {
            var bitPosition = 0L
            fun fail(message: String): Nothing = throw ArchiveFormatException("zstd: $message", start + bitPosition / 8)
            fun bits(n: Int, consume: Boolean = true): Int {
                var value = 0
                for (i in 0 until n) {
                    val p = bitPosition + i
                    val byte = start + (p ushr 3).toInt()
                    if (byte >= end) fail("truncated FSE table description")
                    value = value or ((input[byte].toInt() ushr (p and 7).toInt() and 1) shl i)
                }
                if (consume) bitPosition += n
                return value
            }
            val accuracyLog = bits(4) + 5
            if (accuracyLog > maxLog) fail("FSE accuracy log $accuracyLog exceeds $maxLog")
            val counts = IntArray(maxSymbol + 1)
            var remaining = (1 shl accuracyLog) + 1
            var threshold = 1 shl accuracyLog
            var nbBits = accuracyLog + 1
            var symbol = 0
            var previousZero = false
            while (remaining > 1 && symbol <= maxSymbol) {
                if (previousZero) {
                    var zeros = symbol
                    while (true) {
                        val repeat = bits(2)
                        zeros += repeat
                        if (repeat != 3) break
                    }
                    if (zeros > maxSymbol) fail("FSE zero run passes the last symbol")
                    symbol = zeros
                }
                val max = (2 * threshold - 1) - remaining
                var count: Int
                val small = bits(nbBits - 1, consume = false)
                if (small < max) {
                    count = small
                    bitPosition += nbBits - 1
                } else {
                    count = bits(nbBits)
                    if (count >= threshold) count -= max
                }
                count--
                remaining -= if (count < 0) -count else count
                counts[symbol++] = count
                previousZero = count == 0
                while (remaining < threshold) { nbBits--; threshold = threshold ushr 1 }
            }
            if (remaining != 1) fail("FSE probabilities do not sum to the table size")
            return build(counts, accuracyLog) to ((bitPosition + 7) / 8).toInt()
        }
    }
}

/** FSE encoding for one distribution, as libzstd's FSE_buildCTable lays it out. */
internal class FseEncoder(counts: IntArray, val accuracyLog: Int) {
    private val size = 1 shl accuracyLog
    private val stateTable = IntArray(size)
    private val deltaBits = IntArray(counts.size)
    private val deltaState = IntArray(counts.size)

    init {
        val symbols = IntArray(size)
        val cumulative = IntArray(counts.size + 1)
        var high = size - 1
        for (s in counts.indices) {
            if (counts[s] == -1) {
                cumulative[s + 1] = cumulative[s] + 1
                symbols[high--] = s
            } else cumulative[s + 1] = cumulative[s] + counts[s]
        }
        FseTable.spread(counts, accuracyLog, high, symbols)
        for (u in 0 until size) stateTable[cumulative[symbols[u]]++] = size + u
        var total = 0
        for (s in counts.indices) {
            when (counts[s]) {
                0 -> deltaBits[s] = ((accuracyLog + 1) shl 16) - size
                -1, 1 -> {
                    deltaBits[s] = (accuracyLog shl 16) - size
                    deltaState[s] = total - 1
                    total++
                }
                else -> {
                    val maxBitsOut = accuracyLog - highBit(counts[s] - 1)
                    deltaBits[s] = (maxBitsOut shl 16) - (counts[s] shl maxBitsOut)
                    deltaState[s] = total - counts[s]
                    total += counts[s]
                }
            }
        }
    }

    /** A state that starts by emitting [symbol], writing no bits. */
    fun initial(symbol: Int): Int {
        val bitsOut = (deltaBits[symbol] + (1 shl 15)) ushr 16
        val value = (bitsOut shl 16) - deltaBits[symbol]
        return stateTable[(value ushr bitsOut) + deltaState[symbol]]
    }

    /** Write the bits that move [state] to [symbol], returning the new state. */
    fun encode(out: ForwardBits, state: Int, symbol: Int): Int {
        val bitsOut = (state + deltaBits[symbol]) ushr 16
        out.write(state.toLong(), bitsOut)
        return stateTable[(state ushr bitsOut) + deltaState[symbol]]
    }

    fun flush(out: ForwardBits, state: Int) = out.write(state.toLong(), accuracyLog)
}
