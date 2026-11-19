package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.deflate.DeflateOptions
import io.github.yuroyami.archivekodec.codec.deflate.DeflateStrategy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deterministic mode (#154, Stage 4): the pure encoders give the same bytes on every platform
 * for the same input and options. Each case pins the size and CRC-32 of its output; the test
 * runs on the JVM, Android host, Node, Wasm and Kotlin/Native alike. The platform deflater and
 * encrypted output are outside this promise. When an encoder changes on purpose, update the
 * values here in the same commit and say so in its message.
 */
class GoldenEncoderTest {
    /** Words, random bytes and runs from a fixed xorshift32 seed: no platform randomness. */
    private val input: ByteArray = run {
        var x = 0x2545F491
        fun next(): Int { x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); return x }
        val words = listOf("archive ", "codec ", "the ", "window ", "match ", "literal ", "block ", "stream\n")
        val out = ArrayList<Byte>()
        while (out.size < 40_000) out.addAll(words[(next() ushr 1) % words.size].encodeToByteArray().toList())
        repeat(8_000) { out.add(next().toByte()) }
        repeat(12_000) { out.add((it / 1000).toByte()) }
        out.toByteArray()
    }

    private fun golden(bytes: ByteArray) = "${bytes.size}:${Crc32.of(bytes).toString(16)}"

    private fun outputs(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (id in Codecs.implemented) {
            val codec = Codecs.byId(id) ?: continue
            result[id.name] = golden(codec.compress(input))
        }
        for (level in 0..9) result["deflate-$level"] = golden(Codecs.DEFLATE.compress(input, DeflateOptions { this.level = level }))
        for (strategy in DeflateStrategy.entries) {
            result["deflate-${strategy.name.lowercase()}"] = golden(Codecs.DEFLATE.compress(input, DeflateOptions { this.strategy = strategy }))
        }
        return result
    }

    @Test
    fun encodersGiveTheSameBytesOnEveryPlatform() {
        val actual = outputs()
        val mismatches = EXPECTED.filter { (name, value) -> actual[name] != value }.keys + (actual.keys - EXPECTED.keys)
        assertEquals(emptySet(), mismatches, "expected $EXPECTED\nactual   $actual")
    }

    private companion object {
        val EXPECTED: Map<String, String> = mapOf(
            "store" to "60005:e22a281a",
            "deflate" to "13462:34a5a4a1",
            "deflate64" to "13404:618bf8a5",
            "bzip2" to "11576:18c07b31",
            "lzma" to "12852:ca7daf62",
            "lzma2" to "12846:5fedaaad",
            "xz" to "12904:d868dfed",
            "lzip" to "12871:b71cdccd",
            "zstd" to "15165:7a320d71",
            "brotli" to "13470:a86d79d7",
            "ppmd" to "10837:7c4253e0",
            "gzip" to "13480:76b025f2",
            "zlib" to "13468:b130765",
            "lz4" to "24369:aac4875c",
            "snappy" to "19957:f2d792c1",
            "snappy-framed" to "19975:ec31f3ae",
            "lzw" to "18330:6423ce38",
            "packbits" to "48573:2d2b2f41",
            "palmdoc" to "20738:8ab5a20c",
            "deflate-0" to "60010:7fd84f3b",
            "deflate-1" to "16039:93db2227",
            "deflate-2" to "15308:515eba04",
            "deflate-3" to "14437:d541e2d4",
            "deflate-4" to "14398:1725182f",
            "deflate-5" to "13952:5609ef53",
            "deflate-6" to "13462:34a5a4a1",
            "deflate-7" to "13347:192066a6",
            "deflate-8" to "13296:70515a41",
            "deflate-9" to "13296:70515a41",
            "deflate-default" to "13462:34a5a4a1",
            "deflate-filtered" to "13462:64da7c5f",
            "deflate-huffman_only" to "35503:c84586fe",
            "deflate-rle" to "29875:4e550fcb",
            "deflate-fixed" to "14238:7c45d2c2",
        )
    }
}
