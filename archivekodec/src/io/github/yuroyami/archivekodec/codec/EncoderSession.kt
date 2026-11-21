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

/**
 * The session for a format whose frames or members may be concatenated, which its decoders
 * join into one output: each [chunk] bytes of input is encoded as one complete frame as soon
 * as it is full, so memory stays near one chunk. Matches do not reach across frames, which costs
 * a little compression against one frame of everything. Empty input still gets one frame.
 */
internal class ChunkedEncoderSession(
    chunk: Int,
    private val encode: (ByteArray) -> ByteArray,
    private val sink: ByteSink,
) : EncoderSession {
    private val buffer = ByteArray(chunk)
    private var size = 0
    private var wroteAny = false
    private var finished = false

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "the session is finished" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset) { "invalid slice" }
        var at = offset
        val end = offset + length
        while (at < end) {
            val n = minOf(end - at, buffer.size - size)
            bytes.copyInto(buffer, size, at, at + n)
            size += n
            at += n
            if (size == buffer.size) emit()
        }
    }

    /** Ends the current frame early, so everything written so far is decodable. */
    override fun flush() {
        check(!finished) { "the session is finished" }
        if (size > 0) emit()
    }

    override fun finish() {
        if (finished) return
        finished = true
        if (size > 0 || !wroteAny) emit()
    }

    private fun emit() {
        val out = encode(buffer.copyOf(size))
        sink.write(out, 0, out.size)
        size = 0
        wroteAny = true
    }
}
