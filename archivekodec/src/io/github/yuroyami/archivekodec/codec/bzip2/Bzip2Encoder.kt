package io.github.yuroyami.archivekodec.codec.bzip2

import io.github.yuroyami.archivekodec.codec.deflate.LengthLimitedHuffman
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * bzip2 encoder: the initial run-length stage, a Burrows-Wheeler transform sorted by
 * prefix doubling over cyclic rotations, move-to-front with RUNA/RUNB zero runs, and
 * two to six Huffman tables refined over four passes, as libbzip2 chooses them.
 */
internal object Bzip2Encoder {
    private const val GROUP_SIZE = 50
    private const val MAX_CODE_LENGTH = 15
    private const val RUN_A = 0
    private const val RUN_B = 1

    /** [randomised] writes deprecated randomised blocks; tests use it to check the decoder against libbzip2. */
    fun compress(data: ByteArray, level: Int = 9, randomised: Boolean = false): ByteArray {
        require(level in 1..9) { "bzip2 block size level must be in 1..9" }
        val out = BitWriter(maxOf(64, data.size / 4))
        out.bits(8, 'B'.code); out.bits(8, 'Z'.code); out.bits(8, 'h'.code); out.bits(8, '0'.code + level)
        // libbzip2 keeps 19 bytes of headroom below the nominal block size.
        val maxBlock = level * 100_000 - 19
        val block = ByteArray(maxBlock)
        var combined = 0
        var pos = 0
        while (pos < data.size) {
            val crc = Bzip2Crc()
            var n = 0
            while (pos < data.size && n < maxBlock) {
                val value = data[pos]
                var run = 1
                while (run < 255 && pos + run < data.size && data[pos + run] == value) run++
                if (run >= 4) {
                    if (n + 5 > maxBlock) break
                    block.fill(value, n, n + 4)
                    block[n + 4] = (run - 4).toByte()
                    n += 5
                } else {
                    run = minOf(run, maxBlock - n)
                    block.fill(value, n, n + run)
                    n += run
                }
                repeat(run) { crc.update(value.toInt()) }
                pos += run
            }
            val blockCrc = crc.value()
            combined = (combined shl 1 or (combined ushr 31)) xor blockCrc
            if (randomised) Bzip2Decoder.randomise(block, n)
            writeBlock(out, block, n, blockCrc, randomised)
        }
        out.bits(24, 0x177245); out.bits(24, 0x385090)
        out.bits(32, combined)
        return out.finish()
    }

    private fun writeBlock(out: BitWriter, block: ByteArray, n: Int, crc: Int, randomised: Boolean) {
        val sorted = sortRotations(block, n)
        var origPtr = -1
        val last = ByteArray(n)
        for (row in 0 until n) {
            val start = sorted[row]
            if (start == 0) origPtr = row
            last[row] = block[if (start == 0) n - 1 else start - 1]
        }

        // Map the bytes in use to a dense alphabet.
        val inUse = BooleanArray(256)
        for (i in 0 until n) inUse[block[i].toInt() and 0xFF] = true
        val unseqToSeq = IntArray(256)
        var used = 0
        for (value in 0 until 256) if (inUse[value]) unseqToSeq[value] = used++
        val alphaSize = used + 2
        val endOfBlock = used + 1

        // Move-to-front, with zero runs as bijective base-2 RUNA/RUNB digits.
        val symbols = IntArray(n + 1)
        var count = 0
        val frequency = IntArray(alphaSize)
        fun emit(symbol: Int) { symbols[count++] = symbol; frequency[symbol]++ }
        fun flushZeros(zeros: Int) {
            var pending = zeros - 1
            while (true) {
                emit(if (pending and 1 == 1) RUN_B else RUN_A)
                if (pending < 2) break
                pending = (pending - 2) / 2
            }
        }
        val order = IntArray(used) { it }
        var zeros = 0
        for (row in 0 until n) {
            val seq = unseqToSeq[last[row].toInt() and 0xFF]
            if (order[0] == seq) { zeros++; continue }
            if (zeros > 0) { flushZeros(zeros); zeros = 0 }
            var j = 1
            while (order[j] != seq) j++
            order.copyInto(order, 1, 0, j)
            order[0] = seq
            emit(j + 1)
        }
        if (zeros > 0) flushZeros(zeros)
        emit(endOfBlock)

        // Initial tables partition the symbol frequencies, as libbzip2 does.
        val nGroups = when {
            count < 200 -> 2
            count < 600 -> 3
            count < 1200 -> 4
            count < 2400 -> 5
            else -> 6
        }
        val lengths = Array(nGroups) { IntArray(alphaSize) }
        var parts = nGroups
        var remaining = count
        var groupStart = 0
        while (parts > 0) {
            val target = remaining / parts
            var groupEnd = groupStart - 1
            var accumulated = 0
            while (accumulated < target && groupEnd < alphaSize - 1) accumulated += frequency[++groupEnd]
            if (groupEnd > groupStart && parts != nGroups && parts != 1 && (nGroups - parts) % 2 == 1) {
                accumulated -= frequency[groupEnd--]
            }
            for (symbol in 0 until alphaSize) lengths[parts - 1][symbol] = if (symbol in groupStart..groupEnd) 0 else 15
            parts--
            groupStart = groupEnd + 1
            remaining -= accumulated
        }

        // Refine: choose the cheapest table per 50 symbols, then rebuild the tables.
        val nSelectors = (count + GROUP_SIZE - 1) / GROUP_SIZE
        val selectors = IntArray(nSelectors)
        repeat(4) {
            val tableFrequency = Array(nGroups) { IntArray(alphaSize) }
            for (group in 0 until nSelectors) {
                val start = group * GROUP_SIZE
                val end = minOf(start + GROUP_SIZE, count)
                var best = 0
                var bestCost = Int.MAX_VALUE
                for (t in 0 until nGroups) {
                    var cost = 0
                    for (i in start until end) cost += lengths[t][symbols[i]]
                    if (cost < bestCost) { bestCost = cost; best = t }
                }
                selectors[group] = best
                for (i in start until end) tableFrequency[best][symbols[i]]++
            }
            for (t in 0 until nGroups) {
                // Every symbol needs a code, so unused symbols count once.
                val weights = IntArray(alphaSize) { maxOf(1, tableFrequency[t][it]) }
                lengths[t] = LengthLimitedHuffman.codeLengths(weights, MAX_CODE_LENGTH)
            }
        }

        out.bits(24, 0x314159); out.bits(24, 0x265359)
        out.bits(32, crc)
        out.bits(1, if (randomised) 1 else 0)
        out.bits(24, origPtr)
        var groupsInUse = 0
        for (i in 0 until 16) if ((0 until 16).any { inUse[i * 16 + it] }) groupsInUse = groupsInUse or (0x8000 ushr i)
        out.bits(16, groupsInUse)
        for (i in 0 until 16) {
            if (groupsInUse and (0x8000 ushr i) == 0) continue
            var members = 0
            for (j in 0 until 16) if (inUse[i * 16 + j]) members = members or (0x8000 ushr j)
            out.bits(16, members)
        }
        out.bits(3, nGroups)
        out.bits(15, nSelectors)
        val selectorOrder = IntArray(nGroups) { it }
        for (selector in selectors) {
            var j = 0
            while (selectorOrder[j] != selector) j++
            selectorOrder.copyInto(selectorOrder, 1, 0, j)
            selectorOrder[0] = selector
            repeat(j) { out.bits(1, 1) }
            out.bits(1, 0)
        }
        val codes = Array(nGroups) { IntArray(alphaSize) }
        for (t in 0 until nGroups) {
            var current = lengths[t][0]
            out.bits(5, current)
            for (symbol in 0 until alphaSize) {
                val target = lengths[t][symbol]
                while (current < target) { out.bits(2, 2); current++ }
                while (current > target) { out.bits(2, 3); current-- }
                out.bits(1, 0)
            }
            // Canonical codes in symbol order within each length, as the decoder rebuilds them.
            var code = 0
            for (length in 1..MAX_CODE_LENGTH) {
                for (symbol in 0 until alphaSize) if (lengths[t][symbol] == length) codes[t][symbol] = code++
                code = code shl 1
            }
        }
        for (group in 0 until nSelectors) {
            val t = selectors[group]
            val end = minOf((group + 1) * GROUP_SIZE, count)
            for (i in group * GROUP_SIZE until end) {
                val symbol = symbols[i]
                out.bits(lengths[t][symbol], codes[t][symbol])
            }
        }
    }

    /**
     * Start positions of [block]'s first [n] bytes' cyclic rotations in sorted order, by
     * prefix doubling with counting sorts: O(n log n) time and five arrays of size n.
     */
    internal fun sortRotations(block: ByteArray, n: Int): IntArray {
        val order = IntArray(n)
        val classes = IntArray(n)
        val buckets = IntArray(maxOf(256, n))
        for (i in 0 until n) buckets[block[i].toInt() and 0xFF]++
        for (i in 1 until 256) buckets[i] += buckets[i - 1]
        for (i in n - 1 downTo 0) order[--buckets[block[i].toInt() and 0xFF]] = i
        var classCount = 1
        if (n > 0) classes[order[0]] = 0
        for (i in 1 until n) {
            if (block[order[i]] != block[order[i - 1]]) classCount++
            classes[order[i]] = classCount - 1
        }
        val shifted = IntArray(n)
        val next = IntArray(n)
        var h = 1
        while (h < n && classCount < n) {
            // Sort by the second half by shifting the current order, then stably by the first half.
            for (i in 0 until n) shifted[i] = (order[i] - h).let { if (it < 0) it + n else it }
            buckets.fill(0, 0, classCount)
            for (i in 0 until n) buckets[classes[shifted[i]]]++
            for (i in 1 until classCount) buckets[i] += buckets[i - 1]
            for (i in n - 1 downTo 0) order[--buckets[classes[shifted[i]]]] = shifted[i]
            next[order[0]] = 0
            classCount = 1
            for (i in 1 until n) {
                val a = order[i]
                val b = order[i - 1]
                val aSecond = classes[(a + h).let { if (it >= n) it - n else it }]
                val bSecond = classes[(b + h).let { if (it >= n) it - n else it }]
                if (classes[a] != classes[b] || aSecond != bSecond) classCount++
                next[a] = classCount - 1
            }
            next.copyInto(classes)
            h = h shl 1
        }
        return order
    }

    /** MSB-first bit writer. */
    private class BitWriter(capacity: Int) {
        private val out = ByteArrayBuilder(capacity)
        private var buffer = 0L
        private var count = 0

        fun bits(n: Int, value: Int) {
            buffer = buffer shl n or (value.toLong() and ((1L shl n) - 1))
            count += n
            while (count >= 8) {
                count -= 8
                out.append((buffer ushr count).toByte())
            }
        }

        fun finish(): ByteArray {
            if (count > 0) out.append((buffer shl (8 - count)).toByte())
            count = 0
            return out.takeByteArray()
        }
    }
}
