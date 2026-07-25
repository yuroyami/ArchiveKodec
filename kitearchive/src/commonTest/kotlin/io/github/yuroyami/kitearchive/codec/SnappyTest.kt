package io.github.yuroyami.kitearchive.codec

import io.github.yuroyami.kitearchive.codec.snappy.Snappy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SnappyTest {

    @Test
    fun decodesLiteralVector() {
        // varint(4) | literal len-1=3 | "aaaa"
        assertEquals("aaaa", Snappy.decompress(hex("040c61616161")).decodeToString())
    }

    @Test
    fun decodesCopyVector() {
        // varint(6) | literal "abc" | copy(len=3, offset=3) → "abcabc"
        assertEquals("abcabc", Snappy.decompress(hex("06086162630a0300")).decodeToString())
    }

    @Test
    fun roundTrip() {
        for (s in listOf(ByteArray(0), "x".encodeToByteArray(), ("snappy ".repeat(500)).encodeToByteArray())) {
            assertContentEquals(s, Snappy.decompress(Snappy.compress(s)))
        }
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) {
            ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
        }
}
