package io.github.yuroyami.archivekodec.codec.deflate

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

private const val MAX_ARRAY = Int.MAX_VALUE - 8

internal actual fun platformInflate(
    input: ByteArray, offset: Int, length: Int, zlib: Boolean, sizeHint: Int, maxOutput: Long,
): PlatformInflated? {
    // One byte over the limit is room enough to see that the output passes it.
    val cap = if (maxOutput >= MAX_ARRAY) MAX_ARRAY else (maxOutput + 1).toInt()
    val inflater = Inflater(!zlib)
    try {
        inflater.setInput(input, offset, length)
        var out = ByteArray(minOf(if (sizeHint > 0) sizeHint else maxOf(length.toLong() * 4, 4096L).toInt(), cap).coerceAtLeast(1))
        var size = 0
        while (!inflater.finished()) {
            if (size == out.size) {
                // Full at the cap: more output is over the limit or past one array.
                if (out.size >= cap) return null
                out = out.copyOf(minOf(maxOf(out.size.toLong() * 2, 65536L), cap.toLong()).toInt())
            }
            val n = try {
                inflater.inflate(out, size, out.size - size)
            } catch (_: DataFormatException) {
                return null
            }
            size += n
            if (size > maxOutput) return null
            // No output with room left: the stream is cut short or needs a dictionary.
            if (n == 0 && !inflater.finished() && size < out.size) return null
        }
        return PlatformInflated(if (size == out.size) out else out.copyOf(size), length - inflater.remaining)
    } finally {
        inflater.end()
    }
}

internal actual fun platformDeflate(data: ByteArray, level: Int, strategy: Int): ByteArray? {
    val deflater = Deflater(level, true)
    try {
        deflater.setStrategy(strategy)
        deflater.setInput(data)
        deflater.finish()
        var out = ByteArray(maxOf(data.size / 2, 64))
        var size = 0
        while (!deflater.finished()) {
            if (size == out.size) {
                if (out.size >= MAX_ARRAY) return null
                out = out.copyOf(minOf(out.size.toLong() * 2, MAX_ARRAY.toLong()).toInt())
            }
            size += deflater.deflate(out, size, out.size - size)
        }
        return if (size == out.size) out else out.copyOf(size)
    } finally {
        deflater.end()
    }
}
