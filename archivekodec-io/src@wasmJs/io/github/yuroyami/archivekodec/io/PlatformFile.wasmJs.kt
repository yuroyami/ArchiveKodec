@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException

@JsFun("() => (typeof process !== 'undefined' && process.versions && process.versions.node && typeof process.getBuiltinModule === 'function') ? 1 : 0")
private external fun hasNodeFs(): Int

/** Open [path]; return the descriptor, or -1 with the message left for [lastError]. */
@JsFun(
    "(path) => { try { globalThis.__akError = ''; return process.getBuiltinModule('fs').openSync(path, 'r'); } " +
        "catch (e) { globalThis.__akError = String(e && e.message); return -1; } }",
)
private external fun openFile(path: String): Int

@JsFun("(fd) => process.getBuiltinModule('fs').fstatSync(fd).size")
private external fun fileSize(fd: Int): Double

@JsFun("() => String(globalThis.__akError || '')")
private external fun lastError(): String

/** Read up to [length] bytes at [position] into a scratch buffer kept on the JS side; return the count. */
@JsFun(
    "(fd, length, position) => { const b = globalThis.__akBuffer = new Uint8Array(length); " +
        "return process.getBuiltinModule('fs').readSync(fd, b, 0, length, position); }",
)
private external fun readIntoScratch(fd: Int, length: Int, position: Double): Int

@JsFun("(i) => globalThis.__akBuffer[i]")
private external fun scratchByte(i: Int): Int

@JsFun("(fd) => process.getBuiltinModule('fs').closeSync(fd)")
private external fun closeFile(fd: Int)

internal actual fun openPlatformFile(path: String): PlatformFile {
    if (hasNodeFs() == 0) throw ArchiveUnsupportedException("FileRandomAccessSource needs Node.js 20.16 or later; a browser has no file system to open")
    val fd = openFile(path)
    if (fd < 0) {
        val message = lastError()
        throw ArchiveIOException("cannot open $path: $message", IllegalStateException(message))
    }
    return WasmNodeFile(fd, fileSize(fd).toLong())
}

private class WasmNodeFile(private val fd: Int, override val size: Long) : PlatformFile {
    override fun readAt(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
        val n = readIntoScratch(fd, length, offset.toDouble())
        for (i in 0 until n) into[intoOffset + i] = scratchByte(i).toByte()
        return n
    }

    override fun close() = closeFile(fd)
}
