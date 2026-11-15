@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.archivekodec.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.linux.SYS_getrandom
import platform.posix.EINTR
import platform.posix.ENOSYS
import platform.posix.O_CLOEXEC
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open
import platform.posix.read
import platform.posix.syscall

// The getrandom system call goes through syscall(): the glibc of the Kotlin/Native Linux sysroot predates its wrapper.
// It can return fewer bytes than asked, or fail with EINTR, so loop until the buffer is full.
internal actual fun secureRandomBytes(count: Int): ByteArray {
    val out = ByteArray(count)
    if (count == 0) return out
    out.usePinned { pinned ->
        var filled = 0
        while (filled < count) {
            val got = syscall(SYS_getrandom.toLong(), pinned.addressOf(filled), (count - filled).toLong(), 0L)
            if (got >= 0) {
                filled += got.toInt()
                continue
            }
            val error = errno
            when (error) {
                EINTR -> continue
                // Kernels before 3.17 have no getrandom.
                ENOSYS -> filled = fillFromUrandom(pinned, filled, count)
                else -> throw IllegalStateException("getrandom failed with errno $error")
            }
        }
    }
    return out
}

/** Fill bytes [from] until [count] of [pinned] from /dev/urandom, and return [count]. */
private fun fillFromUrandom(pinned: Pinned<ByteArray>, from: Int, count: Int): Int {
    val fd = open("/dev/urandom", O_RDONLY or O_CLOEXEC)
    check(fd >= 0) { "cannot open /dev/urandom: errno $errno" }
    try {
        var filled = from
        while (filled < count) {
            val got = read(fd, pinned.addressOf(filled), (count - filled).convert())
            if (got > 0) {
                filled += got.toInt()
            } else {
                val error = errno
                if (got < 0 && error == EINTR) continue
                throw IllegalStateException("cannot read /dev/urandom: errno $error")
            }
        }
        return filled
    } finally {
        close(fd)
    }
}
