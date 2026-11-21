package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.codec.palmdoc.PalmDoc
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PalmDocTest {

    // "abc", a copy of 6 bytes from 3 back, a space and 'A', then a run of two raw bytes.
    private val packed = byteArrayOf(0x61, 0x62, 0x63, 0x80.toByte(), 0x1B, 0xC1.toByte(), 0x02, 0x01, 0x02)
    private val text = "abcabcabc A\u0001\u0002".encodeToByteArray()

    @Test
    fun everyKindOfByteDecodesAndEncodesExactly() {
        assertContentEquals(text, PalmDoc.decode(packed))
        assertContentEquals(packed, PalmDoc.encode(text))
        assertContentEquals(text, Codecs.PALMDOC.decompress(packed))
    }

    @Test
    fun recordsRoundTripAndTextCompresses() {
        val random = Random(17)
        val prose = "It was the best of times, it was the worst of times, it was the age of wisdom. ".repeat(60).encodeToByteArray()
        val samples = listOf(
            ByteArray(0),
            byteArrayOf(0x20),
            byteArrayOf(0x20, 0x40),
            byteArrayOf(0x20, 0x80.toByte(), 0x20, 0x7F),
            ByteArray(4096),
            ByteArray(4096) { random.nextInt().toByte() },
            ByteArray(9000) { (it % 251).toByte() },
            prose.copyOf(4096),
            prose,
        )
        for (data in samples) {
            val encoded = PalmDoc.encode(data)
            assertContentEquals(data, PalmDoc.decode(encoded), "${data.size} bytes")
        }
        assertTrue(PalmDoc.encode(prose).size < prose.size / 3)
    }

    @Test
    fun aCopyFromBeforeTheOutputIsDamage() {
        val data = byteArrayOf(0x61, 0x80.toByte(), 0x13) // a copy of 6 from 2 back after one byte
        assertFailsWith<ArchiveFormatException> { PalmDoc.decode(data) }
        val partial = PalmDoc.decode(data, 0, data.size, keepPartialOutput = true)
        assertEquals("a", partial.output.decodeToString())
        assertIs<ArchiveFormatException>(partial.error)
        assertEquals(3, partial.inputConsumed)
    }

    @Test
    fun aCopyWithoutItsSecondByteIsDamage() {
        val data = byteArrayOf(0x61, 0x80.toByte())
        assertFailsWith<ArchiveFormatException> { PalmDoc.decode(data) }
        assertEquals("a", PalmDoc.decode(data, 0, data.size, keepPartialOutput = true).output.decodeToString())
    }

    @Test
    fun aCutRawRunKeepsTheBytesThatAreThere() {
        val data = byteArrayOf(0x61, 0x05, 0x01, 0x02)
        assertFailsWith<ArchiveFormatException> { PalmDoc.decode(data) }
        val partial = PalmDoc.decode(data, 0, data.size, keepPartialOutput = true)
        assertContentEquals(byteArrayOf(0x61, 0x01, 0x02), partial.output)
        assertIs<ArchiveFormatException>(partial.error)
    }

    @Test
    fun aRangeDecodesOnlyItsBytes() {
        val data = byteArrayOf(9, 9) + packed + byteArrayOf(0x41)
        assertContentEquals(text, PalmDoc.decode(data, 2, packed.size).output)
        assertFailsWith<IllegalArgumentException> { PalmDoc.decode(data, 4, data.size) }
    }

    @Test
    fun limitsThrow() {
        val zeros = PalmDoc.encode(ByteArray(40_000))
        assertFailsWith<DecompressionBombException> {
            PalmDoc.decode(zeros, 0, zeros.size, ArchiveLimits { maxUncompressedBytes = 1000 }, keepPartialOutput = true)
        }
        assertFailsWith<DecompressionBombException> {
            PalmDoc.decode(zeros, 0, zeros.size, ArchiveLimits { maxCompressionRatio = 2 })
        }
    }

    @Test
    fun theRatioIsCheckedBeforeTheOutputGrows() {
        // Zeros pack at about 5:1; noise packs as raw runs of eight bytes from nine. Whole, the
        // stream is under 3:1, but its start is not, so the decoder stops there, against the
        // bytes it has read, as the other decoders do, rather than after building the output.
        val zeros = PalmDoc.encode(ByteArray(4000))
        val noise = PalmDoc.encode(Random(7).nextBytes(40_000).also { b -> for (i in b.indices) b[i] = (b[i].toInt() or 0x80).toByte() })
        val stream = zeros + noise
        val low = ArchiveLimits { maxCompressionRatio = 3 }
        assertTrue((4000L + 40_000) / stream.size < 3, "whole-stream ratio")
        val error = assertFailsWith<DecompressionBombException> { PalmDoc.decode(stream, 0, stream.size, low, keepPartialOutput = true) }
        assertTrue(error.message!!.contains("exceeds 3:1"), error.message)
        // Raw runs alone stay under 1:1 at every step, so even that limit passes them.
        assertEquals(40_000, PalmDoc.decode(noise, 0, noise.size, ArchiveLimits { maxCompressionRatio = 1 }).output.size)
    }
}
