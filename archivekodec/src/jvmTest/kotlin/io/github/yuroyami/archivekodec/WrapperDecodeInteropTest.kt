package io.github.yuroyami.archivekodec

import io.github.yuroyami.archivekodec.codec.Codecs
import io.github.yuroyami.archivekodec.codec.deflate.Inflate
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.wrapper.Gzip
import io.github.yuroyami.archivekodec.wrapper.Zlib
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WrapperDecodeInteropTest {
    @Test
    fun decodesIndependentJdkRawAndZlibStreams() {
        val samples = listOf(byteArrayOf(), ByteArray(16_384), ByteArray(32_768) { (it * 37).toByte() })
        for (data in samples) for (level in listOf(0, 1, 6, 9)) {
            for (strategy in listOf(Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY)) {
                for (raw in listOf(true, false)) {
                    val encoded = deflate(data, level, strategy, raw)
                    val decoded = if (raw) Inflate.inflate(encoded) else Zlib.decompress(encoded)
                    assertContentEquals(data, decoded)
                    if (!raw) assertContentEquals(data, Gzip.decompress(encoded))
                }
            }
        }
    }

    @Test
    fun consumedCursorAgreesWithJdkInflater() {
        val data = "cursor consumption ".repeat(2000).encodeToByteArray()
        for (level in listOf(0, 1, 6, 9)) {
            val raw = deflate(data, level, Deflater.DEFAULT_STRATEGY, true)
            val input = raw + byteArrayOf(99, 98, 97)
            val inf = Inflater(true)
            try {
                inf.setInput(input)
                val decoded = ByteArray(data.size + 1)
                assertEquals(data.size, inf.inflate(decoded))
                assertTrue(inf.finished())
                val output = Inflate.Output(ArchiveLimits.DEFAULT, input.size)
                assertEquals(inf.bytesRead.toInt(), Inflate.inflateInto(input, 0, input.size, output))
                assertEquals(raw.size, inf.bytesRead.toInt())
                assertContentEquals(data, output.toByteArray())
            } finally {
                inf.end()
            }
        }
    }

    @Test
    fun decodesJdkConcatenatedGzipAndRejectsDamagedHeadersAndTrailers() {
        val first = "first".encodeToByteArray()
        val second = "second".encodeToByteArray()
        val input = gzip(first) + gzip(byteArrayOf()) + gzip(second)
        assertContentEquals(first + second, Gzip.decompress(input))
        assertContentEquals(first + second, GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() })
        val good = hex("1f8b08020000000000ff90c9cb48cdc9c9070086a6103605000000")
        assertContentEquals("hello".encodeToByteArray(), GZIPInputStream(ByteArrayInputStream(good)).use { it.readBytes() })
        for (index in listOf(10, good.size - 8, good.size - 4)) {
            val bad = good.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFailsWith<ArchiveFormatException> { Gzip.decompress(bad) }
            assertFailsWith<ZipException> { GZIPInputStream(ByteArrayInputStream(bad)).use { it.readBytes() } }
        }
    }

    @Test
    fun legacyDefaultBridgesKeepLinkingAndUseTheSharedDefault() {
        val data = ByteArray(5 * 1024 * 1024)
        for ((type, receiver, input) in listOf(
            Triple(Gzip::class.java, Gzip, Gzip.compress(data)),
            Triple(Zlib::class.java, Zlib, Zlib.compress(data)),
        )) {
            // These are the JVM entry points used by callers compiled before the overloads.
            type.getMethod("decompress", ByteArray::class.java, Long::class.javaPrimitiveType)
            val bridge = type.getMethod(
                "decompress\$default", type, ByteArray::class.java,
                Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Any::class.java,
            )
            val result = bridge.invoke(null, receiver, input, 0L, 2, null) as ByteArray
            assertEquals(data.size, result.size)
        }
    }

    @Test
    fun smallOutputCapsRejectBombsWithoutAllocatingTheirExpansion() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        val data = ByteArray(4 * 1024 * 1024)
        val limits = ArchiveLimits(maxUncompressedBytes = 1024)
        for (codec in listOf(Codecs.DEFLATE, Codecs.GZIP, Codecs.ZLIB)) {
            val input = codec.compress(data)
            repeat(20) { assertFailsWith<DecompressionBombException> { codec.decompress(input, limits) } }
            val thread = Thread.currentThread().threadId()
            val before = bean.getThreadAllocatedBytes(thread)
            assertFailsWith<DecompressionBombException> { codec.decompress(input, limits) }
            val allocated = bean.getThreadAllocatedBytes(thread) - before
            println("${codec.id}: $allocated allocated bytes rejecting ${data.size} output bytes under a 1024-byte cap")
            assertTrue(allocated < 100_000, "${codec.id} allocated $allocated bytes under a 1 KiB output cap")
        }
    }

    private fun deflate(data: ByteArray, level: Int, strategy: Int, raw: Boolean): ByteArray {
        val def = Deflater(level, raw)
        try {
            def.setStrategy(strategy)
            def.setInput(data)
            def.finish()
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!def.finished()) {
                val count = def.deflate(buffer)
                bytes.write(buffer, 0, count)
            }
            return bytes.toByteArray()
        } finally {
            def.end()
        }
    }

    private fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        GZIPOutputStream(bytes).use { it.write(data) }
    }.toByteArray()

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
