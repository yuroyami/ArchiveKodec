package io.github.yuroyami.archivekodec.codec.huffcdic

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.jvm.JvmOverloads

/**
 * The HUFF/CDIC decoder of MOBI text records (compression type 17480).
 *
 * A book that uses it stores one HUFF record and one or more CDIC records after its text. The
 * HUFF record holds a canonical Huffman code of up to 32 bits. Each code picks a phrase from the
 * CDIC records, and a phrase can itself be HUFF/CDIC data, which is decoded once and kept.
 *
 * ```kotlin
 * val huffCdic = HuffCdic(huffRecord, cdicRecords)
 * val text = huffCdic.decode(textRecord, 0, textRecord.size - trailingEntries)
 * ```
 *
 * An instance keeps the phrases it has decoded, so use it from one thread at a time.
 *
 * @param huff the HUFF record, which starts with `HUFF`.
 * @param cdics the CDIC records in book order, each starting with `CDIC`.
 * @param limits the output ceiling of one [decode] and of one phrase.
 * @throws ArchiveFormatException if a record has the wrong signature or its tables are cut short or invalid.
 */
public class HuffCdic @Throws(ArchiveFormatException::class) @JvmOverloads public constructor(
    huff: ByteArray,
    cdics: List<ByteArray>,
    private val limits: ArchiveLimits = ArchiveLimits.DEFAULT,
) {
    private val codeLength = IntArray(256)
    private val terminal = BooleanArray(256)
    private val maxCode1 = LongArray(256)
    private val minCode = LongArray(33)
    private val maxCode = LongArray(33)
    private val phrases = ArrayList<ByteArray?>()
    private val plain = ArrayList<Boolean>()

    init {
        if (huff.size < 24 || !huff.startsWith("HUFF")) throw ArchiveFormatException("huffcdic: no HUFF signature", 0)
        val table1 = s32(huff, 8)
        val table2 = s32(huff, 12)
        if (table1 < 0 || table1 > huff.size - 1024 || table2 < 0 || table2 > huff.size - 256) {
            throw ArchiveFormatException("huffcdic: the HUFF tables pass the end of the record", 8)
        }
        for (i in 0 until 256) {
            val v = u32(huff, table1 + i * 4)
            codeLength[i] = (v and 0x1F).toInt()
            terminal[i] = v and 0x80 != 0L
            if (codeLength[i] == 0 || (codeLength[i] <= 8 && !terminal[i])) {
                throw ArchiveFormatException("huffcdic: entry $i of the HUFF code table is invalid", (table1 + i * 4).toLong())
            }
            maxCode1[i] = (((v ushr 8) + 1) shl (32 - codeLength[i])) - 1
        }
        for (len in 1..32) {
            minCode[len] = u32(huff, table2 + (len - 1) * 8) shl (32 - len)
            maxCode[len] = ((u32(huff, table2 + (len - 1) * 8 + 4) + 1) shl (32 - len)) - 1
        }
        for ((k, cdic) in cdics.withIndex()) {
            if (cdic.size < 16 || !cdic.startsWith("CDIC")) throw ArchiveFormatException("huffcdic: CDIC record $k has no CDIC signature", 0)
            val total = s32(cdic, 8)
            val bits = s32(cdic, 12).coerceIn(0, 30)
            val count = minOf(1 shl bits, total - phrases.size)
            // As Mobipocket readers do, an offset table cut short gives fewer phrases, and a phrase
            // that passes the end of its record is cut there.
            for (j in 0 until count) {
                if (16 + j * 2 + 2 > cdic.size) break
                val offset = 16 + u16(cdic, 16 + j * 2)
                if (offset + 2 > cdic.size) {
                    phrases += ByteArray(0)
                    plain += true
                    continue
                }
                val header = u16(cdic, offset)
                phrases += cdic.copyOfRange(offset + 2, minOf(offset + 2 + (header and 0x7FFF), cdic.size))
                plain += header and 0x8000 != 0
            }
        }
    }

    /** The number of phrases the CDIC records hold. */
    public val phraseCount: Int get() = phrases.size

    /**
     * Decode a whole text record.
     *
     * @throws ArchiveFormatException if a code is past the phrases, or a phrase contains itself or nests more than 32 deep.
     * @throws DecompressionBombException if the output or a phrase exceeds the limits of this decoder.
     */
    @Throws(ArchiveException::class)
    public fun decode(record: ByteArray): ByteArray = decode(record, 0, record.size)

    /**
     * Decode the [length] bytes of [record] that start at [offset]. A text record ends with
     * trailing entries that are not HUFF/CDIC data, so pass the length without them.
     *
     * @throws IllegalArgumentException if [offset] and [length] do not lie inside [record].
     * @throws ArchiveFormatException if a code is past the phrases, or a phrase contains itself or nests more than 32 deep.
     * @throws DecompressionBombException if the output or a phrase exceeds the limits of this decoder.
     */
    @Throws(ArchiveException::class, IllegalArgumentException::class)
    public fun decode(record: ByteArray, offset: Int, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= record.size && length <= record.size - offset) {
            "invalid HUFF/CDIC slice: offset $offset, length $length, size ${record.size}"
        }
        return unpack(record, offset, length, 0)
    }

    private fun unpack(data: ByteArray, offset: Int, size: Int, depth: Int): ByteArray {
        if (depth > MAX_DEPTH) throw ArchiveFormatException("huffcdic: phrases nest more than $MAX_DEPTH deep", offset.toLong())
        val out = ByteArrayBuilder(minOf(size.toLong() * 4 + 16, 1L shl 20).toInt())
        var bitsLeft = size.toLong() * 8
        var pos = 0

        // Eight bytes from p, with zeros past the end.
        fun window(p: Int): Long {
            var x = 0L
            for (k in 0 until 8) x = (x shl 8) or (if (p + k < size) (data[offset + p + k].toLong() and 0xFF) else 0L)
            return x
        }

        var x = window(0)
        var n = 32
        while (true) {
            if (n <= 0) {
                pos += 4
                x = window(pos)
                n += 32
            }
            val code = (x ushr n) and 0xFFFFFFFFL
            val top = (code ushr 24).toInt()
            var len = codeLength[top]
            var max = maxCode1[top]
            if (!terminal[top]) {
                while (len < 32 && code < minCode[len]) len++
                max = maxCode[len]
            }
            n -= len
            bitsLeft -= len
            if (bitsLeft < 0) break
            val r = ((max - code) ushr (32 - len)).toInt()
            if (r !in phrases.indices) {
                throw ArchiveFormatException("huffcdic: code $r is past the ${phrases.size} phrases", offset.toLong() + pos)
            }
            var phrase = phrases[r] ?: throw ArchiveFormatException("huffcdic: phrase $r contains itself", offset.toLong() + pos)
            if (!plain[r]) {
                // A null slot marks the phrase as in progress, so a phrase that contains itself is caught.
                val packed = phrase
                phrases[r] = null
                phrase = try {
                    unpack(packed, 0, packed.size, depth + 1)
                } catch (e: ArchiveException) {
                    phrases[r] = packed
                    throw e
                }
                phrases[r] = phrase
                plain[r] = true
            }
            val needed = out.size.toLong() + phrase.size
            limits.checkOutputSize(needed)
            if (needed > Int.MAX_VALUE - 8L) throw DecompressionBombException("huffcdic: output exceeds the ByteArray maximum")
            out.append(phrase)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MAX_DEPTH = 32

        fun ByteArray.startsWith(tag: String): Boolean = tag.indices.all { this[it].toInt() == tag[it].code }
        fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(b: ByteArray, o: Int): Long = ((u16(b, o).toLong()) shl 16) or u16(b, o + 2).toLong()
        fun s32(b: ByteArray, o: Int): Int = u32(b, o).toInt()
    }
}
