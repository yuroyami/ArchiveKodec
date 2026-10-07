package io.github.yuroyami.archivekodec

import com.sun.management.ThreadMXBean
import io.github.yuroyami.archivekodec.checksum.XxHash32
import io.github.yuroyami.archivekodec.codec.lz4.Lz4
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Lz4ChecksumAllocationTest {
    @Test
    fun invalidContentChecksumDoesNotCopyTheExpandedOutput() {
        val data = ByteArray(4 * 1024 * 1024) { 65 }
        val headerAndBlocks = Lz4.frameCompress(data)
        headerAndBlocks[4] = (headerAndBlocks[4].toInt() or 4).toByte()
        headerAndBlocks[6] = (XxHash32.hash(headerAndBlocks, 4, 2) ushr 8).toByte()
        val checksum = XxHash32.hash(data)
        val valid = headerAndBlocks + ByteArray(4) { (checksum ushr (it * 8)).toByte() }
        val corrupt = valid.copyOf()
        corrupt[corrupt.lastIndex] = (corrupt.last().toInt() xor 1).toByte()
        assertContentEquals(data, Lz4.frameDecompress(valid))
        repeat(3) {
            Lz4.frameDecompress(valid)
            assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(corrupt) }
        }
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        val thread = Thread.currentThread().threadId()
        fun allocated(block: () -> Unit): Long {
            val before = bean.getThreadAllocatedBytes(thread)
            block()
            return bean.getThreadAllocatedBytes(thread) - before
        }
        val successful = allocated { Lz4.frameDecompress(valid) }
        val rejected = allocated { assertFailsWith<ArchiveFormatException> { Lz4.frameDecompress(corrupt) } }
        assertTrue(successful - rejected > data.size / 2,
            "Rejected output was copied: valid=$successful, rejected=$rejected, output=${data.size}")
    }
}
