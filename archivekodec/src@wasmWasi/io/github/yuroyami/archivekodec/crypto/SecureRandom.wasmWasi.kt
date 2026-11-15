@file:OptIn(ExperimentalWasmInterop::class, UnsafeWasmMemoryApi::class)

package io.github.yuroyami.archivekodec.crypto

import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

@WasmImport("wasi_snapshot_preview1", "random_get")
private external fun wasiRandomGet(buffer: Int, length: Int): Int

internal actual fun secureRandomBytes(count: Int): ByteArray {
    if (count == 0) return ByteArray(0)
    return withScopedMemoryAllocator { allocator ->
        val pointer = allocator.allocate(count)
        val errno = wasiRandomGet(pointer.address.toInt(), count)
        check(errno == 0) { "WASI random_get failed with errno $errno" }
        ByteArray(count) { (pointer + it).loadByte() }
    }
}
