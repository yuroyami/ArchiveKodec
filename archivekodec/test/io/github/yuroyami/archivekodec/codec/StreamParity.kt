package io.github.yuroyami.archivekodec.codec

import io.github.yuroyami.archivekodec.ArchiveException
import io.github.yuroyami.archivekodec.security.ArchiveLimits
import io.github.yuroyami.archivekodec.util.ByteArrayBuilder
import kotlin.test.assertEquals

/** Compares a codec's streamed decoder with its whole-array one: same bytes, same failures. */
internal object StreamParity {
    /** A source that hands out at most [step] bytes per read. */
    fun trickle(bytes: ByteArray, step: Int): ByteSource {
        var position = 0
        return ByteSource { into, offset, length ->
            if (position == bytes.size) -1 else {
                val n = minOf(length, step, bytes.size - position)
                bytes.copyInto(into, offset, position, position + n)
                position += n
                n
            }
        }
    }

    /** The decoded text, or the failure's class name. */
    fun arrayOutcome(codec: Codec, stream: ByteArray, limits: ArchiveLimits = ArchiveLimits.DEFAULT): String = try {
        codec.decompress(stream, limits).decodeToString()
    } catch (e: ArchiveException) { e::class.simpleName!! }

    fun streamOutcome(codec: Codec, stream: ByteArray, step: Int, limits: ArchiveLimits = ArchiveLimits.DEFAULT): String = try {
        val out = ByteArrayBuilder(1024)
        val count = codec.decompress(trickle(stream, step), { b, o, l -> out.append(b, o, l) }, limits)
        val bytes = out.takeByteArray()
        assertEquals(bytes.size.toLong(), count)
        bytes.decodeToString()
    } catch (e: ArchiveException) { e::class.simpleName!! }

    fun assertSame(codec: Codec, stream: ByteArray, what: String, limits: ArchiveLimits = ArchiveLimits.DEFAULT, steps: List<Int> = listOf(1, 7, 65_536)) {
        val expected = arrayOutcome(codec, stream, limits)
        for (step in steps) assertEquals(expected, streamOutcome(codec, stream, step, limits), "${codec.id} $what, step $step")
    }

    /** Pseudo-random text from a few words, with stray bytes, so it compresses but not trivially. */
    fun text(size: Int, seed: Int): ByteArray {
        var x = seed
        val words = listOf("stream ", "parity ", "window ", "table ", "code ").map { it.encodeToByteArray() }
        val out = ByteArrayBuilder(size)
        while (out.size < size) {
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            if (x and 7 == 0) out.append(byteArrayOf(x.toByte())) else out.append(words[(x ushr 1) % words.size])
        }
        return out.takeByteArray().copyOf(size)
    }
}
