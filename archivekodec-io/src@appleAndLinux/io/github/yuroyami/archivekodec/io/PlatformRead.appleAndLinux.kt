package io.github.yuroyami.archivekodec.io

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import platform.posix.pread

// off_t is 64-bit on every target of this group. UnsafeNumber: size_t is 32-bit on watchosArm32 only.
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun preadAt(fd: Int, buffer: CPointer<ByteVar>, length: Int, offset: Long): Long =
    pread(fd, buffer, length.convert(), offset.convert()).convert()
