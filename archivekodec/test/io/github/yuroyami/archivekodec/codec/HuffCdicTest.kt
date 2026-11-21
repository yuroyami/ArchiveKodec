package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.huffcdic.HuffCdic
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HuffCdicTest {

    @Test
    fun eightBitCodesPickPhrasesAndAPhraseMadeOfPhrases() {
        // Every code is eight bits, and its byte is 255 minus the phrase number.
        fun code(vararg phrase: Int) = ByteArray(phrase.size) { (255 - phrase[it]).toByte() }
        val phrases = (0 until 256).map { byteArrayOf(it.toByte()) to true }.toMutableList()
        phrases[1] = code('H'.code, 'i'.code) to false
        val decoder = HuffCdic(flatHuff(), listOf(cdic(phrases, bits = 8)))
        assertEquals(256, decoder.phraseCount)
        assertEquals("xHi!", decoder.decode(code('x'.code, 1, '!'.code)).decodeToString())
        // The phrase is now decoded and kept, so a second record reads the same.
        assertEquals("HiHi", decoder.decode(code(1, 1)).decodeToString())
        val record = byteArrayOf(7) + code('o'.code, 'k'.code) + byteArrayOf(1, 2, 3)
        assertEquals("ok", decoder.decode(record, 1, 2).decodeToString())
    }

    @Test
    fun codesOfThreeNineAndTwelveBitsAcrossTwoCdicRecords() {
        // 4 codes of 3 bits, 128 of 9 bits and 1024 of 12 bits fill the code space exactly.
        val lengths = IntArray(4) { 3 } + IntArray(128) { 9 } + IntArray(1024) { 12 }
        val code = MobiCode(lengths)
        val phrases = lengths.indices.map { "<$it>".encodeToByteArray() to true }.toMutableList()
        phrases[200] = code.pack(listOf(0, 1100, 3)) to false
        phrases[201] = code.pack(listOf(200, 200)) to false
        val decoder = HuffCdic(code.huff(), listOf(cdic(phrases.subList(0, 1024), bits = 10, total = phrases.size),
            cdic(phrases.subList(1024, phrases.size), bits = 10, total = phrases.size)))
        assertEquals(lengths.size, decoder.phraseCount)

        val random = Random(5)
        val picks = List(3000) { random.nextInt(lengths.size) } + listOf(201, 0, 1155, 131, 132)
        val expected = StringBuilder()
        for (p in picks) expected.append(
            when (p) {
                200 -> "<0><1100><3>"
                201 -> "<0><1100><3><0><1100><3>"
                else -> "<$p>"
            },
        )
        assertEquals(expected.toString(), decoder.decode(code.pack(picks)).decodeToString())
    }

    @Test
    fun aPhraseThatContainsItselfIsDamage() {
        fun code(vararg phrase: Int) = ByteArray(phrase.size) { (255 - phrase[it]).toByte() }
        val phrases = (0 until 256).map { byteArrayOf(it.toByte()) to true }.toMutableList()
        phrases[1] = code(2) to false
        phrases[2] = code(1) to false
        val decoder = HuffCdic(flatHuff(), listOf(cdic(phrases, bits = 8)))
        assertFailsWith<ArchiveFormatException> { decoder.decode(code(1)) }
        // The failure leaves the phrases as they were.
        assertFailsWith<ArchiveFormatException> { decoder.decode(code(2)) }
        assertEquals("A", decoder.decode(code('A'.code)).decodeToString())
    }

    @Test
    fun aCodePastThePhrasesIsDamage() {
        val phrases = (0 until 16).map { byteArrayOf(it.toByte()) to true }
        val decoder = HuffCdic(flatHuff(), listOf(cdic(phrases, bits = 8)))
        assertEquals(16, decoder.phraseCount)
        assertFailsWith<ArchiveFormatException> { decoder.decode(byteArrayOf(0)) }
    }

    @Test
    fun badRecordsAreRefused() {
        val phrases = listOf(byteArrayOf(1) to true)
        assertFailsWith<ArchiveFormatException> { HuffCdic(ByteArray(24), listOf(cdic(phrases, bits = 8))) }
        assertFailsWith<ArchiveFormatException> { HuffCdic(flatHuff().copyOf(500), listOf(cdic(phrases, bits = 8))) }
        assertFailsWith<ArchiveFormatException> { HuffCdic(flatHuff(), listOf(ByteArray(16))) }
        val shortNonTerminal = flatHuff().also { it[24 + 3] = 8 } // length 8, not terminal
        assertFailsWith<ArchiveFormatException> { HuffCdic(shortNonTerminal, emptyList()) }
    }

    @Test
    fun limitsThrow() {
        fun code(vararg phrase: Int) = ByteArray(phrase.size) { (255 - phrase[it]).toByte() }
        val phrases = (0 until 256).map { ByteArray(1000) to true }
        val decoder = HuffCdic(flatHuff(), listOf(cdic(phrases, bits = 8)), ArchiveLimits { maxUncompressedBytes = 2500 })
        assertEquals(2000, decoder.decode(code(1, 2)).size)
        assertFailsWith<DecompressionBombException> { decoder.decode(code(1, 2, 3)) }
    }

    @Test
    fun theRatioAndTheKeptPhrasesAreLimited() {
        fun code(vararg phrase: Int) = ByteArray(phrase.size) { (255 - phrase[it]).toByte() }
        val phrases = (0 until 256).map { ByteArray(1000) to true }.toMutableList()
        // One byte of code expands to 1000 bytes: past a 100:1 ratio.
        val strict = HuffCdic(flatHuff(), listOf(cdic(phrases, bits = 8)), ArchiveLimits { maxCompressionRatio = 100 })
        assertFailsWith<DecompressionBombException> { strict.decode(code(0)) }
        // Phrases 1 and 2 are each five copies of phrase 0: 5000 bytes kept apiece.
        phrases[1] = code(0, 0, 0, 0, 0) to false
        phrases[2] = code(0, 0, 0, 0, 0) to false
        val decoder = HuffCdic(flatHuff(), listOf(cdic(phrases, bits = 8)), ArchiveLimits { maxDecoderMemory = 6000 })
        assertEquals(5000, decoder.decode(code(1)).size)
        assertFailsWith<DecompressionBombException> { decoder.decode(code(2)) }
        // A failed phrase is not kept, and one already kept still decodes.
        assertEquals(5000, decoder.decode(code(1)).size)
    }

    /** A HUFF record whose 256 codes are all eight bits long and terminal. */
    private fun flatHuff(): ByteArray {
        val table1 = ByteArray(1024)
        for (i in 0 until 256) u32((255 shl 8) or 0x80 or 8).copyInto(table1, i * 4)
        return "HUFF".encodeToByteArray() + u32(24) + u32(24) + u32(24 + 1024) + ByteArray(8) + table1 + ByteArray(256)
    }

    private fun cdic(phrases: List<Pair<ByteArray, Boolean>>, bits: Int, total: Int = phrases.size): ByteArray {
        val offsets = ByteArray(phrases.size * 2)
        var body = ByteArray(0)
        for ((i, phrase) in phrases.withIndex()) {
            u16(phrases.size * 2 + body.size).copyInto(offsets, i * 2)
            body += u16(phrase.first.size or (if (phrase.second) 0x8000 else 0)) + phrase.first
        }
        return "CDIC".encodeToByteArray() + u32(16) + u32(total) + u32(bits) + offsets + body
    }

    /**
     * A MOBI Huffman code for phrase [lengths]: canonical, with every bit inverted, so shorter codes
     * are larger. The phrase number is the stored maximum of its length minus its code.
     */
    private class MobiCode(private val lengths: IntArray) {
        private val codes = LongArray(lengths.size)
        private val count = IntArray(33)
        private val low = LongArray(33)
        private val storedMax = LongArray(33)

        init {
            for (l in lengths) count[l]++
            var next = 0L
            val first = LongArray(33)
            for (l in 1..32) {
                next = (next + count[l - 1]) shl 1
                first[l] = next
            }
            val base = IntArray(33) { -1 }
            for ((i, l) in lengths.withIndex()) if (base[l] < 0) base[l] = i
            for (l in 1..32) {
                val high = (1L shl l) - 1 - first[l]
                low[l] = if (count[l] > 0) high - count[l] + 1 else 1L shl l
                storedMax[l] = if (count[l] > 0) base[l] + high else 0
            }
            for ((i, l) in lengths.withIndex()) codes[i] = storedMax[l] - i
        }

        fun huff(): ByteArray {
            val table1 = ByteArray(1024)
            for (top in 0 until 256) {
                var entry = -1L
                for ((i, l) in lengths.withIndex()) if (l <= 8 && (top ushr (8 - l)).toLong() == codes[i]) entry = (storedMax[l] shl 8) or 0x80L or l.toLong()
                if (entry < 0) {
                    val l = lengths.indices.filter { lengths[it] > 8 && (codes[it] ushr (lengths[it] - 8)) == top.toLong() }.minOf { lengths[it] }
                    entry = l.toLong()
                }
                u32(entry.toInt()).copyInto(table1, top * 4)
            }
            val table2 = ByteArray(256)
            for (l in 1..32) {
                u32(low[l].toInt()).copyInto(table2, (l - 1) * 8)
                u32(storedMax[l].toInt()).copyInto(table2, (l - 1) * 8 + 4)
            }
            return "HUFF".encodeToByteArray() + u32(24) + u32(24) + u32(24 + 1024) + ByteArray(8) + table1 + table2
        }

        fun pack(phrases: List<Int>): ByteArray {
            val bits = StringBuilder()
            for (p in phrases) bits.append(codes[p].toString(2).padStart(lengths[p], '0'))
            while (bits.length % 8 != 0) bits.append('0')
            return ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        }
    }

    private companion object {
        fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        fun u16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    }
}
