package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveIOException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.O_RDONLY
import platform.posix.errno
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
internal actual fun openPlatformFile(path: String): PlatformFile {
    val fd = open(path, O_RDONLY)
    if (fd < 0) throw ArchiveIOException("cannot open $path: ${strerror(errno)?.toKString()}", IllegalStateException("errno $errno"))
    val size = memScoped {
        val info = alloc<stat>()
        if (fstat(fd, info.ptr) != 0) {
            val error = errno
            platform.posix.close(fd)
            throw ArchiveIOException("cannot read the size of $path: ${strerror(error)?.toKString()}", IllegalStateException("errno $error"))
        }
        info.st_size.convert<Long>()
    }
    return PosixFile(fd, size)
}

@OptIn(ExperimentalForeignApi::class)
private class PosixFile(private val fd: Int, override val size: Long) : PlatformFile {
    override fun readAt(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
        val n = into.usePinned { pinned ->
            pread(fd, pinned.addressOf(intoOffset), length.convert(), offset.convert()).convert<Long>()
        }
        if (n < 0) throw ArchiveIOException("read failed: ${strerror(errno)?.toKString()}", IllegalStateException("errno $errno"), offset)
        return n.toInt()
    }

    override fun close() {
        platform.posix.close(fd)
    }
}
