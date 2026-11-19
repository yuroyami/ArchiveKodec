package io.github.yuroyami.archivekodec

import com.sun.management.ThreadMXBean
import io.github.yuroyami.archivekodec.archive.ByteArrayRandomAccessSource
import io.github.yuroyami.archivekodec.archive.squashfs.SquashfsReader
import io.github.yuroyami.archivekodec.archive.use
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.wrapper.Gzip
import java.lang.management.ManagementFactory
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A SquashFS file's block extents are checked before its output is allocated. */
@OptIn(ExperimentalEncodingApi::class)
class SquashfsAllocationTest {
    // mksquashfs 4.6.1 -noI -noD -noF -noX -no-fragments of a 200 MB sparse file, whose first
    // block-list word was then patched to claim a 16 MiB compressed block: past the 8 KiB image.
    private val image = Gzip.decompress(Base64.decode("H4sIAAAAAAAC/+3ZMQrCQBCF4ZmYQiGFlhEET2Cdo1gGQdBG0XQ25hr2nsKbiEcQKysr3biza/AI+n+wzGxeMgtpd1FtqkQC36kM5KIqqeuL3Cc69PVh+6cp7cuTPT9bvVqdHnpy1DC/acp4Wp35SfssZM3E1nQBAAAAAAAAAAD4JSq3eG+StOrYrY5bo/rzZki7sptv19Vktlz1Y1x8Xc2k7+Se84sB4I+9APKsfNAAIAAA"), ArchiveLimits.UNBOUNDED)

    @Test
    fun aBlockOutsideTheImageFailsBeforeTheFileIsAllocated() {
        SquashfsReader(ByteArrayRandomAccessSource(image)).use { reader ->
            val file = reader.entries().single { !it.isDirectory }
            assertEquals(209_715_200L, file.size)
            assertFailsWith<ArchiveFormatException> { reader.read(file) }
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            bean.isThreadAllocatedMemoryEnabled = true
            val thread = Thread.currentThread().threadId()
            val before = bean.getThreadAllocatedBytes(thread)
            assertFailsWith<ArchiveFormatException> { reader.read(file) }
            val allocated = bean.getThreadAllocatedBytes(thread) - before
            assertTrue(allocated < 16L shl 20, "allocated $allocated bytes before rejecting the file")
        }
    }
}
