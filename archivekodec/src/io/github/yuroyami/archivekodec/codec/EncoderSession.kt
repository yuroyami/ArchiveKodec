package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException

/**
 * A streaming encoder: [write] uncompressed bytes in pieces of any size, and the compressed
 * stream goes to the [ByteSink] the session was opened with as it is produced. [finish] ends
 * the stream; a session is single use and not thread-safe. [Codec.encoder] opens one.
 */
public interface EncoderSession {
    /**
     * Encode `bytes[offset, offset + length)`. The array is not kept after this returns.
     * @throws ArchiveException if the sink fails.
     * @throws IllegalStateException after [finish].
     */
    @Throws(ArchiveException::class)
    public fun write(bytes: ByteArray, offset: Int, length: Int)

    /** Encode all of [bytes]. */
    @Throws(ArchiveException::class)
    public fun write(bytes: ByteArray): Unit = write(bytes, 0, bytes.size)

    /**
     * Make everything written so far decodable from what the sink has received, for codecs
     * that can (a DEFLATE sync flush); others write nothing until [finish].
     * @throws ArchiveException if the sink fails.
     */
    @Throws(ArchiveException::class)
    public fun flush()

    /**
     * End the stream: encode what is buffered and write the trailer. Later calls do nothing.
     * @throws ArchiveException if the sink fails.
     */
    @Throws(ArchiveException::class)
    public fun finish()
}

/**
 * The session [Codec.encoder] opens for a codec without a streaming encoder: it keeps the
 * whole input and compresses it at [finish], so it needs memory for both.
 */
internal class BufferingEncoderSession(
    private val encode: (ByteArray) -> ByteArray,
    private val sink: ByteSink,
) : EncoderSession {
    private val input = io.github.yuroyami.archivekodec.util.ByteArrayBuilder(4096)
    private var finished = false

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "the session is finished" }
        input.append(bytes, offset, length)
    }

    override fun flush() {}

    override fun finish() {
        if (finished) return
        finished = true
        val out = encode(input.takeByteArray())
        sink.write(out, 0, out.size)
    }
}
