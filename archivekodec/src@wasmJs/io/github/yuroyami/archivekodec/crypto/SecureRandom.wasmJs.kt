@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.yuroyami.archivekodec.crypto

@JsFun("() => (typeof globalThis.crypto === 'object' && globalThis.crypto !== null) ? 1 : 0")
private external fun hasCrypto(): Int

@JsFun("(n) => { const a = new Uint8Array(n); for (let o = 0; o < n; o += 65536) globalThis.crypto.getRandomValues(a.subarray(o, Math.min(n, o + 65536))); return a; }")
private external fun randomArray(count: Int): JsAny

@JsFun("(a, i) => a[i]")
private external fun byteAt(array: JsAny, index: Int): Int

internal actual fun secureRandomBytes(count: Int): ByteArray {
    if (count == 0) return ByteArray(0)
    check(hasCrypto() == 1) { "globalThis.crypto is missing; Node.js 19 or later and every current browser have it" }
    val array = randomArray(count)
    return ByteArray(count) { byteAt(array, it).toByte() }
}
