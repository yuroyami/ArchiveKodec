package io.github.yuroyami.archivekodec.codec.lzma

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import io.github.yuroyami.archivekodec.util.readI32Le

/** Default properties for writing: lc=3, lp=0, pb=2, as xz and 7-Zip use. */
internal val DEFAULT_PROPERTIES = LzmaProperties(3, 0, 2)

/** Dictionary for writing: the input size rounded up to an LZMA2-encodable size, within 4 KiB..64 MiB. */
internal fun writerDictionarySize(dataSize: Int): Int {
    val wanted = dataSize.coerceIn(4096, 64 shl 20)
    var bits = 0
    while (lzma2DictionarySize(bits) < wanted) bits++
    return lzma2DictionarySize(bits).toInt()
}

/** The LZMA2 dictionary-size property: `(2 | (bits & 1)) << (bits / 2 + 11)`, or 4 GiB - 1 at 40. */
internal fun lzma2DictionarySize(bits: Int): Long =
    if (bits == 40) 0xFFFFFFFFL else (2L or (bits and 1).toLong()) shl (bits / 2 + 11)

internal fun lzma2DictionaryBits(size: Int): Int {
    var bits = 0
    while (lzma2DictionarySize(bits) < size.toLong()) bits++
    return bits
}

/**
 * The `.lzma` ("LZMA alone") format: a properties byte, a 32-bit dictionary size and a
 * 64-bit uncompressed size (all ones when unknown, which requires an end marker), then
 * one LZMA stream.
 */
internal object LzmaAlone {
    fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        if (input.size < 13) throw ArchiveFormatException("LZMA: truncated header", 0)
        val properties = LzmaProperties.of(input[0].toInt() and 0xFF, 0)
        val dictionary = maxOf(4096L, readI32Le(input, 1).toLong() and 0xFFFFFFFFL)
        val size = readI32Le(input, 5).toLong() and 0xFFFFFFFFL or (readI32Le(input, 9).toLong() shl 32)
        if (size < -1) throw ArchiveFormatException("LZMA: uncompressed size exceeds Long", 5)
        if (size >= 0) {
            limits.checkOutputSize(size)
            limits.checkRatio(input.size.toLong(), size)
        }
        val out = LzOutput(limits, input.size)
        val rc = RangeDecoder(input, 13, input.size)
        decodeLzma(rc, LzmaModel(properties), out, size, dictionary, markerAllowed = true)
        if (rc.position != input.size) throw ArchiveFormatException("LZMA: trailing bytes after stream", rc.position.toLong())
        return out.take()
    }

    fun compress(data: ByteArray): ByteArray {
        val dictionary = writerDictionarySize(data.size)
        val out = ByteArrayBuilder(maxOf(64, data.size / 3))
        out.append(DEFAULT_PROPERTIES.byte.toByte())
        for (i in 0 until 4) out.append((dictionary ushr (8 * i)).toByte())
        for (i in 0 until 8) out.append((data.size.toLong() ushr (8 * i)).toByte())
        val rc = RangeEncoder(out)
        LzmaEncoder(data, DEFAULT_PROPERTIES, dictionary).encode(rc, 0, data.size)
        rc.flush()
        return out.takeByteArray()
    }
}

/**
 * LZMA2: LZMA in chunks of at most 2 MiB uncompressed and 64 KiB compressed, plus
 * uncompressed chunks. Each chunk says whether the dictionary, the state and the
 * properties reset; the first chunk must reset the dictionary.
 */
internal object Lzma2 {
    private const val MAX_UNPACKED = 1 shl 21
    private const val MAX_PACKED = 1 shl 16

    /**
     * Decode one LZMA2 stream from input[[start] until [end]) into [out], ending at its
     * 0x00 control byte, and return the position after it.
     */
    fun decode(input: ByteArray, start: Int, end: Int, out: LzOutput, dictionarySize: Long): Int {
        var pos = start
        var model: LzmaModel? = null
        var needDictionaryReset = true
        var needProperties = true
        fun fail(message: String, at: Int = pos): Nothing = throw ArchiveFormatException("LZMA2: $message", at.toLong())
        fun be16(at: Int): Int = (input[at].toInt() and 0xFF shl 8) or (input[at + 1].toInt() and 0xFF)
        while (true) {
            if (pos >= end) fail("truncated stream: no end marker")
            val chunkStart = pos
            val control = input[pos++].toInt() and 0xFF
            if (control == 0) return pos
            if (control >= 0xE0 || control == 1) {
                needProperties = true
                needDictionaryReset = false
                out.dictionaryStart = out.size
            } else if (needDictionaryReset) {
                fail("first chunk does not reset the dictionary", chunkStart)
            }
            if (control < 0x80) {
                if (control > 2) fail("invalid control byte $control", chunkStart)
                if (end - pos < 2) fail("truncated chunk header", chunkStart)
                val size = be16(pos) + 1
                pos += 2
                if (end - pos < size) fail("truncated uncompressed chunk", chunkStart)
                out.put(input, pos, size)
                pos += size
                continue
            }
            val headerSize = if (control >= 0xC0) 5 else 4
            if (end - pos < headerSize) fail("truncated chunk header", chunkStart)
            val unpacked = ((control and 0x1F) shl 16) + be16(pos) + 1
            val packed = be16(pos + 2) + 1
            pos += 4
            if (control >= 0xC0) {
                val properties = LzmaProperties.of(input[pos].toInt() and 0xFF, pos.toLong())
                if (properties.lc + properties.lp > 4) fail("lc + lp exceeds 4")
                pos++
                needProperties = false
                model = model?.also { it.reset(properties) } ?: LzmaModel(properties)
            } else if (needProperties) {
                fail("LZMA chunk before any properties", chunkStart)
            } else if (control >= 0xA0) {
                model!!.reset()
            }
            if (end - pos < packed) fail("truncated LZMA chunk", chunkStart)
            val rc = RangeDecoder(input, pos, pos + packed)
            decodeLzma(rc, model!!, out, unpacked.toLong(), dictionarySize, markerAllowed = false)
            if (rc.position != pos + packed || !rc.isFinishedOk) fail("chunk does not end at its declared size", chunkStart)
            pos += packed
        }
    }

    fun decompress(input: ByteArray, limits: ArchiveLimits): ByteArray {
        val out = LzOutput(limits, input.size)
        val end = decode(input, 0, input.size, out, 0xFFFFFFFFL)
        if (end != input.size) throw ArchiveFormatException("LZMA2: trailing bytes after stream", end.toLong())
        return out.take()
    }

    /**
     * Encode [data] as LZMA2 chunks, keeping the dictionary across chunks. Each LZMA chunk
     * resets the state, so a chunk that does not shrink can be stored uncompressed instead.
     */
    fun encode(data: ByteArray, dictionarySize: Int, out: ByteArrayBuilder) {
        val encoder = LzmaEncoder(data, DEFAULT_PROPERTIES, dictionarySize)
        var pos = 0
        var propertiesSent = false
        var first = true
        while (pos < data.size) {
            encoder.model.reset()
            val packed = ByteArrayBuilder(MAX_PACKED)
            val rc = RangeEncoder(packed)
            val end = encoder.encode(rc, pos, minOf(data.size, pos + MAX_UNPACKED), MAX_PACKED - 256L)
            rc.flush()
            val unpacked = end - pos
            if (packed.size < unpacked && packed.size <= MAX_PACKED) {
                val reset = if (first) 3 else if (!propertiesSent) 2 else 1
                out.append((0x80 or (reset shl 5) or ((unpacked - 1) ushr 16)).toByte())
                out.append(((unpacked - 1) ushr 8).toByte()); out.append((unpacked - 1).toByte())
                out.append(((packed.size - 1) ushr 8).toByte()); out.append((packed.size - 1).toByte())
                if (reset >= 2) out.append(DEFAULT_PROPERTIES.byte.toByte())
                out.append(packed.takeByteArray())
                propertiesSent = true
            } else {
                var at = pos
                while (at < end) {
                    val size = minOf(MAX_PACKED, end - at)
                    out.append((if (first) 1 else 2).toByte())
                    out.append(((size - 1) ushr 8).toByte()); out.append((size - 1).toByte())
                    out.append(data, at, size)
                    at += size
                    first = false
                }
            }
            first = false
            pos = end
        }
        out.append(0)
    }

    fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayBuilder(maxOf(64, data.size / 3))
        encode(data, writerDictionarySize(data.size), out)
        return out.takeByteArray()
    }
}

/**
 * ZIP method 14 (APPNOTE 5.8.8): a two-byte LZMA SDK version, a two-byte properties
 * length (5), the properties byte and a 32-bit dictionary size, then the LZMA stream.
 * An end marker is optional, so the entry's declared size bounds the stream.
 */
internal object ZipLzma {
    fun decompress(input: ByteArray, limits: ArchiveLimits, size: Long): ByteArray {
        if (input.size < 9) throw ArchiveFormatException("ZIP LZMA: truncated header", 0)
        val propertiesLength = (input[2].toInt() and 0xFF) or (input[3].toInt() and 0xFF shl 8)
        if (propertiesLength != 5) throw ArchiveFormatException("ZIP LZMA: properties length $propertiesLength is not 5", 2)
        val properties = LzmaProperties.of(input[4].toInt() and 0xFF, 4)
        val dictionary = maxOf(4096L, readI32Le(input, 5).toLong() and 0xFFFFFFFFL)
        val out = LzOutput(limits, input.size)
        val rc = RangeDecoder(input, 9, input.size)
        decodeLzma(rc, LzmaModel(properties), out, size, dictionary, markerAllowed = true)
        if (rc.position != input.size) throw ArchiveFormatException("ZIP LZMA: trailing bytes after stream", rc.position.toLong())
        return out.take()
    }
}
