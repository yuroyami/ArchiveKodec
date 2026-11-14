package io.github.yuroyami.archivekodec.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.close
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.open
import platform.posix.pwrite
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Reads at offsets past 2 GiB and past 4 GiB return the bytes stored there (#140). The file is
 * sparse, so it takes a few kilobytes on disk although its size is above 4 GiB.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
class LargeOffsetTest {
    private val markers = mapOf(
        (3L shl 30) + 5 to "past 2 GiB".encodeToByteArray(),
        (4L shl 30) + 7 to "past 4 GiB".encodeToByteArray(),
    )

    @Test
    fun readsPastTwoAndFourGibibytesReturnTheStoredBytes() {
        val path = "${getenv("TMPDIR")?.toKString()?.trimEnd('/') ?: "/tmp"}/archivekodec-offset-${getpid()}.bin"
        val fd = open(path, O_CREAT or O_RDWR or O_TRUNC, 0x180)
        check(fd >= 0) { "cannot create $path" }
        try {
            for ((offset, bytes) in markers) {
                val written = bytes.usePinned { pwrite(fd, it.addressOf(0), bytes.size.convert(), offset.convert()) }
                check(written.convert<Long>() == bytes.size.toLong()) { "cannot write at $offset" }
            }
        } finally {
            close(fd)
        }
        try {
            val source = FileRandomAccessSource(path)
            try {
                assertEquals((4L shl 30) + 7 + 10, source.size)
                for ((offset, bytes) in markers) {
                    val read = ByteArray(bytes.size)
                    assertEquals(bytes.size, source.read(offset, read, 0, read.size))
                    assertContentEquals(bytes, read, "bytes at $offset")
                }
                // A 32-bit offset would wrap 4 GiB + 7 to 7. The start of the file holds zeros.
                val start = ByteArray(10)
                assertEquals(10, source.read(7, start, 0, start.size))
                assertContentEquals(ByteArray(10), start)
            } finally {
                source.close()
            }
        } finally {
            unlink(path)
        }
    }
}
