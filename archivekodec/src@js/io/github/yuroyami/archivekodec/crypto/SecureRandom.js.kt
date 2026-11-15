package io.github.yuroyami.archivekodec.crypto

// A Kotlin/JS ByteArray is an Int8Array. getRandomValues fills at most 65,536 bytes per call.
internal actual fun secureRandomBytes(count: Int): ByteArray {
    val out = ByteArray(count)
    val crypto: dynamic = js("globalThis.crypto")
    check(crypto != null && crypto != undefined) { "globalThis.crypto is missing; Node.js 19 or later and every current browser have it" }
    var offset = 0
    while (offset < count) {
        val end = minOf(count, offset + 65536)
        crypto.getRandomValues(out.asDynamic().subarray(offset, end))
        offset = end
    }
    return out
}
