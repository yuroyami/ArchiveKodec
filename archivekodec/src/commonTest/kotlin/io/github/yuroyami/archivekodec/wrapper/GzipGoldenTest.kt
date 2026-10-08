package io.github.yuroyami.archivekodec.wrapper

import io.github.yuroyami.archivekodec.hex
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Decode conformance against fixed-Huffman streams from CPython `zlib`/`gzip`,
 * plus our own round-trips. The original vectors below
 * were emitted by `gzip.GzipFile(mtime=0)` and `zlib.compress(level=6)` over the
 * message `"ArchiveKodec golden vector\n"`.
 */
class GzipGoldenTest {

    private val message = "ArchiveKodec golden vector\n".encodeToByteArray()

    // gzip member (mtime=0), CPython gzip.
    private val gzipVector = hex(
        "1f8b08000000000002ff732c4acec82c4bf5ce4f494d5648cfcf4949cd53284b4d2ec92fe20200693ea8251b000000",
    )

    // zlib stream, CPython zlib.compress(level=6).
    private val zlibVector = hex("789c732c4acec82c4bf5ce4f494d5648cfcf4949cd53284b4d2ec92fe202008eba09ff")

    @Test
    fun decodesRealGzip() = assertContentEquals(message, Gzip.decompress(gzipVector))

    @Test
    fun decodesRealZlib() = assertContentEquals(message, Zlib.decompress(zlibVector))

    @Test
    fun decodesCommandLineGzipWithOriginalFilename() {
        // Apple gzip 479: gzip -c h.txt, mtime=0. Verified with CPython gzip and zlib.
        val vector = hex("1f8b0808000000000003682e74787400cb48cdc9c9e7020020303a3606000000")
        assertContentEquals("hello\n".encodeToByteArray(), Gzip.decompress(vector))
    }

    @Test
    fun decodesForeignGzipOptionalHeaderFields() {
        // Verified with independent gzip/zlib readers. Individual fields expose
        // skipped bytes that a later NUL-terminated field could otherwise absorb.
        val vectors = listOf(
            "1f8b08040000000000030600414202003132cb48cdc9c9e7020020303a3606000000", // FEXTRA
            "1f8b08100000000000036300cb48cdc9c9e7020020303a3606000000", // FCOMMENT
            "1f8b0802000000000003a777cb48cdc9c9e7020020303a3606000000", // FHCRC
            "1f8b081e00000000000306004142020031326e0063000f00cb48cdc9c9e7020020303a3606000000", // all four
        )
        for (vector in vectors) assertContentEquals("hello\n".encodeToByteArray(), Gzip.decompress(hex(vector)))
    }

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

}
