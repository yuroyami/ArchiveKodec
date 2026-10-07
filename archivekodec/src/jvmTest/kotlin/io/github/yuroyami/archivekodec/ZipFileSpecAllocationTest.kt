package io.github.yuroyami.archivekodec

import com.sun.management.ThreadMXBean
import io.github.yuroyami.archivekodec.archive.zip.ZipMethod
import io.github.yuroyami.archivekodec.archive.zip.ZipWriter
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

class ZipFileSpecAllocationTest {
    @Test
    fun writingStoredPayloadDoesNotRepeatedlyCloneThePublicDataGetter() {
        val data = ByteArray(4 * 1024 * 1024) { it.toByte() }
        val files = listOf(ZipWriter.FileSpec("a", data, ZipMethod.STORED))
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        if (!bean.isThreadAllocatedMemorySupported) return
        bean.isThreadAllocatedMemoryEnabled = true
        repeat(3) { ZipWriter.write(files) }
        val thread = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(thread)
        val encoded = ZipWriter.write(files)
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertTrue(encoded.size > data.size)
        // Covers output assembly/growth plus the returned array, with headroom.
        // Repeated snapshots for CRC, compression and size fields exceed this.
        assertTrue(allocated < 6L * data.size, "Stored writer allocated $allocated bytes for ${data.size} payload bytes")
    }
}
