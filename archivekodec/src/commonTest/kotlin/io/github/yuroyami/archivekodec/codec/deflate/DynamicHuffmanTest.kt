package io.github.yuroyami.archivekodec.codec.deflate

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the dynamic-Huffman (BTYPE=10) path: skewed distributions where it
 *  must be chosen over fixed, plus round-trip correctness through [Inflate]. */
class DynamicHuffmanTest {

    @Test
    fun roundTripsAndCompressesSkewedText() {
        val sb = StringBuilder()
        repeat(5000) { sb.append("the quick brown fox ") }
        val data = sb.toString().encodeToByteArray()

        val enc = Deflate.encode(data)
        assertEquals(2, (enc[0].toInt() ushr 1) and 3, "expected dynamic coding for skewed text")
        assertContentEquals(data, Inflate.inflate(enc))
        assertTrue(enc.size < data.size / 5, "weak compression: ${enc.size}/${data.size}")
    }

    @Test
    fun roundTripsBinarySkew() {
        val data = ByteArray(20_000) { if (it % 7 == 0) 0xFF.toByte() else 0 }
        val enc = Deflate.encode(data)
        assertEquals(2, (enc[0].toInt() ushr 1) and 3, "expected dynamic coding for binary skew")
        assertContentEquals(data, Inflate.inflate(enc))
    }

    @Test
    fun roundTripsFullByteAlphabet() {
        val data = ByteArray(256 * 40) { (it % 256).toByte() }
        val enc = Deflate.encode(data)
        assertEquals(2, (enc[0].toInt() ushr 1) and 3, "expected dynamic coding for the repeated alphabet")
        assertContentEquals(data, Inflate.inflate(enc))
    }

    @Test
    fun dynamicBeatsFixedOnSkewedAlphabet() {
        // A few hot symbols + many rare ones → dynamic header pays for itself.
        val data = ByteArray(40_000) {
            when (it % 10) {
                in 0..6 -> 'e'.code.toByte()
                7 -> 'x'.code.toByte()
                8 -> (it % 256).toByte()
                else -> 'z'.code.toByte()
            }
        }
        val enc = Deflate.encode(data)
        assertEquals(2, (enc[0].toInt() ushr 1) and 3, "expected dynamic coding for skewed symbols")
        assertContentEquals(data, Inflate.inflate(enc))
        assertTrue(enc.size < data.size, "expected some compression: ${enc.size}/${data.size}")
    }

    @Test
    fun fixedCodingWinsForSmallInputs() {
        for (data in listOf(byteArrayOf(), "hello, world".encodeToByteArray())) {
            val enc = Deflate.encode(data)
            assertEquals(1, (enc[0].toInt() ushr 1) and 3, "expected fixed coding without a dynamic header")
            assertContentEquals(data, Inflate.inflate(enc))
        }
    }
}
