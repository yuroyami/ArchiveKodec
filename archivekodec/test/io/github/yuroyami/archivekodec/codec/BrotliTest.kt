package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.ArchiveFormatException
import io.github.yuroyami.archivekodec.ArchiveKodec
import io.github.yuroyami.archivekodec.archive.ArchiveFormat
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Brotli (#14). Fixtures come from the reference brotli 1.2 library through its Python
 * binding; the encoder's output was also checked with it.
 */
@OptIn(ExperimentalEncodingApi::class)
class BrotliTest {
    private val hello = "hello world, hello world".encodeToByteArray()
    private val helloQ11 = Base64.decode("HxcA+KXL0Nje1CrAVAMokbodBg==") // quality 11, 16 MiB window, as `brotli -k`
    private val helloQ0 = Base64.decode("iwuAaGVsbG8gd29ybGQsIGhlbGxvIHdvcmxkAw==") // quality 0

    // English words and HTML tags: 177 bytes in 79, through static dictionary references.
    private val page = ("<html><head><title>The government information page</title></head><body><p>Welcome to the " +
        "international community, where everyone is <b>free</b> to participate.</p></body></html>").encodeToByteArray()
    private val pageQ11 = Base64.decode(
        "G7AAAB0Hdiw2KtLm+lHziL3RrRe48HGDA/a+B98X7wEHXE+WIrcTBAdP0LAe3GBpJAbd8CZKAoVohwvuL5GJY3KneI6+FBE2r+jmwoeMAQ==",
    )

    // 64 random bytes at quality 11, which stores them in an uncompressed meta-block.
    private val random64 = hex("769c5f44232fddad01568076e59a14558def9db30aba612bb4f273f3b96c282b3c0d1c2181dfecfd9710c6b062cabfe31aeb4a34ac39b9cb6be316c544e93565")
    private val random64Q11 = Base64.decode("ix+AdpxfRCMv3a0BVoB25ZoUVY3vnbMKumErtPJz87lsKCs8DRwhgd/s/ZcQxrBiyr/jGutKNKw5uctr4xbFROk1ZQM=")

    // Hand-built: a 3-byte metadata block, then "abc" uncompressed, then an empty last block.
    private val metadata = hex("2c0158595a10000861626303")

    // Quality 11, 4 MiB window: context modelling and block switching.
    private val generatedQ11 = Base64.decode(
        "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",
    )
    // Quality 5, 64 KiB window, so several meta-blocks.
    private val generatedQ5 = Base64.decode(
        "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" +
        "phQYkqHn+BkL",
    )
    // Quality 0 of generated(20_000), 1 KiB window: the one-pass fragment compressor.
    private val generatedQ0 = Base64.decode(
        "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",
    )

    /** The same generator as ZstdTest: an LCG choosing words, newlines and long runs of 'z'. */
    private fun generated(size: Int): ByteArray {
        val words = listOf("alpha", "beta", "gamma", "delta").map { it.encodeToByteArray() }
        var x = 1L
        val out = ArrayList<Byte>(size + 400)
        while (out.size < size) {
            x = (x * 1103515245 + 12345) and 0x7fffffff
            val r = (x shr 8) % 64
            if (r == 0L) repeat(((x shr 4) % 300).toInt()) { out.add('z'.code.toByte()) }
            for (b in words[if (r < 8) ((x shr 16) % 4).toInt() else 0]) out.add(b)
            out.add(if (r == 9L) 10 else 32)
        }
        return out.subList(0, size).toByteArray()
    }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun decodesReferenceStreams() {
        assertContentEquals(hello, Codecs.BROTLI.decompress(helloQ11))
        assertContentEquals(hello, Codecs.BROTLI.decompress(helloQ0))
        assertContentEquals(page, Codecs.BROTLI.decompress(pageQ11))
        assertContentEquals(random64, Codecs.BROTLI.decompress(random64Q11))
        assertContentEquals("abc".encodeToByteArray(), Codecs.BROTLI.decompress(metadata))
        val text = generated(100_000)
        assertContentEquals(text, Codecs.BROTLI.decompress(generatedQ11))
        assertContentEquals(text, Codecs.BROTLI.decompress(generatedQ5))
        assertContentEquals(text.copyOf(20_000), Codecs.BROTLI.decompress(generatedQ0))
        // The shortest stream: a 16-bit window and an empty last meta-block.
        assertContentEquals(ByteArray(0), Codecs.BROTLI.decompress(byteArrayOf(6)))
    }

    @Test
    fun facadeAndRegistry() {
        assertSame(Codecs.BROTLI, ArchiveKodec.brotli)
        assertSame(Codecs.BROTLI, CodecRegistry.DEFAULT.byId(CodecId.BROTLI))
        assertSame(Codecs.BROTLI, CodecRegistry.DEFAULT.byFormat(ArchiveFormat.BROTLI))
        assertSame(Codecs.BROTLI, CodecRegistry.DEFAULT.byName("Brotli"))
    }

    @Test
    fun corruptionIsDetected() {
        // Brotli has no checksum, so a flipped bit may still decode. Failures must be archive
        // errors, and most flips must be noticed.
        for (stream in listOf(helloQ11, pageQ11)) {
            val original = Codecs.BROTLI.decompress(stream)
            var failures = 0
            for (position in stream.indices) for (bit in 0 until 8) {
                val damaged = stream.copyOf().also { it[position] = (it[position].toInt() xor (1 shl bit)).toByte() }
                val result = runCatching { Codecs.BROTLI.decompress(damaged) }
                result.exceptionOrNull()?.let { assertTrue(it is ArchiveException, "byte $position bit $bit: $it") }
                if (result.isFailure || !result.getOrThrow().contentEquals(original)) failures++
            }
            assertTrue(failures > stream.size * 4, "most bit flips must change the result")
        }
        for (end in 1 until pageQ11.size) {
            assertFailsWith<ArchiveFormatException>("truncated to $end") { Codecs.BROTLI.decompress(pageQ11.copyOf(end)) }
        }
        assertFailsWith<ArchiveFormatException> { Codecs.BROTLI.decompress(ByteArray(0)) }
        assertFailsWith<ArchiveFormatException> { Codecs.BROTLI.decompress(helloQ11 + 0) }
        // The last byte's unused high bits must be zero.
        assertFailsWith<ArchiveFormatException> { Codecs.BROTLI.decompress(byteArrayOf(0x86.toByte())) }
        // Window-bits pattern 0010001 is the large-window extension, which RFC 7932 streams never use.
        assertFailsWith<ArchiveFormatException> { Codecs.BROTLI.decompress(byteArrayOf(0x11, 0x03)) }
    }

    @Test
    fun encoderRoundTrips() {
        val random = ByteArray(200_000).also { var x = 3L; for (i in it.indices) { x = x * 6364136223846793005L + 1442695040888963407L; it[i] = (x ushr 56).toByte() } }
        for (data in listOf(ByteArray(0), byteArrayOf(7), hello, page, ByteArray(1_000_000), random, generated(1_300_000), random + generated(300_000))) {
            assertContentEquals(data, Codecs.BROTLI.decompress(Codecs.BROTLI.compress(data), ArchiveLimits.UNBOUNDED), "${data.size} bytes")
        }
        assertTrue(Codecs.BROTLI.compress(generated(1_300_000)).size < 80_000)
        assertTrue(Codecs.BROTLI.compress(random).size <= random.size + 16)
    }

    @Test
    fun limitsApply() {
        assertFailsWith<DecompressionBombException> { Codecs.BROTLI.decompress(generatedQ11, ArchiveLimits { maxUncompressedBytes = 1000 }) }
        assertFailsWith<DecompressionBombException> {
            Codecs.BROTLI.decompress(Codecs.BROTLI.compress(ByteArray(1_000_000)), ArchiveLimits { maxCompressionRatio = 100 })
        }
        assertContentEquals(page, Codecs.BROTLI.decompress(pageQ11, ArchiveLimits { maxUncompressedBytes = page.size.toLong() }))
    }
}
