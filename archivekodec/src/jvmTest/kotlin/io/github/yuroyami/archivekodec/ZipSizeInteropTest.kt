package io.github.yuroyami.archivekodec

import com.sun.management.ThreadMXBean
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.zip.ZipReader
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipSizeInteropTest {
    @Test
    fun tinyDeclaredSizeStopsForeignStoredAndDynamicDeflateBeforeExpansion() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        bean.isThreadAllocatedMemoryEnabled = true
        for (level in listOf(0, 9)) {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                zip.setLevel(level)
                zip.putNextEntry(ZipEntry("bounded.bin"))
                zip.write(ByteArray(if (level == 0) 65536 else 4 * 1024 * 1024) { 65 })
                zip.closeEntry()
            }
            val bytes = output.toByteArray()
            val central = bytes.indices.first { i -> i <= bytes.size - 4 &&
                bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                bytes[i + 2] == 1.toByte() && bytes[i + 3] == 2.toByte() }
            // JDK data descriptor and CRC stay intact; only the authoritative size is forged.
            repeat(4) { bytes[central + 24 + it] = if (it == 0) 1 else 0 }
            val reader = ZipReader(ByteArrayRandomAccessSource(bytes))
            val entry = reader.entries().single()
            repeat(3) { assertFailsWith<ArchiveFormatException> { reader.read(entry) } }
            val thread = Thread.currentThread().threadId()
            val before = bean.getThreadAllocatedBytes(thread)
            assertFailsWith<ArchiveFormatException> { reader.read(entry) }
            val allocated = bean.getThreadAllocatedBytes(thread) - before
            // Compressed payload copying is expected; allocating the expanded array is not.
            assertTrue(allocated < entry.compressedSize + 128 * 1024,
                "level $level: allocated $allocated for ${entry.compressedSize} compressed bytes")
        }
    }
}
