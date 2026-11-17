package io.github.yuroyami.archivekodec.codec.deflate

/** What a platform inflate decoded, and the input bytes its stream took. */
internal class PlatformInflated(val output: ByteArray, val consumed: Int)

/**
 * Inflate the raw DEFLATE stream, or the zlib stream when [zlib] is true, that starts at [offset]
 * with the platform's zlib. Null where the platform has none, and on damage, a preset dictionary,
 * a stream cut short or output over [maxOutput]: the pure decoder then decides.
 */
internal expect fun platformInflate(input: ByteArray, offset: Int, length: Int, zlib: Boolean, sizeHint: Int, maxOutput: Long): PlatformInflated?

/** Raw DEFLATE of [data] by the platform's zlib at [level] with zlib's [strategy] number, or null where there is none. */
internal expect fun platformDeflate(data: ByteArray, level: Int, strategy: Int): ByteArray?
