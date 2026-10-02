package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import kotlin.test.Test
import kotlin.test.assertContentEquals

class Lz4Test {

    private fun sample(): ByteArray =
        "the quick brown fox jumps over the lazy dog ".repeat(30).encodeToByteArray() +
            ByteArray(256 * 4) { (it % 256).toByte() }

    // Frame produced by the `lz4` CLI (content-checksum flag set, 64 KiB blocks).
    private val cliVector = hex("04224d186440a743010000f01074686520717569636b2062726f776e20666f78206a756d7073206f766572201f00816c617a7920646f670d000f2c00ffffffffe9fff1000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff0001ffffea50fbfcfdfeff00000000ebe8a7af")

    @Test
    fun decodesCliFrame() {
        assertContentEquals(sample(), Lz4.frameDecompress(cliVector))
    }

    @Test
    fun frameRoundTrip() {
        for (s in listOf(ByteArray(0), "x".encodeToByteArray(), sample(), ByteArray(5000) { 0 })) {
            assertContentEquals(s, Lz4.frameDecompress(Lz4.frameCompress(s)))
        }
    }

    @Test
    fun blockRoundTrip() {
        val s = sample()
        val block = Lz4.blockCompress(s)
        assertContentEquals(s, Lz4.blockDecompress(block, 0, block.size))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) {
            ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
        }
}
