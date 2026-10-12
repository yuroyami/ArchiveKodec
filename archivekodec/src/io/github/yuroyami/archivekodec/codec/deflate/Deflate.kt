/* Altered Kotlin implementation; canonical Huffman coding follows zlib's trees.c. */

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

import kotlin.jvm.JvmStatic

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.codec.EncoderMatchTable
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

/**
 * Pure-Kotlin RFC 1951 DEFLATE *deflater*, the inverse of [Inflate].
 *
 * Pipeline: hash-chained LZ77 over a window of up to 32 KiB, greedy at levels 1 to 3 and
 * lazy at 4 to 9 with zlib's search limits (see [DeflateOptions]), cut into blocks of at most
 * 16,384 symbols (zlib's default). Each block is written as whichever of **stored**
 * (BTYPE=00), **fixed** (BTYPE=01) or **dynamic** (BTYPE=10) is smallest, so each part
 * of a mixed input gets its own codes and incompressible stretches are stored. Dynamic
 * trees use optimal binary package-merge lengths, capped at 15 bits (7 for the
 * code-length code). Symbol coverage, length bounds and Kraft equality are checked
 * before the tables can be selected.
 *
 * Working memory is bounded: the match chain is a ring over the 32 KiB window and one
 * block's symbols are buffered at a time, so beyond the output only a few hundred
 * kilobytes are used whatever the input size.
 *
 * Output is bare DEFLATE; the gzip/zlib framers wrap it. It decodes byte-for-byte
 * through [Inflate] and through stock zlib.
 *
 * This is a supported low-level public API for callers that need a raw RFC 1951
 * stream. Use [io.github.yuroyami.archivekodec.codec.Codecs.DEFLATE] when a
 * [io.github.yuroyami.archivekodec.codec.Codec] interface is more convenient.
 */
public object Deflate {

    /**
     * Compress [data] to a bare DEFLATE stream (no zlib/gzip wrapper).
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun encode(data: ByteArray): ByteArray = Deflater(data).encode()

    /**
     * Compress [data] to a bare DEFLATE stream with [options]: level, strategy, window and
     * preset dictionary. A dictionary-primed stream decodes only with the same dictionary,
     * as `Inflate.inflate(stream, limits, dictionary)` takes it.
     *
     * @throws io.github.yuroyami.archivekodec.ArchiveSizeException if the result cannot fit in one ByteArray.
     */
    @Throws(ArchiveException::class)
    @JvmStatic
    public fun encode(data: ByteArray, options: DeflateOptions): ByteArray = Deflater(data, options).encode()

    /**
     * Compress [data] to a Deflate64 stream. Deflate64 shares DEFLATE's codes except length
     * code 285, so capping matches at 257 bytes makes the DEFLATE output valid Deflate64.
     */
    internal fun encode64(data: ByteArray): ByteArray = Deflater(data, maxMatch = 257).encode()
}

private class Deflater(input: ByteArray, private val options: DeflateOptions = DeflateOptions.DEFAULT, private val maxMatch: Int = 258) {

    private val windowSize = 1 shl options.windowBits
    private val mask = windowSize - 1

    /** The window buffer: the dictionary's last [windowSize] bytes, then the input. */
    private val data: ByteArray
    private val start: Int

    init {
        val dictionary = options.dictionaryBytes
        start = if (dictionary == null) 0 else minOf(dictionary.size, windowSize)
        data = if (start == 0) input else ByteArray(start + input.size).also {
            dictionary!!.copyInto(it, 0, dictionary.size - start)
            input.copyInto(it, start)
        }
    }

    private val out = ByteArrayBuilder(maxOf(64, input.size / 3))
    private var bitBuf = 0
    private var bitCount = 0

    // One block's LZ77 tokens, packed one Int per token (see [MATCH_FLAG]).
    private val tokens = IntArray(BLOCK_TOKENS)
    private var tokenCount = 0

    // The current block's symbol frequencies and extra-bit total.
    private val llFreq = IntArray(286)   // literals 0..255, EOB 256, length codes 257..285
    private val dFreq = IntArray(30)     // distance codes
    private var extraBits = 0L
    private var blockStart = start       // first input byte of the current block
    private var emitted = start          // input covered by the tokens so far
    private val workspace = LengthLimitedHuffman.Workspace()

    // zlib's configuration for this level: a longer previous match than goodLength
    // quarters the search; lazy evaluation stops above maxLazy (for greedy levels,
    // longer matches are not inserted into the hash chains); niceLength ends a search.
    private val config = CONFIG[options.level]
    private val goodLength = config[0]
    private val maxLazy = config[1]
    private val niceLength = config[2]
    private val maxChain = config[3]

    @Throws(ArchiveException::class)
    fun encode(): ByteArray {
        when {
            options.level == 0 -> {
                writeStored(start, data.size, final = true)
                flushToByte()
                return out.toByteArray()
            }
            options.strategy == DeflateStrategy.HUFFMAN_ONLY -> for (i in start until data.size) {
                addLiteral(data[i].toInt() and 0xFF)
                flushIfFull()
            }
            options.strategy == DeflateStrategy.RLE -> rle()
            options.level <= 3 -> greedy()
            else -> lazy()
        }
        flushBlock(final = true)
        flushToByte()
        return out.toByteArray()
    }

    /** Write the buffered tokens, covering input [blockStart] until [emitted], as the smallest block type. */
    private fun flushBlock(final: Boolean) {
        val end = emitted
        llFreq[END_OF_BLOCK]++           // every block ends with one EOB
        // Build dynamic tables.
        val llLen = LengthLimitedHuffman.codeLengths(llFreq, MAX_BITS, workspace)
        val dLenRaw = LengthLimitedHuffman.codeLengths(dFreq, MAX_BITS, workspace)
        val dLen = if (dLenRaw.any { it > 0 }) dLenRaw else IntArray(30).also { it[0] = 1 }

        val hlit = maxIndex(llLen, minTop = END_OF_BLOCK) + 1   // >= 257
        val hdist = maxIndex(dLen, minTop = 0) + 1              // >= 1

        // Code-length code: RLE of (llLen[0..hlit) ++ dLen[0..hdist)).
        val clSeq = buildCodeLengthSequence(llLen, hlit, dLen, hdist)
        val clFreq = IntArray(19)
        for (i in clSeq.indices step 3) clFreq[clSeq[i]]++
        val clLen = LengthLimitedHuffman.codeLengths(clFreq, MAX_CL_BITS, workspace)
        var numCl = 19
        while (numCl > 4 && clLen[ORDER[numCl - 1]] == 0) numCl--

        // Pick the smallest of stored, fixed and dynamic.
        val dynamicBits = 3 + dynamicHeaderBits(numCl, clSeq, clLen) + symbolBits(llLen, dLen) + extraBits
        val fixedBits = 3 + fixedSymbolBits() + extraBits
        val storedBits = storedBits(end - blockStart)
        val finalBit = if (final) 1 else 0
        val dynamicAllowed = options.strategy != DeflateStrategy.FIXED
        when {
            storedBits < (if (dynamicAllowed) minOf(dynamicBits, fixedBits) else fixedBits) -> writeStored(blockStart, end, final)
            dynamicAllowed && dynamicBits < fixedBits -> {
                writeBits(finalBit, 1)
                writeBits(0b10, 2)
                writeDynamicHeader(hlit, hdist, numCl, clSeq, clLen)
                emitData(canonicalCodes(llLen), llLen, canonicalCodes(dLen), dLen)
            }
            else -> {
                writeBits(finalBit, 1)
                writeBits(0b01, 2)
                emitData(FIXED_LITLEN_CODE, FIXED_LITLEN_LEN, FIXED_DIST_CODE, FIXED_DIST_LEN)
            }
        }
        tokenCount = 0
        llFreq.fill(0)
        dFreq.fill(0)
        extraBits = 0
        blockStart = end
    }

    /** Bits to store [length] bytes: stored blocks hold at most 65,535 bytes after a byte-aligned header. */
    private fun storedBits(length: Int): Long {
        var bits = 0L
        var remaining = length
        var first = true
        do {
            val chunk = minOf(remaining, MAX_STORED)
            val pad = if (first) (8 - (bitCount + 3) % 8) % 8 else 5
            bits += 3 + pad + 32 + 8L * chunk
            remaining -= chunk
            first = false
        } while (remaining > 0)
        return bits
    }

    private fun writeStored(start: Int, end: Int, final: Boolean) {
        var position = start
        do {
            val chunk = minOf(end - position, MAX_STORED)
            val last = position + chunk == end
            writeBits(if (final && last) 1 else 0, 1)
            writeBits(0b00, 2)
            flushToByte()
            writeBits(chunk, 16)
            writeBits(chunk.inv() and 0xFFFF, 16)
            out.append(data, position, chunk)
            position += chunk
        } while (position < end)
    }

    /* ─── LZ77: gathers tokens + frequencies ───────────────────────────── */

    private val head = EncoderMatchTable(HASH_BITS, data.size)
    // Each position's predecessor in its hash chain. Only positions inside the window
    // are followed, and those slots have not been reused yet, so a ring suffices.
    private val prev = IntArray(minOf(data.size, windowSize).coerceAtLeast(1))
    private var matchStart = 0

    private fun hash(i: Int): Int {
        val a = data[i].toInt() and 0xFF
        val b = data[i + 1].toInt() and 0xFF
        val c = data[i + 2].toInt() and 0xFF
        return ((a shl 10) xor (b shl 5) xor c) and (HASH_SIZE - 1)
    }

    /** Insert position [i] into its hash chain and return the previous head, or -1. */
    private fun insert(i: Int): Int {
        if (i > data.size - MIN_MATCH) return -1
        val previous = head.put(hash(i), i)
        prev[i and mask] = previous
        return previous
    }

    /** Prime the chains with the dictionary, so the input can match it. */
    private fun primeDictionary() {
        for (i in 0 until start) insert(i)
    }

    /**
     * zlib's longest_match: follow the chain from [chainHead], starting from a best of
     * [previous] bytes, and return the best length found; [matchStart] marks it.
     */
    private fun longestMatch(position: Int, chainHead: Int, previous: Int): Int {
        var chainLength = if (previous >= goodLength) maxChain shr 2 else maxChain
        val maxLength = minOf(maxMatch, data.size - position)
        val nice = minOf(niceLength, maxLength)
        val oldest = position - windowSize
        var best = previous
        var candidate = chainHead
        while (candidate >= 0 && candidate >= oldest && chainLength-- > 0) {
            if (best < maxLength && data[candidate + best] == data[position + best] && data[candidate] == data[position]) {
                var length = 0
                while (length < maxLength && data[candidate + length] == data[position + length]) length++
                if (length > best) {
                    best = length
                    matchStart = candidate
                    if (length >= nice) break
                }
            }
            candidate = prev[candidate and mask]
        }
        return best
    }

    private fun flushIfFull() {
        if (tokenCount == BLOCK_TOKENS) flushBlock(final = false)
    }

    /** Levels 1 to 3: take each match as found (zlib's deflate_fast). */
    private fun greedy() {
        primeDictionary()
        val n = data.size
        var position = start
        while (position < n) {
            val chainHead = insert(position)
            var length = 0
            if (chainHead >= 0 && position - chainHead <= windowSize) length = longestMatch(position, chainHead, MIN_MATCH - 1)
            if (length >= MIN_MATCH) {
                addMatch(length, position - matchStart)
                if (length <= maxLazy) {
                    for (i in position + 1 until position + length) insert(i)
                }
                position += length
            } else {
                addLiteral(data[position].toInt() and 0xFF)
                position++
            }
            flushIfFull()
        }
    }

    /** Levels 4 to 9: defer each match by one byte in case the next is longer (zlib's deflate_slow). */
    private fun lazy() {
        primeDictionary()
        val n = data.size
        var position = start
        var matchAvailable = false
        var matchLength = MIN_MATCH - 1
        while (position < n) {
            val chainHead = insert(position)
            val previousLength = matchLength
            val previousMatch = matchStart
            matchLength = MIN_MATCH - 1
            if (chainHead >= 0 && previousLength < maxLazy && position - chainHead <= windowSize) {
                matchLength = longestMatch(position, chainHead, previousLength)
                if (matchLength <= 5 && (options.strategy == DeflateStrategy.FILTERED ||
                        (matchLength == MIN_MATCH && position - matchStart > TOO_FAR))) {
                    matchLength = MIN_MATCH - 1
                }
            }
            if (previousLength >= MIN_MATCH && matchLength <= previousLength) {
                addMatch(previousLength, position - 1 - previousMatch)
                for (i in position + 1 until position + previousLength - 1) insert(i)
                matchAvailable = false
                matchLength = MIN_MATCH - 1
                position += previousLength - 1
            } else if (matchAvailable) {
                addLiteral(data[position - 1].toInt() and 0xFF)
                position++
            } else {
                matchAvailable = true
                position++
            }
            // A deferred literal is not yet a token, so the block ends before it.
            flushIfFull()
        }
        if (matchAvailable) addLiteral(data[position - 1].toInt() and 0xFF)
    }

    /** Runs only: matches at distance one (zlib's deflate_rle). */
    private fun rle() {
        val n = data.size
        var position = start
        while (position < n) {
            var length = 0
            if (position > 0) {
                val previous = data[position - 1]
                val maxLength = minOf(maxMatch, n - position)
                while (length < maxLength && data[position + length] == previous) length++
            }
            if (length >= MIN_MATCH) {
                addMatch(length, 1)
                position += length
            } else {
                addLiteral(data[position].toInt() and 0xFF)
                position++
            }
            flushIfFull()
        }
    }

    private fun addLiteral(v: Int) {
        appendToken(v)
        llFreq[v]++
        emitted++
    }

    private fun addMatch(length: Int, distance: Int) {
        appendToken(MATCH_FLAG or (length shl 16) or distance)
        val lc = lengthCode(length)
        val dc = distCode(distance)
        llFreq[END_OF_BLOCK + 1 + lc]++  // 257 + lc
        dFreq[dc]++
        extraBits += LENGTH_EXTRA[lc] + DIST_EXTRA[dc]
        emitted += length
    }

    private fun appendToken(t: Int) {
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
        private const val MIN_MATCH = 3
        private const val MAX_MATCH = 258
        private const val HASH_BITS = 15
        private const val HASH_SIZE = 1 shl HASH_BITS
        private const val TOO_FAR = 4096

        /** zlib's configuration_table: good_length, max_lazy, nice_length, max_chain per level. */
        private val CONFIG = arrayOf(
            intArrayOf(0, 0, 0, 0),
            intArrayOf(4, 4, 8, 4), intArrayOf(4, 5, 16, 8), intArrayOf(4, 6, 32, 32),
            intArrayOf(4, 4, 16, 16), intArrayOf(8, 16, 32, 32), intArrayOf(8, 16, 128, 128),
            intArrayOf(8, 32, 128, 256), intArrayOf(32, 128, 258, 1024), intArrayOf(32, 258, 258, 4096),
        )
        private const val BLOCK_TOKENS = 16_384
        private const val MAX_STORED = 65_535

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

        /** Assign canonical Huffman codes from code lengths (RFC 1951 §3.2.2). */
        fun canonicalCodes(lengths: IntArray): IntArray {
            val maxBits = 15
            val blCount = IntArray(maxBits + 1)
            for (length in lengths) {
                check(length in 0..maxBits) { "invalid canonical Huffman length" }
                if (length > 0) blCount[length]++
            }
            var slots = 1
            for (bits in 1..maxBits) {
                slots = (slots shl 1) - blCount[bits]
                check(slots >= 0) { "oversubscribed canonical Huffman tree" }
            }
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
