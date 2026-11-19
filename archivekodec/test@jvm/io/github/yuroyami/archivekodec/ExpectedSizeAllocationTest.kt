package io.github.yuroyami.archivekodec

import com.sun.management.ThreadMXBean
import io.github.yuroyami.archivekodec.codec.lzw.Lzw
import io.github.yuroyami.archivekodec.codec.lzw.LzwOptions
import io.github.yuroyami.archivekodec.codec.packbits.PackBits
import io.github.yuroyami.archivekodec.codec.packbits.PackBitsOptions
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A declared expected size is a claim: a short stream does not reserve it. */
class ExpectedSizeAllocationTest {
    private val bean = (ManagementFactory.getThreadMXBean() as ThreadMXBean).also { it.isThreadAllocatedMemoryEnabled = true }

    private fun allocated(block: () -> Unit): Long {
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        block()
        return bean.getThreadAllocatedBytes(thread) - before
    }

    @Test
    fun aShortStreamDoesNotReserveItsExpectedSize() {
        val text = "hello".encodeToByteArray()
        // As a GIF of 46341 x 46341 pixels would declare, with five bytes of data.
        val huge = 1_000_000_000L
        val lzw = Lzw.encode(text)
        val lzwOptions = LzwOptions { expectedSize = huge; keepPartialOutput = true }
        val packBits = PackBits.encode(text)
        val packBitsOptions = PackBitsOptions { expectedSize = huge; keepPartialOutput = true }
        repeat(2) { // the first round warms up
            val lzwBytes = allocated {
                val result = Lzw.decode(lzw, lzwOptions)
                assertContentEquals(text, result.output)
                assertTrue(result.error != null)
            }
            val packBitsBytes = allocated {
                val result = PackBits.decode(packBits, packBitsOptions)
                assertContentEquals(text, result.output)
                assertTrue(result.error != null)
            }
            assertTrue(lzwBytes < 16L shl 20, "LZW allocated $lzwBytes bytes")
            assertTrue(packBitsBytes < 16L shl 20, "PackBits allocated $packBitsBytes bytes")
        }
        // A stream that fills its expected size still decodes to exactly that size.
        val full = ByteArray(300_000) { (it % 7).toByte() }
        assertEquals(full.size, Lzw.decode(Lzw.encode(full), LzwOptions { expectedSize = full.size.toLong() }).output.size)
        assertContentEquals(full, PackBits.decode(PackBits.encode(full), PackBitsOptions { expectedSize = full.size.toLong() }).output)
    }
}
