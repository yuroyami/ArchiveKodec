package io.github.yuroyami.archivekodec.codec.deflate

import io.github.yuroyami.archivekodec.checksum.Adler32
import io.github.yuroyami.archivekodec.checksum.Crc32
import io.github.yuroyami.archivekodec.codec.ByteSink
import io.github.yuroyami.archivekodec.codec.EncoderSession
import io.github.yuroyami.archivekodec.wrapper.GzipHeader

/**
 * Streaming DEFLATE: input is encoded in chunks of [CHUNK] bytes, each primed with the last
 * 32 KiB before it as a dictionary, so matches still reach back across chunks, and each but the
 * last ending with a sync flush. Memory stays near one chunk whatever the input size. The
 * output is one valid DEFLATE stream; it is slightly larger than a whole-array encode because
 * each chunk starts its own blocks.
 */
internal open class DeflateEncoderSession(
    private val options: DeflateOptions,
    private val sink: ByteSink,
) : EncoderSession {
    private val window = 1 shl options.windowBits
    private var chunk = ByteArray(CHUNK)
    private var size = 0
    private var history: ByteArray? = options.dictionaryBytes
    private var finished = false

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(!finished) { "the session is finished" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size && length <= bytes.size - offset) { "invalid slice" }
        consumed(bytes, offset, length)
        var at = offset
        val end = offset + length
        while (at < end) {
            val n = minOf(end - at, chunk.size - size)
            bytes.copyInto(chunk, size, at, at + n)
            size += n
            at += n
            if (size == chunk.size) encode(final = false)
        }
    }

    override fun flush() {
        check(!finished) { "the session is finished" }
        if (size > 0) encode(final = false)
    }

    override fun finish() {
        if (finished) return
        encode(final = true)
        finished = true
        trailer()
    }

    /** Hear about input before it is encoded, for a wrapper's checksum. */
    protected open fun consumed(bytes: ByteArray, offset: Int, length: Int) {}

    /** Write what follows the DEFLATE stream. */
    protected open fun trailer() {}

    private fun encode(final: Boolean) {
        val input = chunk.copyOf(size)
        val chunkOptions = history?.let { previous -> options.copy { dictionary = previous } } ?: options
        val out = deflateChunk(input, chunkOptions, final)
        sink.write(out, 0, out.size)
        // The next chunk may match into the last window of this one, and of the history before it.
        val joined = (history ?: ByteArray(0)) + input
        history = if (joined.isEmpty()) null else joined.copyOfRange(maxOf(0, joined.size - window), joined.size)
        size = 0
    }

    companion object {
        const val CHUNK = 1 shl 20
    }
}

/** Streaming zlib: the two-byte header, DEFLATE chunks and the Adler-32 trailer. */
internal class ZlibEncoderSession(options: DeflateOptions, private val sink: ByteSink) : DeflateEncoderSession(options, sink) {
    private val adler = Adler32()

    init {
        require(options.dictionaryBytes == null) { "a streamed zlib stream takes no preset dictionary" }
        val cmf = ((options.windowBits - 8) shl 4) or 8
        val level = when { options.level < 2 -> 0; options.level < 6 -> 1; options.level == 6 -> 2; else -> 3 }
        var flg = level shl 6
        flg += 31 - (cmf * 256 + flg) % 31
        sink.write(byteArrayOf(cmf.toByte(), flg.toByte()), 0, 2)
    }

    override fun consumed(bytes: ByteArray, offset: Int, length: Int) = adler.update(bytes, offset, length)

    override fun trailer() {
        val v = adler.value()
        sink.write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()), 0, 4)
    }
}

/** Streaming gzip: one member with [header], DEFLATE chunks, then the CRC-32 and ISIZE trailer. */
internal class GzipEncoderSession(options: DeflateOptions, header: GzipHeader, private val sink: ByteSink) : DeflateEncoderSession(options, sink) {
    private val crc = Crc32()
    private var total = 0L

    init {
        require(options.dictionaryBytes == null) { "gzip takes no preset dictionary" }
        val bytes = io.github.yuroyami.archivekodec.wrapper.Gzip.headerBytes(header, options)
        sink.write(bytes, 0, bytes.size)
    }

    override fun consumed(bytes: ByteArray, offset: Int, length: Int) {
        crc.update(bytes, offset, length)
        total += length
    }

    override fun trailer() {
        val c = crc.value()
        val out = ByteArray(8)
        for (i in 0 until 4) out[i] = (c ushr (8 * i)).toByte()
        for (i in 0 until 4) out[4 + i] = (total ushr (8 * i)).toByte()
        sink.write(out, 0, 8)
    }
}
