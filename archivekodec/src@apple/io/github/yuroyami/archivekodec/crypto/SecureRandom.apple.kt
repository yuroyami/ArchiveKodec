@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.archivekodec.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.arc4random_buf

internal actual fun secureRandomBytes(count: Int): ByteArray {
    val out = ByteArray(count)
    if (count > 0) out.usePinned { arc4random_buf(it.addressOf(0), count.convert()) }
    return out
}
