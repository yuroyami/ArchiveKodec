@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.archivekodec.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.arc4random

// arc4random() returns 32 bits on every target. arc4random_buf takes a size_t, whose width
// differs between the 32-bit and 64-bit targets, so the shared metadata compile rejects it.
internal actual fun secureRandomBytes(count: Int): ByteArray {
    val out = ByteArray(count)
    var i = 0
    while (i < count) {
        val word = arc4random().toInt()
        val take = minOf(4, count - i)
        for (k in 0 until take) out[i + k] = (word ushr (8 * k)).toByte()
        i += take
    }
    return out
}
