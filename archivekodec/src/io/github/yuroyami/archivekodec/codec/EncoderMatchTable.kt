package io.github.yuroyami.archivekodec.codec

/**
 * Latest absolute input position for each original encoder hash. Small inputs
 * pack a 16-bit hash and a relative position into each slot, at most half full;
 * larger inputs retain direct indexing. Keeping the full hash preserves matches.
 * Callers insert at most [inputLength] positions within the input slice and use
 * hash keys in `0 until (1 shl hashBits)`.
 */
internal class EncoderMatchTable(hashBits: Int, inputLength: Int, private val inputBase: Int = 0) {
    init {
        require(hashBits in 1..16)
        require(inputLength >= 0 && inputBase >= 0 && inputLength <= Int.MAX_VALUE - inputBase)
    }

    private val fullSize = 1 shl hashBits
    private val compact = inputLength <= fullSize / 4
    private val entries = if (compact) {
        var size = 2
        while (size < inputLength * 2) size = size shl 1
        IntArray(size)
    } else IntArray(fullSize) { -1 }
    private val mask = entries.size - 1
    private val shift = entries.size.countLeadingZeroBits() + 1

    operator fun get(hash: Int): Int {
        if (!compact) return entries[hash]
        return position(entries[slot(hash)])
    }

    /** Store [inputPosition] and return the preceding position, or -1 when absent. */
    fun put(hash: Int, inputPosition: Int): Int {
        if (!compact) {
            val previous = entries[hash]
            entries[hash] = inputPosition
            return previous
        }
        val index = slot(hash)
        val previous = position(entries[index])
        entries[index] = ((inputPosition - inputBase + 1) shl 16) or hash
        return previous
    }

    private fun position(packed: Int): Int =
        if (packed == 0) -1 else inputBase + (packed ushr 16) - 1

    private fun slot(hash: Int): Int {
        var index = (hash * -1640531535) ushr shift
        while (entries[index] != 0 && (entries[index] and 0xFFFF) != hash) {
            index = (index + 1) and mask
        }
        return index
    }
}
