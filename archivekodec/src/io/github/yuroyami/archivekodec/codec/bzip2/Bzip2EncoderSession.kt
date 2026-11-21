package io.github.yuroyami.archivekodec.codec.bzip2

import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.EncoderSession

/**
 * Streaming bzip2: each full block of input becomes one complete bzip2 stream, and the streams
 * are concatenated, as pbzip2 writes them; `bzip2 -d` and every other decoder join them into
 * one output. Memory stays near one block. A stream costs about 14 bytes more than a block
 * inside a single stream would.
 */
internal class Bzip2EncoderSession(private val level: Int, private val sink: ByteSink) : EncoderSession {
    // The encoder's own block limit for this level, so each piece is exactly one block.
    private val block = ByteArray(level * 100_000 - 19)
    private var size = 0
    private var wroteAny = false
    private var finished = false

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "the session is finished" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset) { "invalid slice" }
        var at = offset
        val end = offset + length
        while (at < end) {
            val n = minOf(end - at, block.size - size)
            bytes.copyInto(block, size, at, at + n)
            size += n
            at += n
            if (size == block.size) emit()
        }
    }

    /** bzip2 has no partial flush: everything up to the last full block is already out. */
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
        val out = Bzip2Encoder.compress(block.copyOf(size), level)
        sink.write(out, 0, out.size)
        size = 0
        wroteAny = true
    }
}
