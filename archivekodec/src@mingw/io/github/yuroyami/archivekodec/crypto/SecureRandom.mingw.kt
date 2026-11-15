@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.archivekodec.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.windows.BCryptGenRandom

// BCRYPT_USE_SYSTEM_PREFERRED_RNG: use the system generator, so no algorithm handle is needed.
private const val SYSTEM_PREFERRED_RNG = 2

internal actual fun secureRandomBytes(count: Int): ByteArray {
    val out = ByteArray(count)
    if (count == 0) return out
    val status = out.usePinned { BCryptGenRandom(null, it.addressOf(0).reinterpret(), count.convert(), SYSTEM_PREFERRED_RNG.convert()) }
    check(status == 0) { "BCryptGenRandom failed with status $status" }
    return out
}
