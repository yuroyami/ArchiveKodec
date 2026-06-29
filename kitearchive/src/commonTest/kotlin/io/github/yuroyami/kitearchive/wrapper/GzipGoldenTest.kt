package io.github.yuroyami.kitearchive.wrapper

import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Decode-conformance against bytes produced by the real `zlib`/`gzip` (CPython,
 * level 6 → dynamic Huffman), plus our own round-trips. The golden vectors below
 * were emitted by `gzip.GzipFile(mtime=0)` and `zlib.compress(level=6)` over the
 * message `"KiteArchive golden vector\n"`.
 */
class GzipGoldenTest {

    private val message = "KiteArchive golden vector\n".encodeToByteArray()

    // gzip member (mtime=0), CPython gzip.
    private val gzipVector = hex(
        "1f8b08000000000002fff3ce2c49752c4acec82c4b5548cfcf4949cd53284b4d2ec92fe20200af9310fc1a000000",
    )

    // zlib stream, CPython zlib.compress(level=6).
    private val zlibVector = hex("789cf3ce2c49752c4acec82c4b5548cfcf4949cd53284b4d2ec92fe2020084bb09a6")

    @Test
    fun decodesRealGzip() = assertContentEquals(message, Gzip.decompress(gzipVector))

    @Test
    fun decodesRealZlib() = assertContentEquals(message, Zlib.decompress(zlibVector))

    @Test
    fun gzipRoundTrip() {
        val data = ("The quick brown fox jumps over the lazy dog. ").repeat(40).encodeToByteArray()
        assertContentEquals(data, Gzip.decompress(Gzip.compress(data)))
    }

    @Test
    fun zlibRoundTrip() {
        val data = ("lorem ipsum dolor sit amet ").repeat(100).encodeToByteArray()
        assertContentEquals(data, Zlib.decompress(Zlib.compress(data)))
    }

    @Test
    fun gzipDecodesOurOwnOutput() {
        // our gzip member must also be readable by us (header + CRC32/ISIZE trailer present)
        assertContentEquals(message, Gzip.decompress(Gzip.compress(message)))
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) {
            ((s[it * 2].digitToInt(16) shl 4) or s[it * 2 + 1].digitToInt(16)).toByte()
        }
}
