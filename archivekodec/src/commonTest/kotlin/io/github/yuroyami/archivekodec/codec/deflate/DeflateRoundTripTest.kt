package io.github.yuroyami.archivekodec.codec.deflate

import kotlin.test.Test
import kotlin.test.assertContentEquals

/** encode → inflate identity over a spread of inputs (empty, tiny, repetitive, random). */
class DeflateRoundTripTest {

    @Test
    fun roundTripVarious() {
        val samples = listOf(
            ByteArray(0),
            "a".encodeToByteArray(),
            "hello, world".encodeToByteArray(),
            ("ab").repeat(2000).encodeToByteArray(),       // highly repetitive → long matches
            ("The quick brown fox. ").repeat(300).encodeToByteArray(),
            ByteArray(5000) { 0 },                          // run of zeros
            pseudoRandom(10_000),                           // incompressible-ish
        )
        for (s in samples) {
            val deflated = Deflate.encode(s)
            val inflated = Inflate.inflate(deflated)
            assertContentEquals(s, inflated, "round-trip failed for size ${s.size}")
        }
    }

    /** Deterministic LCG so the "random" sample is reproducible across runs/targets. */
    private fun pseudoRandom(n: Int): ByteArray {
        var x = 0x12345678
        return ByteArray(n) {
            x = x * 1103515245 + 12345
            (x ushr 16).toByte()
        }
    }
}
