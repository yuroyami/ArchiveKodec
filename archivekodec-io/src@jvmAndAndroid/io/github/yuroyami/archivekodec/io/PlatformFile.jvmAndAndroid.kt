package io.github.yuroyami.archivekodec.io

import io.github.yuroyami.archivekodec.ArchiveIOException
import java.io.IOException
import java.io.RandomAccessFile

internal actual fun openPlatformFile(path: String): PlatformFile {
    val file = try {
        RandomAccessFile(path, "r")
    } catch (error: IOException) {
        throw ArchiveIOException("cannot open $path: ${error.message}", error)
    }
    return try {
        JavaFile(file, file.length())
    } catch (error: IOException) {
        file.close()
        throw ArchiveIOException("cannot read the size of $path: ${error.message}", error)
    }
}

private class JavaFile(private val file: RandomAccessFile, override val size: Long) : PlatformFile {
    override fun readAt(offset: Long, into: ByteArray, intoOffset: Int, length: Int): Int {
        file.seek(offset)
        return file.read(into, intoOffset, length)
    }

    override fun close() = file.close()
}
