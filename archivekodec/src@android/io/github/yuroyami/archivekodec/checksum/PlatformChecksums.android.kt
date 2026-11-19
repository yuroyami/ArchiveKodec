package io.github.yuroyami.archivekodec.checksum

/** Adapts a `java.util.zip` checksum, whose `getValue` is already unsigned 32-bit. */
private class JdkChecksum(private val delegate: java.util.zip.Checksum) : Checksum {
    override fun update(data: ByteArray, offset: Int, length: Int) {
        checkedChecksumEnd(data.size, offset, length)
        delegate.update(data, offset, length)
    }

    override fun value(): Long = delegate.value

    override fun reset() = delegate.reset()
}

internal actual fun platformCrc32(): Checksum? = JdkChecksum(java.util.zip.CRC32())

internal actual fun platformAdler32(): Checksum? = JdkChecksum(java.util.zip.Adler32())
