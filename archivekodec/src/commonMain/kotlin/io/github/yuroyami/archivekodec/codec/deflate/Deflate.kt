/* Altered Kotlin implementation of zlib's trees.c Huffman construction. */

/*
Copyright notice:

 (C) 1995-2026 Jean-loup Gailly and Mark Adler

  This software is provided 'as-is', without any express or implied
  warranty.  In no event will the authors be held liable for any damages
  arising from the use of this software.

  Permission is granted to anyone to use this software for any purpose,
  including commercial applications, and to alter it and redistribute it
  freely, subject to the following restrictions:

  1. The origin of this software must not be misrepresented; you must not
     claim that you wrote the original software. If you use this software
     in a product, an acknowledgment in the product documentation would be
     appreciated but is not required.
  2. Altered source versions must be plainly marked as such, and must not be
     misrepresented as being the original software.
  3. This notice may not be removed or altered from any source distribution.

  Jean-loup Gailly        Mark Adler
  jloup@gzip.org          madler@alumni.caltech.edu
*/

package io.github.yuroyami.archivekodec.codec.deflate

import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * Pure-Kotlin RFC 1951 DEFLATE *deflater*, the inverse of [Inflate].
 *
 * Pipeline: greedy hash-chained LZ77 over a 32 KiB window → one final block,
 * emitted with whichever of **fixed** (BTYPE=01) or **dynamic** (BTYPE=10) Huffman
 * codes is smaller for this input. The dynamic code lengths follow zlib's
 * length-limited Huffman (`trees.c` `build_tree` + `gen_bitlen`): a min-heap Huffman
 * tree, then the overflow-redistribution that caps lengths at 15 bits (7 for the
 * code-length code) while preserving the Kraft equality.
 *
 * This encoder never emits stored (BTYPE=00) blocks and never splits its output into
 * several blocks. One dynamic or fixed block encodes an input of any size.
 *
 * Output is bare DEFLATE; the gzip/zlib framers wrap it. It decodes byte-for-byte
 * through [Inflate] and through stock zlib.
 */
object Deflate {

    /** Compress [data] to a bare DEFLATE stream (no zlib/gzip wrapper). */
    fun encode(data: ByteArray): ByteArray = Deflater(data).encode()
}

private class Deflater(private val data: ByteArray) {

    private val out = ByteArrayBuilder(maxOf(64, data.size / 3))
    private var bitBuf = 0
    private var bitCount = 0

    // LZ77 token stream, packed one Int per token (see [MATCH_FLAG]).
    private var tokens = IntArray(256)
    private var tokenCount = 0

    // Symbol frequencies gathered during LZ77.
    private val llFreq = IntArray(286)   // literals 0..255, EOB 256, length codes 257..285
    private val dFreq = IntArray(30)     // distance codes

    fun encode(): ByteArray {
        lz77()
        llFreq[END_OF_BLOCK]++           // every block ends with one EOB

        // Build dynamic tables.
        val llLen = codeLengths(llFreq, MAX_BITS)
        val dLenRaw = codeLengths(dFreq, MAX_BITS)
        val dLen = if (dLenRaw.any { it > 0 }) dLenRaw else IntArray(30).also { it[0] = 1 }

        val hlit = maxIndex(llLen, minTop = END_OF_BLOCK) + 1   // >= 257
        val hdist = maxIndex(dLen, minTop = 0) + 1              // >= 1

        // Code-length code: RLE of (llLen[0..hlit) ++ dLen[0..hdist)).
        val clSeq = buildCodeLengthSequence(llLen, hlit, dLen, hdist)
        val clFreq = IntArray(19)
        for (i in clSeq.indices step 3) clFreq[clSeq[i]]++
        val clLen = codeLengths(clFreq, MAX_CL_BITS)
        var numCl = 19
        while (numCl > 4 && clLen[ORDER[numCl - 1]] == 0) numCl--

        // Pick the smaller of dynamic vs fixed (extra bits cancel, so compare the rest).
        val dynamicBits = dynamicHeaderBits(numCl, clSeq, clLen) + symbolBits(llLen, dLen)
        val fixedBits = fixedSymbolBits()
        val useDynamic = dynamicBits < fixedBits

        // BFINAL=1, then BTYPE (LSB-first).
        writeBits(1, 1)
        if (useDynamic) {
            writeBits(0b10, 2)
            writeDynamicHeader(hlit, hdist, numCl, clSeq, clLen)
            emitData(canonicalCodes(llLen), llLen, canonicalCodes(dLen), dLen)
        } else {
            writeBits(0b01, 2)
            emitData(FIXED_LITLEN_CODE, FIXED_LITLEN_LEN, FIXED_DIST_CODE, FIXED_DIST_LEN)
        }
        flushToByte()
        return out.toByteArray()
    }

    /* ─── LZ77 (greedy, hash-chained): gathers tokens + frequencies ──────── */

    private fun lz77() {
        val n = data.size
        if (n == 0) return
        val head = IntArray(HASH_SIZE) { -1 }
        val prev = IntArray(n)

        fun hash(i: Int): Int {
            val a = data[i].toInt() and 0xFF
            val b = data[i + 1].toInt() and 0xFF
            val c = data[i + 2].toInt() and 0xFF
            return ((a shl 10) xor (b shl 5) xor c) and (HASH_SIZE - 1)
        }
        fun insert(i: Int) {
            if (i > n - MIN_MATCH) return
            val h = hash(i)
            prev[i] = head[h]
            head[h] = i
        }

        var pos = 0
        while (pos < n) {
            var bestLen = 0
            var bestDist = 0
            if (pos <= n - MIN_MATCH) {
                val maxLen = minOf(MAX_MATCH, n - pos)
                val minPos = maxOf(0, pos - WINDOW_SIZE)
                var cand = head[hash(pos)]
                var chain = MAX_CHAIN
                while (cand >= minPos && chain-- > 0) {
                    var l = 0
                    while (l < maxLen && data[cand + l] == data[pos + l]) l++
                    if (l > bestLen) {
                        bestLen = l
                        bestDist = pos - cand
                        if (l >= maxLen) break
                    }
                    cand = prev[cand]
                }
            }
            if (bestLen >= MIN_MATCH) {
                addMatch(bestLen, bestDist)
                val end = pos + bestLen
                while (pos < end) { insert(pos); pos++ }
            } else {
                addLiteral(data[pos].toInt() and 0xFF)
                insert(pos)
                pos++
            }
        }
    }

    private fun addLiteral(v: Int) {
        appendToken(v)
        llFreq[v]++
    }

    private fun addMatch(length: Int, distance: Int) {
        appendToken(MATCH_FLAG or (length shl 16) or distance)
        llFreq[END_OF_BLOCK + 1 + lengthCode(length)]++  // 257 + lc
        dFreq[distCode(distance)]++
    }

    private fun appendToken(t: Int) {
        if (tokenCount == tokens.size) tokens = tokens.copyOf(tokens.size * 2)
        tokens[tokenCount++] = t
    }

    /* ─── Emit the token stream with a chosen pair of code tables ─────────── */

    private fun emitData(llCode: IntArray, llLen: IntArray, dCode: IntArray, dLen: IntArray) {
        for (i in 0 until tokenCount) {
            val t = tokens[i]
            if (t and MATCH_FLAG == 0) {
                writeCode(llCode[t], llLen[t])                              // literal
            } else {
                val length = (t ushr 16) and 0x1FF
                val distance = t and 0xFFFF
                val lc = lengthCode(length)
                val sym = END_OF_BLOCK + 1 + lc
                writeCode(llCode[sym], llLen[sym])
                if (LENGTH_EXTRA[lc] > 0) writeBits(length - LENGTH_BASE[lc], LENGTH_EXTRA[lc])
                val dc = distCode(distance)
                writeCode(dCode[dc], dLen[dc])
                if (DIST_EXTRA[dc] > 0) writeBits(distance - DIST_BASE[dc], DIST_EXTRA[dc])
            }
        }
        writeCode(llCode[END_OF_BLOCK], llLen[END_OF_BLOCK])               // EOB
    }

    /* ─── Dynamic-block header (HLIT/HDIST/HCLEN + code-length code) ──────── */

    private fun writeDynamicHeader(hlit: Int, hdist: Int, numCl: Int, clSeq: IntArray, clLen: IntArray) {
        writeBits(hlit - 257, 5)
        writeBits(hdist - 1, 5)
        writeBits(numCl - 4, 4)
        for (k in 0 until numCl) writeBits(clLen[ORDER[k]], 3)
        val clCode = canonicalCodes(clLen)
        var i = 0
        while (i < clSeq.size) {
            val sym = clSeq[i]
            val extra = clSeq[i + 1]
            val nbits = clSeq[i + 2]
            writeCode(clCode[sym], clLen[sym])
            if (nbits > 0) writeBits(extra, nbits)
            i += 3
        }
    }

    /**
     * RLE-encode the concatenated code-length list into code-length-code symbols
     * (0..15 literal, 16 = repeat-prev 3..6, 17 = zero 3..10, 18 = zero 11..138).
     * Returns a flat [sym, extra, extraBits] triple stream.
     */
    private fun buildCodeLengthSequence(llLen: IntArray, hlit: Int, dLen: IntArray, hdist: Int): IntArray {
        val merged = IntArray(hlit + hdist)
        for (i in 0 until hlit) merged[i] = llLen[i]
        for (i in 0 until hdist) merged[hlit + i] = dLen[i]

        val seq = ArrayList<Int>(merged.size)
        fun emit(sym: Int, extra: Int, nbits: Int) { seq.add(sym); seq.add(extra); seq.add(nbits) }

        var i = 0
        while (i < merged.size) {
            val cur = merged[i]
            var run = 1
            while (i + run < merged.size && merged[i + run] == cur) run++
            if (cur == 0) {
                var rl = run
                while (rl >= 11) { val take = minOf(rl, 138); emit(18, take - 11, 7); rl -= take }
                while (rl >= 3) { val take = minOf(rl, 10); emit(17, take - 3, 3); rl -= take }
                repeat(rl) { emit(0, 0, 0) }
            } else {
                emit(cur, 0, 0)
                var rl = run - 1
                while (rl >= 3) { val take = minOf(rl, 6); emit(16, take - 3, 2); rl -= take }
                repeat(rl) { emit(cur, 0, 0) }
            }
            i += run
        }
        return seq.toIntArray()
    }

    /* ─── Size estimation (to choose dynamic vs fixed) ───────────────────── */

    private fun symbolBits(llLen: IntArray, dLen: IntArray): Long {
        var bits = 0L
        for (s in 0..285) bits += llFreq[s].toLong() * llLen[s]
        for (d in 0..29) bits += dFreq[d].toLong() * dLen[d]
        return bits
    }

    private fun fixedSymbolBits(): Long {
        var bits = 0L
        for (s in 0..285) bits += llFreq[s].toLong() * FIXED_LITLEN_LEN[s]
        for (d in 0..29) bits += dFreq[d].toLong() * FIXED_DIST_LEN[d]
        return bits
    }

    private fun dynamicHeaderBits(numCl: Int, clSeq: IntArray, clLen: IntArray): Long {
        var bits = (5 + 5 + 4 + numCl * 3).toLong()
        var i = 0
        while (i < clSeq.size) {
            bits += clLen[clSeq[i]] + clSeq[i + 2]
            i += 3
        }
        return bits
    }

    private fun maxIndex(lengths: IntArray, minTop: Int): Int {
        var top = minTop
        for (i in lengths.indices) if (lengths[i] > 0) top = i
        return top
    }

    /* ─── Bit writer ─────────────────────────────────────────────────────── */

    private fun writeBits(value: Int, n: Int) {
        bitBuf = bitBuf or ((value and ((1 shl n) - 1)) shl bitCount)
        bitCount += n
        while (bitCount >= 8) {
            out.append((bitBuf and 0xFF).toByte())
            bitBuf = bitBuf ushr 8
            bitCount -= 8
        }
    }

    /** Write a Huffman [code] of [len] bits, most-significant bit first. */
    private fun writeCode(code: Int, len: Int) {
        var reversed = 0
        var c = code
        repeat(len) {
            reversed = (reversed shl 1) or (c and 1)
            c = c ushr 1
        }
        writeBits(reversed, len)
    }

    private fun flushToByte() {
        if (bitCount > 0) {
            out.append((bitBuf and 0xFF).toByte())
            bitBuf = 0
            bitCount = 0
        }
    }

    private fun lengthCode(length: Int): Int {
        var i = 0
        while (i < 28 && LENGTH_BASE[i + 1] <= length) i++
        return i
    }

    private fun distCode(distance: Int): Int {
        var j = 0
        while (j < 29 && DIST_BASE[j + 1] <= distance) j++
        return j
    }

    companion object {
        private const val WINDOW_SIZE = 32_768
        private const val MIN_MATCH = 3
        private const val MAX_MATCH = 258
        private const val HASH_SIZE = 1 shl 15
        private const val MAX_CHAIN = 128

        private const val END_OF_BLOCK = 256
        private const val MAX_BITS = 15        // max bits for lit/len + dist codes
        private const val MAX_CL_BITS = 7      // max bits for the code-length code
        private const val MATCH_FLAG = 1 shl 30

        // HCLEN permutation (RFC 1951 §3.2.7).
        private val ORDER = intArrayOf(
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15,
        )

        private val LENGTH_BASE = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
            35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
        )
        private val LENGTH_EXTRA = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
        )
        private val DIST_BASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
            257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
            8193, 12289, 16385, 24577,
        )
        private val DIST_EXTRA = intArrayOf(
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
        )

        /** Fixed-Huffman literal/length code lengths (RFC 1951 §3.2.6). */
        private val FIXED_LITLEN_LEN = IntArray(288) { i ->
            when {
                i <= 143 -> 8
                i <= 255 -> 9
                i <= 279 -> 7
                else -> 8
            }
        }
        private val FIXED_LITLEN_CODE = canonicalCodes(FIXED_LITLEN_LEN)

        /** Fixed-Huffman distance codes: all 30 are 5 bits. */
        private val FIXED_DIST_LEN = IntArray(30) { 5 }
        private val FIXED_DIST_CODE = canonicalCodes(FIXED_DIST_LEN)

        /**
         * Length-limited Huffman code lengths for [freq], capped at [maxLen] bits.
         * Follows zlib `trees.c`: min-heap tree build, then the
         * overflow-redistribution of `gen_bitlen` (move a leaf down and rebalance,
         * preserving the Kraft equality), then re-assign lengths longest-first to the
         * least-frequent symbols. Returns 0 for unused symbols.
         */
        fun codeLengths(freq: IntArray, maxLen: Int): IntArray {
            val n = freq.size
            val lengths = IntArray(n)

            val symbols = ArrayList<Int>()
            for (i in 0 until n) if (freq[i] > 0) symbols.add(i)
            val m = symbols.size
            if (m == 0) return lengths
            if (m == 1) { lengths[symbols[0]] = 1; return lengths }

            // Node arrays: leaves 0..m-1, internal nodes m..(2m-2).
            val maxNodes = 2 * m
            val weight = LongArray(maxNodes)
            val depth = IntArray(maxNodes)
            val parent = IntArray(maxNodes) { -1 }
            for (i in 0 until m) { weight[i] = freq[symbols[i]].toLong(); depth[i] = 0 }
            var nodeCount = m

            // Min-heap of node ids keyed by (weight, depth). The depth tie-break matches
            // zlib and keeps the tree shallow, which minimises overflow.
            val heap = IntArray(maxNodes)
            var heapSize = 0
            fun less(a: Int, b: Int): Boolean =
                weight[a] < weight[b] || (weight[a] == weight[b] && depth[a] < depth[b])
            fun siftUp(start: Int) {
                var i = start
                while (i > 0) {
                    val p = (i - 1) / 2
                    if (less(heap[i], heap[p])) { val t = heap[i]; heap[i] = heap[p]; heap[p] = t; i = p }
                    else break
                }
            }
            fun siftDown(start: Int) {
                var i = start
                while (true) {
                    val l = 2 * i + 1; val r = 2 * i + 2; var s = i
                    if (l < heapSize && less(heap[l], heap[s])) s = l
                    if (r < heapSize && less(heap[r], heap[s])) s = r
                    if (s == i) break
                    val t = heap[i]; heap[i] = heap[s]; heap[s] = t; i = s
                }
            }
            for (i in 0 until m) { heap[heapSize++] = i; siftUp(heapSize - 1) }
            fun pop(): Int {
                val top = heap[0]
                heap[0] = heap[--heapSize]
                if (heapSize > 0) siftDown(0)
                return top
            }

            while (heapSize > 1) {
                val a = pop(); val b = pop()
                val node = nodeCount++
                weight[node] = weight[a] + weight[b]
                depth[node] = maxOf(depth[a], depth[b]) + 1
                parent[a] = node; parent[b] = node
                heap[heapSize++] = node; siftUp(heapSize - 1)
            }

            // Raw (unbounded) length per leaf = depth in the tree.
            val blCount = IntArray(maxLen + 2)
            var overflow = 0
            for (i in 0 until m) {
                var d = 0; var p = parent[i]
                while (p != -1) { d++; p = parent[p] }
                if (d > maxLen) { blCount[maxLen]++; overflow++ } else blCount[d]++
            }

            // zlib gen_bitlen overflow redistribution (keeps sum == m and Kraft == 1).
            if (overflow > 0) {
                do {
                    var bits = maxLen - 1
                    while (blCount[bits] == 0) bits--
                    blCount[bits]--
                    blCount[bits + 1] += 2
                    blCount[maxLen]--
                    overflow -= 2
                } while (overflow > 0)
            }

            // Assign the length multiset to symbols: longest codes to least-frequent.
            val order = symbols.indices.sortedWith(
                compareBy({ freq[symbols[it]] }, { symbols[it] }),
            )
            var oi = 0
            for (bits in maxLen downTo 1) {
                var c = blCount[bits]
                while (c > 0) { lengths[symbols[order[oi++]]] = bits; c-- }
            }
            return lengths
        }

        /** Assign canonical Huffman codes from code lengths (RFC 1951 §3.2.2). */
        fun canonicalCodes(lengths: IntArray): IntArray {
            val maxBits = 15
            val blCount = IntArray(maxBits + 1)
            for (l in lengths) if (l > 0) blCount[l]++
            val nextCode = IntArray(maxBits + 1)
            var code = 0
            for (bits in 1..maxBits) {
                code = (code + blCount[bits - 1]) shl 1
                nextCode[bits] = code
            }
            return IntArray(lengths.size) { sym ->
                val l = lengths[sym]
                if (l > 0) nextCode[l]++ else 0
            }
        }
    }
}
