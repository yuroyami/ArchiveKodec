package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveIOException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.ERROR_HANDLE_EOF
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.GENERIC_READ
import platform.windows.GetFileSizeEx
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.LARGE_INTEGER
import platform.windows.OPEN_EXISTING
import platform.windows.OVERLAPPED
import platform.windows.ReadFile
import platform.windows.DWORDVar

@OptIn(ExperimentalForeignApi::class)
internal actual fun openPlatformFile(path: String): PlatformFile {
    val handle = CreateFileW(path, GENERIC_READ, FILE_SHARE_READ.convert(), null, OPEN_EXISTING.convert(), FILE_ATTRIBUTE_NORMAL.convert(), null)
    if (handle == null || handle == INVALID_HANDLE_VALUE) {
        val error = GetLastError()
        throw ArchiveIOException("cannot open $path: Windows error $error", IllegalStateException("GetLastError $error"))
    }
    val size = memScoped {
        val value = alloc<LARGE_INTEGER>()
        if (GetFileSizeEx(handle, value.ptr) == 0) {
            val error = GetLastError()
            CloseHandle(handle)
            throw ArchiveIOException("cannot read the size of $path: Windows error $error", IllegalStateException("GetLastError $error"))
        }
        value.QuadPart
    }
    return WindowsFile(handle, size)
}

@OptIn(ExperimentalForeignApi::class)
private class WindowsFile(private val handle: HANDLE, override val size: Long) : PlatformFile {
    override fun readAt(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int = memScoped {
        val overlapped = alloc<OVERLAPPED>()
        overlapped.Offset = (offset and 0xFFFFFFFFL).convert()
        overlapped.OffsetHigh = (offset ushr 32).convert()
        val read = alloc<DWORDVar>()
        val ok = into.usePinned { pinned ->
            ReadFile(handle, pinned.addressOf(intoOffset), length.convert(), read.ptr, overlapped.ptr)
        }
        if (ok == 0) {
            val error = GetLastError()
            if (error == ERROR_HANDLE_EOF.convert<UInt>()) return@memScoped 0
            throw ArchiveIOException("read failed: Windows error $error", IllegalStateException("GetLastError $error"), offset)
        }
        read.value.toInt()
    }

    override fun close() {
        CloseHandle(handle)
    }
}
