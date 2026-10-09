package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException

/**
 * Where streamed output goes: [Codec.decompress] with a sink writes its output in
 * chunks instead of returning one array, so output larger than memory can be written to
 * a file or a network stream. `archivekodec-io` adapts a kotlinx-io `RawSink` to it.
 */
public fun interface ByteSink {
    /**
     * Take `bytes[offset, offset + length)`. The array is reused after this call returns,
     * so copy what you keep. A failure here stops the decoding and propagates.
     *
     * @throws ArchiveException if the sink cannot take the bytes; other exceptions propagate unchanged.
     */
    @Throws(ArchiveException::class)
    public fun write(bytes: ByteArray, offset: Int, length: Int)
}
