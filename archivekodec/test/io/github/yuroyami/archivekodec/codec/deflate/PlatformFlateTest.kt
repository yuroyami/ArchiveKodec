package io.github.yuroyami.archivekodec.codec.deflate

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Zlib
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The platform fast paths give the same results as the pure code, valid or damaged. On platforms
 * without one, the setting is ignored, so the same checks hold there too.
 */
class PlatformFlateTest {

    private val text = "PDF content stream: 0 0 m 100 100 l S BT /F1 12 Tf (Hello) Tj ET\n".repeat(3000).encodeToByteArray()
    private val noise = Random(1).nextBytes(20_000)

    private fun streams(): List<Pair<String, ByteArray>> {
        val raw = Deflate.encode(text)
        val zlib = Zlib.compress(text)
        val list = mutableListOf(
            "raw" to raw,
            "raw level 1" to Deflate.encode(text, DeflateOptions { level = 1 }),
            "raw stored" to Deflate.encode(noise, DeflateOptions { level = 0 }),
            "raw noise" to Deflate.encode(noise),
            "raw empty" to Deflate.encode(ByteArray(0)),
            "raw cut" to raw.copyOf(raw.size / 2),
            "raw trailing" to raw + byteArrayOf(1, 2, 3),
            "raw garbage" to byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            "zlib" to zlib,
            "zlib bad adler" to zlib.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() },
            "zlib cut trailer" to zlib.copyOf(zlib.size - 2),
            "zlib bad header" to zlib.copyOf().also { it[1] = (it[1] + 1).toByte() },
            "zlib dictionary" to Zlib.compress(text, DeflateOptions { dictionary = "Hello".encodeToByteArray() }),
            "zlib trailing" to zlib + byteArrayOf(0),
        )
        for (i in 0 until 12) {
            val damaged = raw.copyOf()
            damaged[(i * 397 + 11) % damaged.size] = (damaged[(i * 397 + 11) % damaged.size].toInt() xor (1 shl (i % 8))).toByte()
            list += "raw flip $i" to damaged
        }
        return list
    }

    private fun outcome(block: () -> InflateResult): String = try {
        val r = block()
        "ok ${r.output.contentHashCode()} ${r.output.size} ${r.inputConsumed} ${r.error?.let { it::class.simpleName + ": " + it.message }}"
    } catch (e: ArchiveException) {
        "throws ${e::class.simpleName}: ${e.message}"
    }

    @Test
    fun theFastPathGivesThePureResultForEverySettingAndStream() {
        val settings = listOf(
            InflateOptions.DEFAULT,
            InflateOptions { allowTrailingData = true },
            InflateOptions { keepPartialOutput = true },
            InflateOptions { verifyChecksum = false },
            InflateOptions { verifyChecksum = false; allowTrailingData = true; keepPartialOutput = true },
            InflateOptions { limits = ArchiveLimits { maxUncompressedBytes = 1000 } },
            InflateOptions { limits = ArchiveLimits { maxCompressionRatio = 5 } },
            InflateOptions { sizeHint = 7 },
        )
        for ((name, stream) in streams()) for (options in settings) {
            val fast = options.copy { usePlatformInflater = true }
            val zlib = name.startsWith("zlib")
            val pure = outcome { if (zlib) Zlib.decompress(stream, options) else Inflate.inflate(stream, options) }
            val platform = outcome { if (zlib) Zlib.decompress(stream, fast) else Inflate.inflate(stream, fast) }
            assertEquals(pure, platform, "$name with $options")
        }
    }

    @Test
    fun aRangeDecodesTheSameOnBothPaths() {
        val stream = byteArrayOf(9, 9) + Zlib.compress(text) + byteArrayOf(7)
        val options = InflateOptions { allowTrailingData = true }
        val pure = Zlib.decompress(stream, 2, stream.size - 2, options)
        val fast = Zlib.decompress(stream, 2, stream.size - 2, options.copy { usePlatformInflater = true })
        assertContentEquals(pure.output, fast.output)
        assertEquals(pure.inputConsumed, fast.inputConsumed)
    }

    @Test
    fun thePlatformDeflaterRoundTripsForEveryStrategy() {
        for (strategy in DeflateStrategy.entries) for (level in listOf(0, 1, 6, 9)) {
            val options = DeflateOptions { this.strategy = strategy; this.level = level; usePlatformDeflater = true }
            for (data in listOf(text, noise, ByteArray(0))) {
                assertContentEquals(data, Inflate.inflate(Deflate.encode(data, options)), "$strategy $level")
                assertContentEquals(data, Zlib.decompress(Zlib.compress(data, options)), "$strategy $level zlib")
            }
        }
    }

    @Test
    fun optionsCompareTheNewSettings() {
        assertNotEquals(InflateOptions.DEFAULT, InflateOptions { usePlatformInflater = true })
        assertEquals(InflateOptions { usePlatformInflater = true }, InflateOptions.DEFAULT.copy { usePlatformInflater = true })
        assertNotEquals(DeflateOptions.DEFAULT, DeflateOptions { usePlatformDeflater = true })
        assertEquals(DeflateOptions { usePlatformDeflater = true }.hashCode(), DeflateOptions.DEFAULT.copy { usePlatformDeflater = true }.hashCode())
    }
}
