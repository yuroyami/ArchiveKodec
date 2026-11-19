package io.github.yuroyami.archivekodec.codec.deflate

import com.sun.management.ThreadMXBean
import io.github.yuroyami.archivekodec.security.DecompressionBombException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Zlib
import java.lang.management.ManagementFactory
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** On the JVM the fast paths are `java.util.zip`, and they step aside where the pure code must decide. */
class PlatformFlateJvmTest {

    private val text = "BT /F1 12 Tf 72 712 Td (A line of text) Tj ET\n".repeat(2000).encodeToByteArray()

    @Test
    fun theInflaterRunsOnValidStreamsAndStepsAsideOnDamage() {
        val raw = Deflate.encode(text)
        assertContentEquals(text, assertNotNull(platformInflate(raw, 0, raw.size, false, 0, Long.MAX_VALUE)).output)
        val zlib = Zlib.compress(text)
        val result = assertNotNull(platformInflate(zlib + byteArrayOf(5, 5), 0, zlib.size + 2, true, 0, Long.MAX_VALUE))
        assertEquals(zlib.size, result.consumed)
        assertNull(platformInflate(raw, 0, raw.size / 2, false, 0, Long.MAX_VALUE), "cut short")
        assertNull(platformInflate(raw, 0, raw.size, false, 0, text.size - 1L), "over the limit")
        assertContentEquals(text, assertNotNull(platformInflate(raw, 0, raw.size, false, 0, text.size.toLong())).output)
        val dictionary = Zlib.compress(text, DeflateOptions { dictionary = "BT".encodeToByteArray() })
        assertNull(platformInflate(dictionary, 0, dictionary.size, true, 0, Long.MAX_VALUE), "dictionary")
        assertContentEquals(ByteArray(0), assertNotNull(platformInflate(Deflate.encode(ByteArray(0)), 0, 2, false, 0, 0)).output)
    }

    @Test
    fun theRatioLimitCapsThePlatformOutputBeforeItIsMaterialized() {
        // 64 MiB of zeros in about 64 KB: far past a 10:1 ratio, well under the size limit.
        val zeros = ByteArray(64 shl 20)
        val deflater = Deflater(9, true)
        deflater.setInput(zeros)
        deflater.finish()
        val buffer = ByteArray(1 shl 20)
        val stream = buffer.copyOf(deflater.deflate(buffer))
        deflater.end()
        val options = InflateOptions {
            limits = ArchiveLimits { maxCompressionRatio = 10 }
            usePlatformInflater = true
        }
        assertFailsWith<DecompressionBombException> { Inflate.inflate(stream, options) }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        assertFailsWith<DecompressionBombException> { Inflate.inflate(stream, options) }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        // The ratio allows about 10 times the stream; the output was 64 MiB before the fix.
        assertTrue(allocated < 40L * stream.size + (4 shl 20), "allocated $allocated bytes for a ${stream.size}-byte stream")
    }

    @Test
    fun theDeflaterIsJavaUtilZip() {
        val options = DeflateOptions { level = 9; usePlatformDeflater = true }
        val deflater = Deflater(9, true)
        deflater.setInput(text)
        deflater.finish()
        val buffer = ByteArray(text.size)
        val n = deflater.deflate(buffer)
        deflater.end()
        assertContentEquals(buffer.copyOf(n), Deflate.encode(text, options))
        // RLE has no java.util.zip strategy, so the pure encoder runs.
        assertContentEquals(Deflate.encode(text, DeflateOptions { strategy = DeflateStrategy.RLE }),
            Deflate.encode(text, DeflateOptions { strategy = DeflateStrategy.RLE; usePlatformDeflater = true }))
    }
}
