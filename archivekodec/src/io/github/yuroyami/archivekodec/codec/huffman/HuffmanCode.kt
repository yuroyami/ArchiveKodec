package io.github.yuroyami.archivekodec.codec.huffman

import io.github.yuroyami.archivekodec.codec.deflate.LengthLimitedHuffman
import kotlin.jvm.JvmStatic

/**
 * Length-limited canonical Huffman codes, as DEFLATE (RFC 1951, 3.2.2) and the lossless WebP
 * format build them. The DEFLATE, bzip2 and brotli encoders of ArchiveKodec use the same builder.
 *
 * ```kotlin
 * val lengths = HuffmanCode.lengths(frequencies, maxLength = 15)
 * val codes = HuffmanCode.canonicalCodes(lengths)   // code i has lengths[i] bits, most significant bit first
 * ```
 *
 * Both calls hold no state, so threads can share them.
 */
public object HuffmanCode {

    /**
     * The code length of each symbol of [frequencies], none longer than [maxLength]. The lengths
     * are optimal for that limit (the package-merge method). An unused symbol gets 0. A single used
     * symbol gets 1. For two or more used symbols, the lengths fill the code space exactly.
     *
     * @throws IllegalArgumentException if [maxLength] is outside 1 to 15, a frequency is negative,
     *   or more symbols are used than `2^maxLength` codes can hold.
     */
    @Throws(IllegalArgumentException::class)
    @JvmStatic
    public fun lengths(frequencies: IntArray, maxLength: Int): IntArray =
        LengthLimitedHuffman.codeLengths(frequencies, maxLength)

    /**
     * The canonical code of each symbol, from its length in [lengths] (RFC 1951, 3.2.2): shorter
     * codes come first, and codes of one length follow the symbol order. A symbol of length 0 gets 0.
     *
     * @throws IllegalArgumentException if a length is outside 0 to 15, or the lengths oversubscribe the code space.
     */
    @Throws(IllegalArgumentException::class)
    @JvmStatic
    public fun canonicalCodes(lengths: IntArray): IntArray {
        val counts = IntArray(MAX_LENGTH + 1)
        for (l in lengths) {
            require(l in 0..MAX_LENGTH) { "Huffman code length $l is outside 0 to $MAX_LENGTH" }
            if (l > 0) counts[l]++
        }
        var space = 1L shl MAX_LENGTH
        for (l in 1..MAX_LENGTH) space -= counts[l].toLong() shl (MAX_LENGTH - l)
        require(space >= 0) { "Huffman code lengths oversubscribe the code space" }
        val next = IntArray(MAX_LENGTH + 1)
        var code = 0
        for (bits in 1..MAX_LENGTH) {
            code = (code + counts[bits - 1]) shl 1
            next[bits] = code
        }
        next[0] = 0
        return IntArray(lengths.size) { symbol ->
            val l = lengths[symbol]
            if (l > 0) next[l]++ else 0
        }
    }

    private const val MAX_LENGTH = 15
}
