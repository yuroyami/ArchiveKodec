package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveIOException
import io.github.yuroyami.archivekodec.ArchiveUnsupportedException

/** Node's `fs` module, or null outside Node (a browser). */
private val nodeFs: dynamic = js(
    "(typeof process !== 'undefined' && process.versions && process.versions.node) ? " +
        "(typeof process.getBuiltinModule === 'function' ? process.getBuiltinModule('fs') : eval('require')('fs')) : null",
)

internal actual fun openPlatformFile(path: String): PlatformFile {
    val fs = nodeFs ?: throw ArchiveUnsupportedException("FileRandomAccessSource needs Node.js; a browser has no file system to open")
    val fd: Int = try {
        fs.openSync(path, "r") as Int
    } catch (error: Throwable) {
        throw ArchiveIOException("cannot open $path: ${error.message}", error)
    }
    val size: Long = try {
        (fs.fstatSync(fd).size as Double).toLong()
    } catch (error: Throwable) {
        fs.closeSync(fd)
        throw ArchiveIOException("cannot read the size of $path: ${error.message}", error)
    }
    return NodeFile(fs, fd, size)
}

private class NodeFile(private val fs: dynamic, private val fd: Int, override val size: Long) : PlatformFile {
    override fun readAt(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int =
        // A Kotlin ByteArray is an Int8Array, which fs.readSync fills in place.
        (fs.readSync(fd, into, intoOffset, length, offset.toDouble()) as Number).toInt()

    override fun close() {
        fs.closeSync(fd)
    }
}
