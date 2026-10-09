package io.github.yuroyami.archivekodec.codec.lzma

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.byteArrayCapacity

internal const val NUM_STATES = 12
internal const val POS_STATES_MAX = 16
internal const val MATCH_MIN_LENGTH = 2
internal const val LEN_TO_POS_STATES = 4
internal const val END_POS_MODEL_INDEX = 14
internal const val FULL_DISTANCES = 128
internal const val ALIGN_BITS = 4

/** lc, lp and pb from an LZMA properties byte (`(pb * 5 + lp) * 9 + lc`). */
internal class LzmaProperties(val lc: Int, val lp: Int, val pb: Int) {
    val byte: Int get() = (pb * 5 + lp) * 9 + lc

    companion object {
        fun of(value: Int, offset: Long): LzmaProperties {
            if (value !in 0 until 9 * 5 * 5) throw ArchiveFormatException("LZMA: invalid properties byte $value", offset)
            return LzmaProperties(value % 9, value / 9 % 5, value / 45)
        }
    }
}

/** A length coder: choice bits, then 3-bit low and mid trees per position state, or an 8-bit high tree. */
internal class LengthModel {
    val choice = probabilities(2)
    val low = probabilities(POS_STATES_MAX shl 3)
    val mid = probabilities(POS_STATES_MAX shl 3)
    val high = probabilities(256)

    fun reset() {
        choice.fill(PROB_INIT); low.fill(PROB_INIT); mid.fill(PROB_INIT); high.fill(PROB_INIT)
    }

    fun decode(rc: RangeDecoder, posState: Int): Int = when {
        rc.bit(choice, 0) == 0 -> rc.bitTree(low, posState shl 3, 3)
        rc.bit(choice, 1) == 0 -> 8 + rc.bitTree(mid, posState shl 3, 3)
        else -> 16 + rc.bitTree(high, 0, 8)
    }

    fun encode(rc: RangeEncoder, length: Int, posState: Int) {
        when {
            length < 8 -> { rc.bit(choice, 0, 0); rc.bitTree(low, posState shl 3, 3, length) }
            length < 16 -> { rc.bit(choice, 0, 1); rc.bit(choice, 1, 0); rc.bitTree(mid, posState shl 3, 3, length - 8) }
            else -> { rc.bit(choice, 0, 1); rc.bit(choice, 1, 1); rc.bitTree(high, 0, 8, length - 16) }
        }
    }
}

/**
 * The adaptive model of an LZMA stream: every probability, the 12-state machine and the
 * four repeated distances. LZMA2 keeps one across chunks and resets it on demand.
 */
internal class LzmaModel(properties: LzmaProperties) {
    var properties = properties
        private set
    var literals = probabilities(0x300 shl (properties.lc + properties.lp))
        private set
    val isMatch = probabilities(NUM_STATES shl 4)
    val isRep = probabilities(NUM_STATES)
    val isRepG0 = probabilities(NUM_STATES)
    val isRepG1 = probabilities(NUM_STATES)
    val isRepG2 = probabilities(NUM_STATES)
    val isRep0Long = probabilities(NUM_STATES shl 4)
    val posSlot = probabilities(LEN_TO_POS_STATES shl 6)
    val posSpecial = probabilities(1 + FULL_DISTANCES - END_POS_MODEL_INDEX)
    val align = probabilities(1 shl ALIGN_BITS)
    val length = LengthModel()
    val repLength = LengthModel()
    var state = 0
    var rep0 = 0
    var rep1 = 0
    var rep2 = 0
    var rep3 = 0

    fun reset(newProperties: LzmaProperties = properties) {
        if (newProperties.lc + newProperties.lp != properties.lc + properties.lp) {
            literals = probabilities(0x300 shl (newProperties.lc + newProperties.lp))
        } else literals.fill(PROB_INIT)
        properties = newProperties
        for (p in listOf(isMatch, isRep, isRepG0, isRepG1, isRepG2, isRep0Long, posSlot, posSpecial, align)) p.fill(PROB_INIT)
        length.reset(); repLength.reset()
        state = 0; rep0 = 0; rep1 = 0; rep2 = 0; rep3 = 0
    }
}

internal fun stateAfterLiteral(state: Int): Int = if (state < 4) 0 else if (state < 10) state - 3 else state - 6
internal fun stateAfterMatch(state: Int): Int = if (state < 7) 7 else 10
internal fun stateAfterRep(state: Int): Int = if (state < 7) 8 else 11
internal fun stateAfterShortRep(state: Int): Int = if (state < 7) 9 else 11

/**
 * The decoded output, which is also the dictionary. Growth is bounded by the caller's
 * limits; [dictionaryStart] marks the last LZMA2 dictionary reset, before which no match
 * may reach.
 */
internal class LzOutput(private val limits: ArchiveLimits, private val compressedSize: Int) {
    var bytes = ByteArray(256)
        private set
    var size = 0
        private set
    var dictionaryStart = 0
    private val maximum: Int = minOf(
        Int.MAX_VALUE.toLong(), limits.maxUncompressedBytes,
        if (compressedSize > 0) compressedSize.toLong() * limits.maxCompressionRatio else Long.MAX_VALUE,
    ).toInt()

    init {
        limits.checkOutputSize(0)
        limits.checkRatio(compressedSize.toLong(), 0)
    }

    private fun ensure(extra: Int) {
        val needed = size.toLong() + extra
        if (needed > maximum) {
            limits.checkOutputSize(needed)
            limits.checkRatio(compressedSize.toLong(), needed)
        }
        if (needed > bytes.size) bytes = bytes.copyOf(byteArrayCapacity(bytes.size, size, extra, maximum))
    }

    fun put(value: Int) {
        if (size == bytes.size || size >= maximum) ensure(1)
        bytes[size++] = value.toByte()
    }

    fun put(source: ByteArray, offset: Int, length: Int) {
        ensure(length)
        source.copyInto(bytes, size, offset, offset + length)
        size += length
    }

    /** The byte [distance] + 1 positions back. */
    fun back(distance: Int): Int = bytes[size - distance - 1].toInt() and 0xFF

    fun hasDistance(distance: Int): Boolean = distance >= 0 && distance < size - dictionaryStart

    /** Copy [length] bytes from [distance] + 1 back, overlapping forward as LZ77 requires. */
    fun copy(distance: Int, length: Int) {
        ensure(length)
        var from = size - distance - 1
        if (distance + 1 >= length) {
            bytes.copyInto(bytes, size, from, from + length)
            size += length
        } else {
            repeat(length) { bytes[size++] = bytes[from++] }
        }
    }

    fun take(): ByteArray = if (size == bytes.size) bytes else bytes.copyOf(size)
}

/**
 * Decode LZMA symbols from [rc] with [model] into [out]. With [unpackSize] of -1 the
 * stream must end with an end marker; otherwise exactly [unpackSize] bytes are decoded
 * and an end marker is accepted only where [markerAllowed]. Distances may not reach
 * [dictionarySize] or past the last dictionary reset. Returns true if an end marker ended it.
 */
internal fun decodeLzma(
    rc: RangeDecoder, model: LzmaModel, out: LzOutput, unpackSize: Long, dictionarySize: Long, markerAllowed: Boolean,
): Boolean {
    val props = model.properties
    val pbMask = (1 shl props.pb) - 1
    val lpMask = (1 shl props.lp) - 1
    var remaining = unpackSize
    fun fail(message: String): Nothing = throw ArchiveFormatException("LZMA: $message", rc.position.toLong())
    while (true) {
        if (remaining == 0L) {
            if (!markerAllowed) return false
            // An optional end marker may follow; a finished range coder means it is absent.
            if (rc.isFinishedOk) return false
        }
        val position = out.size - out.dictionaryStart
        val posState = position and pbMask
        val state = model.state
        if (rc.bit(model.isMatch, (state shl 4) + posState) == 0) {
            if (remaining == 0L) fail("data continues past the declared size")
            val previous = if (out.size > out.dictionaryStart) out.back(0) else 0
            val base = 0x300 * (((position and lpMask) shl props.lc) + (previous ushr (8 - props.lc)))
            val literals = model.literals
            var symbol = 1
            if (state >= 7) {
                var matchByte = out.back(model.rep0)
                do {
                    val matchBit = (matchByte ushr 7) and 1
                    matchByte = matchByte shl 1
                    val bit = rc.bit(literals, base + ((1 + matchBit) shl 8) + symbol)
                    symbol = symbol shl 1 or bit
                    if (matchBit != bit) break
                } while (symbol < 0x100)
            }
            while (symbol < 0x100) symbol = symbol shl 1 or rc.bit(literals, base + symbol)
            out.put(symbol - 0x100)
            model.state = stateAfterLiteral(state)
            if (remaining > 0) remaining--
            continue
        }
        var length: Int
        if (rc.bit(model.isRep, state) != 0) {
            if (remaining == 0L) fail("data continues past the declared size")
            if (out.size == out.dictionaryStart) fail("repeated match before any output")
            if (rc.bit(model.isRepG0, state) == 0) {
                if (rc.bit(model.isRep0Long, (state shl 4) + posState) == 0) {
                    model.state = stateAfterShortRep(state)
                    out.put(out.back(model.rep0))
                    if (remaining > 0) remaining--
                    continue
                }
            } else {
                val distance: Int
                if (rc.bit(model.isRepG1, state) == 0) {
                    distance = model.rep1
                } else {
                    if (rc.bit(model.isRepG2, state) == 0) {
                        distance = model.rep2
                    } else {
                        distance = model.rep3
                        model.rep3 = model.rep2
                    }
                    model.rep2 = model.rep1
                }
                model.rep1 = model.rep0
                model.rep0 = distance
            }
            length = model.repLength.decode(rc, posState)
            model.state = stateAfterRep(state)
        } else {
            model.rep3 = model.rep2; model.rep2 = model.rep1; model.rep1 = model.rep0
            length = model.length.decode(rc, posState)
            model.state = stateAfterMatch(state)
            model.rep0 = decodeDistance(rc, model, length)
            if (model.rep0 == -1) {
                if (!markerAllowed && unpackSize >= 0) fail("unexpected end marker")
                if (!rc.isFinishedOk) fail("data after the end marker")
                if (unpackSize >= 0 && remaining != 0L) fail("end marker before the declared size")
                return true
            }
            if (remaining == 0L) fail("data continues past the declared size")
            if (Integer2.uLong(model.rep0) >= dictionarySize || !out.hasDistance(model.rep0)) fail("match distance ${Integer2.uLong(model.rep0) + 1} is too far back")
        }
        length += MATCH_MIN_LENGTH
        if (remaining >= 0 && remaining < length) fail("match runs past the declared size")
        out.copy(model.rep0, length)
        if (remaining > 0) remaining -= length
    }
}

private fun decodeDistance(rc: RangeDecoder, model: LzmaModel, length: Int): Int {
    val lenState = minOf(length, LEN_TO_POS_STATES - 1)
    val slot = rc.bitTree(model.posSlot, lenState shl 6, 6)
    if (slot < 4) return slot
    val directBits = (slot ushr 1) - 1
    var distance = (2 or (slot and 1)) shl directBits
    if (slot < END_POS_MODEL_INDEX) {
        distance += rc.reverseBitTree(model.posSpecial, distance - slot, directBits)
    } else {
        distance += rc.directBits(directBits - ALIGN_BITS) shl ALIGN_BITS
        distance += rc.reverseBitTree(model.align, 0, ALIGN_BITS)
    }
    return distance
}

private object Integer2 {
    fun uLong(value: Int): Long = value.toLong() and 0xFFFFFFFFL
}
