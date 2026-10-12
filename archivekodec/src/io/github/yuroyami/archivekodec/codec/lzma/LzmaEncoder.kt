package io.github.yuroyami.archivekodec.codec.lzma

import io.github.yuroyami.archivekodec.util.ByteArrayBuilder

internal const val MATCH_MAX_LENGTH = 273

/**
 * LZMA encoder over a whole input array: a hash-chain match finder, repeated-distance
 * and short-rep checks, and one step of lazy matching. It is not an optimal parser, so
 * it compresses somewhat worse than `xz -6`, but every stream it writes is standard LZMA.
 * The dictionary is the whole input before the current position, limited to [dictionarySize].
 */
internal class LzmaEncoder(
    private val data: ByteArray,
    private val properties: LzmaProperties,
    private val dictionarySize: Int,
) {
    val model = LzmaModel(properties)
    private val head = IntArray(HASH_SIZE) { -1 }
    private val chain = IntArray(data.size)
    private var inserted = 0
    private var matchDistance = 0

    private fun hash(p: Int): Int {
        val v = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8) or ((data[p + 2].toInt() and 0xFF) shl 16)
        return (v * -1640531535) ushr (32 - HASH_BITS)
    }

    private fun insertUpTo(position: Int) {
        while (inserted < position) {
            if (inserted + 2 < data.size) {
                val h = hash(inserted)
                chain[inserted] = head[h]
                head[h] = inserted
            }
            inserted++
        }
    }

    private fun matchLength(a: Int, b: Int, limit: Int): Int {
        var n = 0
        while (n < limit && data[a + n] == data[b + n]) n++
        return n
    }

    /** Longest match at [p] within [limit] bytes; its distance minus one is left in [matchDistance]. */
    private fun findMatch(p: Int, limit: Int): Int {
        insertUpTo(p)
        if (limit < MATCH_MIN_LENGTH || p + 2 >= data.size) return 0
        var best = 0
        var candidate = head[hash(p)]
        var depth = CHAIN_DEPTH
        val oldest = maxOf(0, p - dictionarySize)
        while (candidate >= oldest && depth-- > 0) {
            if (data[candidate + best] == data[p + best]) {
                val length = matchLength(candidate, p, limit)
                if (length > best) {
                    best = length
                    matchDistance = p - candidate - 1
                    if (length == limit) break
                }
            }
            candidate = chain[candidate]
        }
        // A length-2 match only pays off at a short distance.
        return if (best == 2 && matchDistance >= 128) 0 else best
    }

    /**
     * Encode data[[start] until [end]) with [rc]. Stops early, at a symbol boundary,
     * once the encoded size would pass [maxPacked]; returns the position reached.
     */
    fun encode(rc: RangeEncoder, start: Int, end: Int, maxPacked: Long = Long.MAX_VALUE): Int {
        val pbMask = (1 shl properties.pb) - 1
        var p = start
        while (p < end && rc.pendingSize < maxPacked) {
            val limit = minOf(MATCH_MAX_LENGTH, end - p)
            val posState = p and pbMask
            // Repeated distances first: they cost far fewer bits than a new distance.
            var repLength = 0
            var repIndex = 0
            for (i in 0 until 4) {
                val distance = rep(i)
                if (distance >= p) continue
                val length = matchLength(p - distance - 1, p, limit)
                if (length > repLength) { repLength = length; repIndex = i }
            }
            val mainLength = findMatch(p, limit)
            val mainDistance = matchDistance
            when {
                repLength >= MATCH_MIN_LENGTH && repLength + 1 >= mainLength -> {
                    encodeRep(rc, repIndex, repLength, posState)
                    p += repLength
                }
                mainLength >= MATCH_MIN_LENGTH && !(p + 1 < end && findMatch(p + 1, minOf(MATCH_MAX_LENGTH, end - p - 1)) > mainLength + 1) -> {
                    encodeMatch(rc, mainDistance, mainLength, posState)
                    p += mainLength
                }
                p > model.rep0 && data[p] == data[p - model.rep0 - 1] && model.state >= 7 -> {
                    encodeShortRep(rc, posState)
                    p++
                }
                else -> {
                    encodeLiteral(rc, p, posState)
                    p++
                }
            }
        }
        return p
    }

    private fun rep(i: Int): Int = when (i) { 0 -> model.rep0; 1 -> model.rep1; 2 -> model.rep2; else -> model.rep3 }

    private fun encodeLiteral(rc: RangeEncoder, p: Int, posState: Int) {
        val state = model.state
        rc.bit(model.isMatch, (state shl 4) + posState, 0)
        val previous = if (p > 0) data[p - 1].toInt() and 0xFF else 0
        val lpMask = (1 shl properties.lp) - 1
        val base = 0x300 * (((p and lpMask) shl properties.lc) + (previous ushr (8 - properties.lc)))
        val value = data[p].toInt() and 0xFF
        val literals = model.literals
        var m = 1
        if (state >= 7) {
            val matchByte = data[p - model.rep0 - 1].toInt() and 0xFF
            var matched = true
            for (i in 7 downTo 0) {
                val bit = (value ushr i) and 1
                if (matched) {
                    val matchBit = (matchByte ushr i) and 1
                    rc.bit(literals, base + ((1 + matchBit) shl 8) + m, bit)
                    matched = matchBit == bit
                } else {
                    rc.bit(literals, base + m, bit)
                }
                m = m shl 1 or bit
            }
        } else {
            rc.bitTree(literals, base, 8, value)
        }
        model.state = stateAfterLiteral(state)
    }

    private fun encodeMatch(rc: RangeEncoder, distance: Int, length: Int, posState: Int) {
        val state = model.state
        rc.bit(model.isMatch, (state shl 4) + posState, 1)
        rc.bit(model.isRep, state, 0)
        model.length.encode(rc, length - MATCH_MIN_LENGTH, posState)
        encodeDistance(rc, distance, length - MATCH_MIN_LENGTH)
        model.rep3 = model.rep2; model.rep2 = model.rep1; model.rep1 = model.rep0; model.rep0 = distance
        model.state = stateAfterMatch(state)
    }

    /** The end-of-stream marker: a match at distance 0xFFFFFFFF. */
    fun encodeEndMarker(rc: RangeEncoder, position: Int) {
        val state = model.state
        val posState = position and ((1 shl properties.pb) - 1)
        rc.bit(model.isMatch, (state shl 4) + posState, 1)
        rc.bit(model.isRep, state, 0)
        model.length.encode(rc, 0, posState)
        encodeDistance(rc, -1, 0)
    }

    private fun encodeDistance(rc: RangeEncoder, distance: Int, length: Int) {
        val slot = positionSlot(distance)
        rc.bitTree(model.posSlot, minOf(length, LEN_TO_POS_STATES - 1) shl 6, 6, slot)
        if (slot < 4) return
        val footerBits = (slot ushr 1) - 1
        val base = (2 or (slot and 1)) shl footerBits
        val reduced = distance - base
        if (slot < END_POS_MODEL_INDEX) {
            rc.reverseBitTree(model.posSpecial, base - slot, footerBits, reduced)
        } else {
            rc.directBits(reduced ushr ALIGN_BITS, footerBits - ALIGN_BITS)
            rc.reverseBitTree(model.align, 0, ALIGN_BITS, reduced and ((1 shl ALIGN_BITS) - 1))
        }
    }

    private fun encodeRep(rc: RangeEncoder, index: Int, length: Int, posState: Int) {
        val state = model.state
        rc.bit(model.isMatch, (state shl 4) + posState, 1)
        rc.bit(model.isRep, state, 1)
        if (index == 0) {
            rc.bit(model.isRepG0, state, 0)
            rc.bit(model.isRep0Long, (state shl 4) + posState, 1)
        } else {
            rc.bit(model.isRepG0, state, 1)
            val distance = rep(index)
            if (index == 1) {
                rc.bit(model.isRepG1, state, 0)
            } else {
                rc.bit(model.isRepG1, state, 1)
                rc.bit(model.isRepG2, state, index - 2)
                if (index == 3) model.rep3 = model.rep2
                model.rep2 = model.rep1
            }
            model.rep1 = model.rep0
            model.rep0 = distance
        }
        model.repLength.encode(rc, length - MATCH_MIN_LENGTH, posState)
        model.state = stateAfterRep(state)
    }

    private fun encodeShortRep(rc: RangeEncoder, posState: Int) {
        val state = model.state
        rc.bit(model.isMatch, (state shl 4) + posState, 1)
        rc.bit(model.isRep, state, 1)
        rc.bit(model.isRepG0, state, 0)
        rc.bit(model.isRep0Long, (state shl 4) + posState, 0)
        model.state = stateAfterShortRep(state)
    }

    companion object {
        private const val HASH_BITS = 17
        private const val HASH_SIZE = 1 shl HASH_BITS
        private const val CHAIN_DEPTH = 48

        /** The distance slot of an unsigned distance: two per power of two above 4. */
        fun positionSlot(distance: Int): Int {
            if (distance in 0..3) return distance
            val n = 31 - distance.countLeadingZeroBits()
            return (n shl 1) or ((distance ushr (n - 1)) and 1)
        }

        fun newBuilder(capacity: Int) = ByteArrayBuilder(maxOf(64, capacity))
    }
}
