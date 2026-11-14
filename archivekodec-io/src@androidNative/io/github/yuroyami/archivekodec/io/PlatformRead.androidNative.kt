package io.github.yuroyami.archivekodec.io

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import platform.posix.pread64

// pread64 takes a 64-bit off64_t on every Android ABI. pread takes off_t, which is 32-bit on
// androidNativeArm32 and androidNativeX86 and would cut offsets past 2 GiB. UnsafeNumber: size_t width varies.
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun preadAt(fd: Int, buffer: CPointer<ByteVar>, length: Int, offset: Long): Long =
    pread64(fd, buffer, length.convert(), offset).convert()
